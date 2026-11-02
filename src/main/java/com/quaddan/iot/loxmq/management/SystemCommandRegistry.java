package com.quaddan.iot.loxmq.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Server-side allow-list for {@code POST /api/v1/system-command}, derived at
 * startup from the very same catalogue the {@code /system} page renders:
 * {@code META-INF/resources/js/system-commands.json}. The registry file is the
 * single source of truth — a command is runnable iff it appears there, so
 * adding an entry to the JSON both lists it in the UI and authorises it on the
 * backend, and nothing outside the curated set (notably user-management, token
 * and crypto commands) can ever be routed through this diagnostic surface.
 * <p>
 * Parsed with {@link ObjectMapper#readTree} into a {@link JsonNode} tree (no
 * reflective binding to a DTO) so it stays native-image safe; the JSON file is
 * declared under {@code quarkus.native.resources.includes} so the classloader
 * can still read it from the native binary.
 * <p>
 * Fail-closed: if the catalogue cannot be read or is empty, every command is
 * rejected rather than silently widening access.
 */
@ApplicationScoped
public class SystemCommandRegistry
{
    private static final Logger LOG = Logger.getLogger( SystemCommandRegistry.class );

    private static final String RESOURCE = "META-INF/resources/js/system-commands.json";

    @Inject
    ObjectMapper jsonMapper;

    /** Commands allowed verbatim (no trailing argument). */
    private Set<String> exact = Collections.emptySet();

    /** Commands carrying a {@code param} — allowed as {@code <cmd>/<value>}. */
    private Set<String> paramPrefixes = Collections.emptySet();

    @PostConstruct
    void load()
    {
        Set<String> exactSet = new LinkedHashSet<>();
        Set<String> paramSet = new LinkedHashSet<>();
        try ( InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream( RESOURCE ) )
        {
            if ( in == null )
            {
                LOG.errorf( "System-command catalogue not found on classpath (%s) — all system commands will be rejected", RESOURCE );
                return;
            }
            JsonNode root = jsonMapper.readTree( in );
            for ( JsonNode domain : root.path( "domains" ) )
            {
                for ( JsonNode command : domain.path( "commands" ) )
                {
                    String cmd = command.path( "cmd" ).asText( "" ).trim();
                    if ( cmd.isEmpty() )
                    {
                        continue;
                    }
                    if ( command.has( "param" ) )
                    {
                        paramSet.add( cmd );
                    }
                    else
                    {
                        exactSet.add( cmd );
                    }
                }
            }
        }
        catch ( Exception e )
        {
            LOG.errorf( e, "Failed to load system-command catalogue (%s) — all system commands will be rejected", RESOURCE );
            return;
        }
        this.exact = Collections.unmodifiableSet( exactSet );
        this.paramPrefixes = Collections.unmodifiableSet( paramSet );
        LOG.infof( "System-command catalogue loaded: %d fixed + %d parameterised commands allowed",
                   exact.size(), paramPrefixes.size() );
    }

    /**
     * @return {@code true} iff {@code cmd} is an exact catalogue entry, or the
     *         {@code <cmd>/<value>} form of a parameterised one.
     */
    public boolean isAllowed( String cmd )
    {
        if ( cmd == null || cmd.isBlank() )
        {
            return false;
        }
        if ( exact.contains( cmd ) )
        {
            return true;
        }
        for ( String prefix : paramPrefixes )
        {
            if ( cmd.startsWith( prefix + "/" ) && cmd.length() > prefix.length() + 1 )
            {
                return true;
            }
        }
        return false;
    }
}
