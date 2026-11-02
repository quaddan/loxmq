/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for the {@code /system} page's server-side allow-list.
 *
 * <p>Drives {@link SystemCommandRegistry} directly as a POJO (no CDI): it just
 * needs an {@link ObjectMapper} and reads the real
 * {@code META-INF/resources/js/system-commands.json} off the classpath — the
 * same catalogue the UI renders. The contract under test is the security
 * invariant: a command runs <em>iff</em> it is listed, so user-management /
 * token / crypto commands can never slip through even though they share the
 * {@code jdev/sys/} namespace.
 */
class SystemCommandRegistryTest
{
    private SystemCommandRegistry registry;

    @BeforeEach
    void setUp()
    {
        registry = new SystemCommandRegistry();
        registry.jsonMapper = new ObjectMapper();
        registry.load();
    }

    @Test
    @DisplayName( "catalogued read-only commands are allowed across all four domains" )
    void allowsCataloguedCommands()
    {
        assertThat( registry.isAllowed( "jdev/sys/cpu" ) ).isTrue();
        assertThat( registry.isAllowed( "jdev/sps/state" ) ).isTrue();
        assertThat( registry.isAllowed( "jdev/cfg/version" ) ).isTrue();
        assertThat( registry.isAllowed( "jdev/bus/frameerrors" ) ).isTrue();
    }

    @Test
    @DisplayName( "the parameterised setdatetime accepts <cmd>/<value> but not the bare command" )
    void allowsParameterisedCommand()
    {
        assertThat( registry.isAllowed( "jdev/sys/setdatetime/2026-11-02 10:00:00" ) ).isTrue();
        assertThat( registry.isAllowed( "jdev/sys/setdatetime" ) ).isFalse();
    }

    @Test
    @DisplayName( "user / token / crypto / control commands are refused by construction" )
    void refusesNonCatalogued()
    {
        assertThat( registry.isAllowed( "jdev/sys/killtoken/a/b" ) ).isFalse();
        assertThat( registry.isAllowed( "jdev/sys/getjwt/x" ) ).isFalse();
        assertThat( registry.isAllowed( "jdev/sys/getPublicKey" ) ).isFalse();
        assertThat( registry.isAllowed( "jdev/sps/getuserlist2" ) ).isFalse();
        assertThat( registry.isAllowed( "jdev/cfg/apiKey" ) ).isFalse();
        assertThat( registry.isAllowed( "jdev/sps/io/0f12/on" ) ).isFalse();
    }

    @Test
    @DisplayName( "null / blank / unknown / exact-only-with-suffix are refused" )
    void refusesJunk()
    {
        assertThat( registry.isAllowed( null ) ).isFalse();
        assertThat( registry.isAllowed( "" ) ).isFalse();
        assertThat( registry.isAllowed( "jdev/sys/notacommand" ) ).isFalse();
        // a fixed (non-parameterised) command matches EXACTLY — no trailing segment.
        assertThat( registry.isAllowed( "jdev/sys/cpu/extra" ) ).isFalse();
    }
}
