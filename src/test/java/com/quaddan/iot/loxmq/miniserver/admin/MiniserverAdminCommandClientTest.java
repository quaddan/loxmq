/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quaddan.iot.loxmq.miniserver.http.InvalidLoxoneResponseException;
import com.quaddan.iot.loxmq.config.LoxoneConfig;
import com.quaddan.iot.loxmq.miniserver.crypto.LoxoneCryptoService;
import com.quaddan.iot.loxmq.miniserver.http.LoxoneJsonParser;
import com.quaddan.iot.loxmq.miniserver.http.MiniserverHttpClient;
import com.quaddan.iot.loxmq.miniserver.session.MiniserverToken;
import com.quaddan.iot.loxmq.miniserver.session.SessionState;
import com.quaddan.iot.loxmq.miniserver.session.SessionTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MiniserverAdminCommandClient} — HTTPS + autht
 * transport.
 *
 * <p>Plain JUnit (no Quarkus boot) — the client's fields are package-
 * private so we can inject mocks directly. Tests the request construction
 * (autht hashing via crypto, user decode), the response parsing, and the
 * mapping of HTTP-layer errors to {@link AdminCommandException}.
 */
@DisplayName( "MiniserverAdminCommandClient — HTTPS + autht" )
class MiniserverAdminCommandClientTest
{
    private static final String FAKE_TOKEN     = "eyJhbGciOiJIUzI1NiJ9.fake.jwt";
    private static final String FAKE_JWT_KEY   = "ABCDEF0123456789ABCDEF0123456789";    // token.key()
    private static final String FAKE_HASH_KEY  = "FEDCBA9876543210FEDCBA9876543210";    // from /jdev/sys/getkey/{user}
    private static final String FAKE_HASH_BODY = "{\"LL\":{\"Code\":\"200\",\"value\":\"FEDCBA9876543210FEDCBA9876543210\"}}";
    private static final String FAKE_HASH      = "deadbeefcafebabe";
    private static final String USER_B64       = "YWRtaW4=";    // base64 of "admin"

    private MiniserverAdminCommandClient client;
    private MiniserverHttpClient         httpClient;
    private SessionTracker               tracker;
    private LoxoneCryptoService          crypto;
    private LoxoneJsonParser             parser;

    @BeforeEach
    void setUp() throws Exception
    {
        httpClient = mock( MiniserverHttpClient.class );
        tracker    = mock( SessionTracker.class );
        crypto     = mock( LoxoneCryptoService.class );
        parser     = mock( LoxoneJsonParser.class );
        LoxoneConfig config = mock( LoxoneConfig.class, RETURNS_DEEP_STUBS );
        when( config.miniserver().security().credentials().user() ).thenReturn( USER_B64 );
        // Stubs of the Semaphore getters.
        when( config.miniserver().connection().http().adminMaxConcurrent() ).thenReturn( 4 );
        when( config.miniserver().connection().http().adminWaitTimeout() )
                .thenReturn( java.time.Duration.ofSeconds( 5 ) );

        client            = new MiniserverAdminCommandClient();
        client.httpClient = httpClient;
        client.tracker    = tracker;
        client.crypto     = crypto;
        client.config     = config;
        client.parser     = parser;
        client.jsonMapper = new ObjectMapper();
        // initSemaphore() is a CDI @PostConstruct. In a unit test we
        // call it manually to reproduce the container bootstrap.
        client.initSemaphore();

        // Default — most tests assume RUNNING + valid token.
        when( tracker.state() ).thenReturn( SessionState.RUNNING );
        when( tracker.token() ).thenReturn( Optional.of(
                new MiniserverToken( FAKE_TOKEN, FAKE_JWT_KEY, 999999999L, 1668, false ) ) );

        // The HMAC key comes from /jdev/sys/getkey/{user}, not from
        // token.key(). Stub the fetchHashKey → parseHashKey →
        // hashToken chain so it produces a deterministic hash.
        when( httpClient.fetchHashKey( "admin" ) ).thenReturn( FAKE_HASH_BODY );
        when( parser.parseHashKey( FAKE_HASH_BODY ) ).thenReturn( FAKE_HASH_KEY );
        when( crypto.hashToken( FAKE_HASH_KEY, FAKE_TOKEN ) ).thenReturn( FAKE_HASH );
    }

    // ============================================================
    //  Preconditions
    // ============================================================

    @Test
    @DisplayName( "session DISCONNECTED → IllegalStateException, no HTTPS call" )
    void rejectsWhenSessionNotRunning()
    {
        when( tracker.state() ).thenReturn( SessionState.DISCONNECTED );

        assertThatThrownBy( () -> client.sendAndAwait( "calendargetentries", Duration.ofSeconds( 8 ) ) )
                .isInstanceOf( IllegalStateException.class )
                .hasMessageContaining( "DISCONNECTED" );

        verify( httpClient, times( 0 ) ).sendAuthenticatedAdminGet( anyString(), anyString(), anyString() );
    }

    @Test
    @DisplayName( "session RUNNING but no token → IllegalStateException" )
    void rejectsWhenNoTokenCached()
    {
        when( tracker.token() ).thenReturn( Optional.empty() );

        assertThatThrownBy( () -> client.sendAndAwait( "calendargetentries", Duration.ofSeconds( 8 ) ) )
                .isInstanceOf( IllegalStateException.class )
                .hasMessageContaining( "no token cached" );
    }

    @Test
    @DisplayName( "blank pathSegment → IllegalArgumentException" )
    void rejectsBlankPath()
    {
        assertThatThrownBy( () -> client.sendAndAwait( "  ", Duration.ofSeconds( 1 ) ) )
                .isInstanceOf( IllegalArgumentException.class );
        assertThatThrownBy( () -> client.sendAndAwait( null, Duration.ofSeconds( 1 ) ) )
                .isInstanceOf( IllegalArgumentException.class );
    }

    // ============================================================
    //  Happy path
    // ============================================================

    @Test
    @DisplayName( "happy path : HMAC + HTTPS + parse → LL subtree returned" )
    void happyPath() throws Exception
    {
        String body = "{\"LL\":{\"control\":\"jdev/sps/calendargetentries\","
                      + "\"value\":[{\"uuid\":\"x\"}],\"Code\":\"200\"}}";
        when( httpClient.sendAuthenticatedAdminGet( anyString(), anyString(), anyString() ) )
                .thenReturn( body );

        JsonNode ll = client.sendAndAwait( "calendargetentries", Duration.ofSeconds( 8 ) );

        assertThat( ll.path( "Code" ).asText() ).isEqualTo( "200" );
        assertThat( ll.path( "value" ).isArray() ).isTrue();

        // Verify the path passed to httpClient is the full jdev/sps/... form
        // + the user is DECODED (not base64) + the hash comes from crypto.
        ArgumentCaptor< String > pathCap = ArgumentCaptor.forClass( String.class );
        ArgumentCaptor< String > userCap = ArgumentCaptor.forClass( String.class );
        ArgumentCaptor< String > hashCap = ArgumentCaptor.forClass( String.class );
        verify( httpClient ).sendAuthenticatedAdminGet(
                pathCap.capture(), userCap.capture(), hashCap.capture() );

        assertThat( pathCap.getValue() ).isEqualTo( "jdev/sps/calendargetentries" );
        assertThat( userCap.getValue() ).isEqualTo( "admin" );             // decoded
        assertThat( hashCap.getValue() ).isEqualTo( FAKE_HASH );           // from crypto

        // The HMAC key comes from fetchHashKey + parseHashKey (not from
        // token.key()).
        verify( httpClient ).fetchHashKey( "admin" );
        verify( parser ).parseHashKey( FAKE_HASH_BODY );
        verify( crypto ).hashToken( FAKE_HASH_KEY, FAKE_TOKEN );
    }

    @Test
    @DisplayName( "response without LL envelope → falls back to root" )
    void handlesMissingLLEnvelope() throws Exception
    {
        // Some older firmware variants return the payload at root.
        String body = "{\"value\":[],\"Code\":\"200\"}";
        when( httpClient.sendAuthenticatedAdminGet( anyString(), anyString(), anyString() ) )
                .thenReturn( body );

        JsonNode root = client.sendAndAwait( "foo", Duration.ofSeconds( 1 ) );
        assertThat( root.path( "Code" ).asText() ).isEqualTo( "200" );
    }

    // ============================================================
    //  Error mapping
    // ============================================================

    @Test
    @DisplayName( "HTTPS 403 → AdminCommandException with decorated message" )
    void mapsHttp403ToAdminException()
    {
        when( httpClient.sendAuthenticatedAdminGet( anyString(), anyString(), anyString() ) )
                .thenThrow( new InvalidLoxoneResponseException(
                        "HTTP 403 from https://miniserver/jdev/sps/getuserlist2?autht=...&user=admin" ) );

        assertThatThrownBy( () -> client.sendAndAwait( "getuserlist2", Duration.ofSeconds( 1 ) ) )
                .isInstanceOf( AdminCommandException.class )
                .hasMessageContaining( "Code=403" )
                .hasMessageContaining( "lacks the required permission" )
                .hasMessageContaining( "Loxone Config" );
    }

    @Test
    @DisplayName( "HTTPS 401 → AdminCommandException with same decorated message" )
    void mapsHttp401ToAdminException()
    {
        when( httpClient.sendAuthenticatedAdminGet( anyString(), anyString(), anyString() ) )
                .thenThrow( new InvalidLoxoneResponseException( "HTTP 401 from ..." ) );

        assertThatThrownBy( () -> client.sendAndAwait( "getuserlist2", Duration.ofSeconds( 1 ) ) )
                .isInstanceOf( AdminCommandException.class )
                .hasMessageContaining( "Code=401" );
    }

    @Test
    @DisplayName( "Other HTTP / I/O failures → generic AdminCommandException" )
    void mapsOtherErrorsGenerically()
    {
        when( httpClient.sendAuthenticatedAdminGet( anyString(), anyString(), anyString() ) )
                .thenThrow( new InvalidLoxoneResponseException( "I/O failure ..." ) );

        assertThatThrownBy( () -> client.sendAndAwait( "getuserlist2", Duration.ofSeconds( 1 ) ) )
                .isInstanceOf( AdminCommandException.class )
                .hasMessageContaining( "HTTPS GET failed" );
    }

    @Test
    @DisplayName( "Malformed JSON in HTTP response → AdminCommandException" )
    void mapsMalformedJsonToAdminException()
    {
        when( httpClient.sendAuthenticatedAdminGet( anyString(), anyString(), anyString() ) )
                .thenReturn( "this is not json {{{ }" );

        assertThatThrownBy( () -> client.sendAndAwait( "foo", Duration.ofSeconds( 1 ) ) )
                .isInstanceOf( AdminCommandException.class )
                .hasMessageContaining( "Could not parse admin reply" );
    }

    // ============================================================
    //  unwrapValue
    // ============================================================

    @Test
    @DisplayName( "unwrapValue() returns native JsonNode untouched" )
    void unwrapValueNative() throws Exception
    {
        JsonNode ll = client.jsonMapper.readTree(
                "{\"value\":[{\"x\":1}],\"Code\":\"200\"}" );
        JsonNode v = client.unwrapValue( ll );
        assertThat( v.isArray() ).isTrue();
        assertThat( v.get( 0 ).path( "x" ).asInt() ).isEqualTo( 1 );
    }

    @Test
    @DisplayName( "unwrapValue() re-parses JSON-encoded string value" )
    void unwrapValueReparses() throws Exception
    {
        JsonNode ll = client.jsonMapper.readTree(
                "{\"value\":\"[{\\\"x\\\":1}]\",\"Code\":\"200\"}" );
        JsonNode v = client.unwrapValue( ll );
        assertThat( v.isArray() ).isTrue();
        assertThat( v.get( 0 ).path( "x" ).asInt() ).isEqualTo( 1 );
    }

    @Test
    @DisplayName( "unwrapValue() leaves scalar strings alone (e.g. heat-period)" )
    void unwrapValueScalarString() throws Exception
    {
        JsonNode ll = client.jsonMapper.readTree(
                "{\"value\":\"10-15/04-15\",\"Code\":\"200\"}" );
        JsonNode v = client.unwrapValue( ll );
        assertThat( v.isTextual() ).isTrue();
        assertThat( v.asText() ).isEqualTo( "10-15/04-15" );
    }

    // ============================================================
    //  Retry-once on transient
    // ============================================================

    @Test
    @DisplayName( "isRetryable() — autht / HTTPS / 401 are retryable" )
    void isRetryableMatrix()
    {
        assertThat( MiniserverAdminCommandClient.isRetryable(
                new AdminCommandException( "Failed to compute autht hash for X — boom" ) ) ).isTrue();
        assertThat( MiniserverAdminCommandClient.isRetryable(
                new AdminCommandException( "HTTPS GET failed for X — read timeout" ) ) ).isTrue();
        assertThat( MiniserverAdminCommandClient.isRetryable(
                new AdminCommandException( "Miniserver rejected X : Code=401" ) ) ).isTrue();

        // Non-retryable cases :
        assertThat( MiniserverAdminCommandClient.isRetryable(
                new AdminCommandException( "Miniserver rejected X : Code=403" ) ) ).isFalse();
        assertThat( MiniserverAdminCommandClient.isRetryable(
                new AdminCommandException( "Miniserver rejected X : Code=400" ) ) ).isFalse();
        assertThat( MiniserverAdminCommandClient.isRetryable(
                new AdminCommandException( "Could not parse admin reply for X" ) ) ).isFalse();
        // Defensive — null / empty msg never retried
        assertThat( MiniserverAdminCommandClient.isRetryable(
                new AdminCommandException( "" ) ) ).isFalse();
    }

    @Test
    @DisplayName( "Retry once on autht computation blip → 2nd attempt succeeds" )
    void retriesOnTransientAuthtFailure() throws Exception
    {
        // 1st fetchHashKey throws (network blip) — 2nd succeeds with the
        // canonical body. The retry triggers a fresh fetchHashKey +
        // parseHashKey + hashToken cycle.
        when( httpClient.fetchHashKey( "admin" ) )
                .thenThrow( new RuntimeException( "connection reset" ) )
                .thenReturn( FAKE_HASH_BODY );

        String body = "{\"LL\":{\"control\":\"jdev/sps/getuserlist2\","
                      + "\"value\":[],\"Code\":\"200\"}}";
        when( httpClient.sendAuthenticatedAdminGet( anyString(), anyString(), anyString() ) )
                .thenReturn( body );

        JsonNode ll = client.sendAndAwait( "getuserlist2", Duration.ofSeconds( 1 ) );
        assertThat( ll.path( "Code" ).asText() ).isEqualTo( "200" );

        // fetchHashKey called twice (1st throws, 2nd OK), GET called once
        // (only on the retry — the 1st attempt died before reaching GET).
        verify( httpClient, times( 2 ) ).fetchHashKey( "admin" );
        verify( httpClient, times( 1 ) ).sendAuthenticatedAdminGet(
                anyString(), anyString(), anyString() );
    }

    @Test
    @DisplayName( "Retry once on HTTPS network failure (non-4xx) → 2nd succeeds" )
    void retriesOnHttpsNetworkFailure() throws Exception
    {
        String body = "{\"LL\":{\"value\":[],\"Code\":\"200\"}}";
        when( httpClient.sendAuthenticatedAdminGet( anyString(), anyString(), anyString() ) )
                .thenThrow( new InvalidLoxoneResponseException( "I/O failure on first attempt" ) )
                .thenReturn( body );

        JsonNode ll = client.sendAndAwait( "getuserlist2", Duration.ofSeconds( 1 ) );
        assertThat( ll.path( "Code" ).asText() ).isEqualTo( "200" );

        // GET called twice — autht refreshed before each call too.
        verify( httpClient, times( 2 ) ).sendAuthenticatedAdminGet(
                anyString(), anyString(), anyString() );
        verify( httpClient, times( 2 ) ).fetchHashKey( "admin" );
    }

    @Test
    @DisplayName( "Retry once on Miniserver Code=401 (autht rejected) → 2nd succeeds" )
    void retriesOn401() throws Exception
    {
        String body = "{\"LL\":{\"value\":[],\"Code\":\"200\"}}";
        when( httpClient.sendAuthenticatedAdminGet( anyString(), anyString(), anyString() ) )
                .thenThrow( new InvalidLoxoneResponseException( "HTTP 401 from miniserver" ) )
                .thenReturn( body );

        JsonNode ll = client.sendAndAwait( "getuser/0a5fa72f-018b-0050-1900000000000000",
                                           Duration.ofSeconds( 1 ) );
        assertThat( ll.path( "Code" ).asText() ).isEqualTo( "200" );

        // GET called twice ; key refetched between attempts so the
        // Miniserver gets a fresh HMAC on the retry.
        verify( httpClient, times( 2 ) ).sendAuthenticatedAdminGet(
                anyString(), anyString(), anyString() );
        verify( httpClient, times( 2 ) ).fetchHashKey( "admin" );
    }

    @Test
    @DisplayName( "Do NOT retry on Code=403 — single attempt only" )
    void noRetryOn403()
    {
        when( httpClient.sendAuthenticatedAdminGet( anyString(), anyString(), anyString() ) )
                .thenThrow( new InvalidLoxoneResponseException( "HTTP 403 from miniserver" ) );

        assertThatThrownBy( () -> client.sendAndAwait( "getuserlist2", Duration.ofSeconds( 1 ) ) )
                .isInstanceOf( AdminCommandException.class )
                .hasMessageContaining( "Code=403" );

        // Exactly ONE attempt — no retry on permission errors
        verify( httpClient, times( 1 ) ).sendAuthenticatedAdminGet(
                anyString(), anyString(), anyString() );
        verify( httpClient, times( 1 ) ).fetchHashKey( "admin" );
    }

    @Test
    @DisplayName( "Do NOT retry on malformed JSON response — single attempt only" )
    void noRetryOnParseFailure()
    {
        when( httpClient.sendAuthenticatedAdminGet( anyString(), anyString(), anyString() ) )
                .thenReturn( "this is not json {{{ }" );

        assertThatThrownBy( () -> client.sendAndAwait( "foo", Duration.ofSeconds( 1 ) ) )
                .isInstanceOf( AdminCommandException.class )
                .hasMessageContaining( "Could not parse admin reply" );

        verify( httpClient, times( 1 ) ).sendAuthenticatedAdminGet(
                anyString(), anyString(), anyString() );
    }

    @Test
    @DisplayName( "Two consecutive transient failures → final exception propagates" )
    void twoFailuresPropagate()
    {
        when( httpClient.fetchHashKey( "admin" ) )
                .thenThrow( new RuntimeException( "blip 1" ) )
                .thenThrow( new RuntimeException( "blip 2" ) );

        assertThatThrownBy( () -> client.sendAndAwait( "foo", Duration.ofSeconds( 1 ) ) )
                .isInstanceOf( AdminCommandException.class )
                .hasMessageContaining( "Failed to compute autht hash" )
                .hasMessageContaining( "blip 2" );    // last failure surfaces

        // Two attempts, both fail, no admin GET ever happens
        verify( httpClient, times( 2 ) ).fetchHashKey( "admin" );
        verify( httpClient, times( 0 ) ).sendAuthenticatedAdminGet(
                anyString(), anyString(), anyString() );
    }

    // ============================================================
    //  Semaphore concurrency cap
    // ============================================================

    @Test
    @DisplayName( "Semaphore: sendAndAwait releases the permit even on exception" )
    void semaphoreReleasesOnException()
    {
        // Force every sendAndAwait to throw. If the sendAndAwait
        // finally does not release, we would be blocked after 4 calls.
        when( httpClient.sendAuthenticatedAdminGet( anyString(), anyString(), anyString() ) )
                .thenThrow( new InvalidLoxoneResponseException( "HTTP 500 boom" ) );

        // 10 calls in a row — if the release works, all exit with an
        // exception without blocking on the Semaphore acquire.
        for ( int i = 0; i < 10; i++ )
        {
            assertThatThrownBy( () -> client.sendAndAwait( "foo", Duration.ofSeconds( 1 ) ) )
                    .isInstanceOf( AdminCommandException.class );
        }
        // After 10 calls, all permits are still available.
        assertThat( client.concurrencyLimit.availablePermits() ).isEqualTo( 4 );
    }

    @Test
    @DisplayName( "Semaphore: 5 parallel calls → 4 simultaneous max, the 5th waits" )
    void semaphoreCapsConcurrency() throws Exception
    {
        // Server-side latch: every call blocks until release. Lets us
        // measure how many run in parallel in-flight.
        java.util.concurrent.CountDownLatch       holdRelease  = new java.util.concurrent.CountDownLatch( 1 );
        java.util.concurrent.atomic.AtomicInteger inFlight     = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger peakInFlight = new java.util.concurrent.atomic.AtomicInteger();

        when( httpClient.sendAuthenticatedAdminGet( anyString(), anyString(), anyString() ) )
                .thenAnswer( inv ->
                             {
                                 int current = inFlight.incrementAndGet();
                                 peakInFlight.updateAndGet( prev -> Math.max( prev, current ) );
                                 holdRelease.await( 5, TimeUnit.SECONDS );
                                 inFlight.decrementAndGet();
                                 return "{\"LL\":{\"value\":[],\"Code\":\"200\"}}";
                             } );

        // Launch 5 calls in parallel; Semaphore cap=4 must serialize:
        // 4 enter in-flight, the 5th waits.
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool( 5 );
        java.util.List< java.util.concurrent.Future< ? > > futures = new java.util.ArrayList<>();
        for ( int i = 0; i < 5; i++ )
        {
            futures.add( pool.submit( () ->
                                              client.sendAndAwait( "foo", Duration.ofSeconds( 1 ) ) ) );
        }

        // Give the first 4 time to enter in-flight and block.
        Thread.sleep( 300 );
        assertThat( inFlight.get() )
                .as( "Semaphore must cap at 4 in-flight max" )
                .isLessThanOrEqualTo( 4 );

        // Release all → the 5 complete.
        holdRelease.countDown();
        for ( var f : futures )
        {
            f.get( 5, TimeUnit.SECONDS );
        }
        pool.shutdown();

        // Peak in-flight reaches 4 but is never exceeded.
        assertThat( peakInFlight.get() ).isEqualTo( 4 );
        // The 5th must have waited at least once.
        assertThat( client.pendingWaitCount() ).isGreaterThanOrEqualTo( 1 );
    }

    @Test
    @DisplayName( "Semaphore: wait-timeout exceeded → fail-fast AdminCommandException" )
    void semaphoreWaitTimeoutFailsFast()
    {
        // Acquire ALL permits to saturate the pool.
        client.concurrencyLimit.drainPermits();
        try
        {
            // Short wait-timeout so we don't wait 30s in CI.
            when( client.config.miniserver().connection().http().adminWaitTimeout() )
                    .thenReturn( Duration.ofMillis( 100 ) );

            long start = System.currentTimeMillis();
            assertThatThrownBy( () -> client.sendAndAwait( "foo", Duration.ofSeconds( 1 ) ) )
                    .isInstanceOf( AdminCommandException.class )
                    .hasMessageContaining( "Timeout waiting for admin slot" );
            long elapsed = System.currentTimeMillis() - start;
            // Fails between 100ms (waitTimeout) and ~500ms (margin) — does not hang.
            assertThat( elapsed ).isBetween( 50L, 500L );
        }
        finally
        {
            client.concurrencyLimit.release( 4 );
        }
    }

    @Test
    @DisplayName( "Semaphore: config max=0 → fallback to 1 + warning" )
    void semaphoreFallbackOnInvalidConfig()
    {
        when( client.config.miniserver().connection().http().adminMaxConcurrent() ).thenReturn( 0 );
        client.initSemaphore();

        assertThat( client.concurrencyLimit.availablePermits() )
                .as( "Fallback to 1 when max < 1" )
                .isEqualTo( 1 );
    }
}
