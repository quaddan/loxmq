<img src="src/main/resources/META-INF/resources/images/loxmq-logo.svg" alt="loxmq">

# TESTS

Practical guide to the module's test strategy.

---

## TL;DR

Two test tiers, two commands:

```bash
# Unit (in-JVM, fast, ~10 s).
./mvnw test
#  → tests under src/test/java/*Test.java, mocked/faked via QuarkusMock + @TestProfile.

# Integration (Quarkus subprocess + Docker, ~2 min, opt-in).
./mvnw verify -Pintegration
#  → unit + IT (*IT.java) against Mosquitto Docker + fake Miniserver Vert.x.
```

> **⚠️ A project `.env` overrides test config — move it aside before testing.**
> Quarkus auto-loads a `.env` file from the project root as a **high-priority**
> config source (DotEnvConfigSource), for `./mvnw test` too, even if you never
> `source` it. If your `.env` sets `LOXONE_*` keys (e.g. broker credentials),
> they override the `test`-profile fixtures and `LoxoneConfigInjectionTest`
> fails with a mismatch such as
> `expected <Optional[ZmFrZV91c2Vy]> but was <Optional[…]>`. This is **not** a
> real failure — CI is green precisely because it has no `.env`. Run the suite
> with the file parked:
>
> ```bash
> mv .env .env.disabled && ./mvnw test ; mv .env.disabled .env
> ```

Current inventory:

| Tier                             | Files               | Speed | When to use it                                                                                                               |
|----------------------------------|---------------------|-------|------------------------------------------------------------------------------------------------------------------------------|
| Unit (`@QuarkusTest`)            | **49** `*Test.java` | ~10 s | Everything, by default. Business logic, parsing, crypto, state.                                                              |
| IT   (`@QuarkusIntegrationTest`) | **13** `*IT.java`   | ~45 s | Validation of the packaged jar against real external services (Mosquitto Docker) or simulated ones (fake miniserver Vert.x). |

---

## Table of contents

1. [Why two tiers](#1-why-two-tiers)
2. [Unit tier — `@QuarkusTest`](#2-unit-tier---quarkustest)
3. [Integration tier — `@QuarkusIntegrationTest`](#3-integration-tier---quarkusintegrationtest)
4. [The test resources](#4-the-test-resources)
5. [The CDI + SmallRye rule](#5-the-cdi--smallrye-rule)
6. [Command cheat-sheet](#6-command-cheat-sheet)
7. [File map](#7-file-map)
8. [Deciding where to place a new test](#8-deciding-where-to-place-a-new-test)
9. [Anti-patterns](#9-anti-patterns)
10. [What is not tested](#10-what-is-not-tested)

---

## 1. Why two tiers

Quarkus has a native distinction:

- **`@QuarkusTest`** boots the application **in the same JVM** as JUnit.
  You have access to the CDI container via `@Inject`, you can mock beans
  via `QuarkusMock.installMockForType(...)`, you can replace the
  config via a `@TestProfile`. Fast (~100 ms per class to start
  Quarkus) and richly instrumentable.

- **`@QuarkusIntegrationTest`** first **packages** the app (mvn `package`
  → fast-jar in `target/quarkus-app/`), then **launches it in a
  separate subprocess**. JUnit communicates with it via HTTP (RestAssured)
  only. **No CDI access**. Slower (~3 s per class to boot
  the subprocess) but it's the only way to validate:
    - That all resources are effectively copied into
      `target/quarkus-app/` (Qute templates, OpenAPI, config files).
    - That profile selection at startup works (the JAR boots in `prod`
      unless overridden by `quarkus.test.integration-test-profile=dev` on
      the Failsafe side).
    - That CDI beans are really wired in the prod classpath
      (not just in the test classpath).
    - That native-image generation bugs (reflection, resources) don't
      break anything (when `-Pnative` is enabled in addition).

> ⚠️ **Important: do NOT run `ApplicationSmokeIT` from the IDE.**
> IntelliJ / VS Code use their own JUnit runner which **does not apply**
> the `quarkus.test.integration-test-profile=dev` config from
> `pom.xml`. The JAR then boots in `prod` by default, reads the certs
> paths (`/opt/loxmq/certs/...` in Option B, or
> `/etc/letsencrypt/live/example.com/...` in legacy layout) which
> don't exist locally, and `Failed to start application`.
>
> **Always run ITs via Maven**:
>
> ```bash
> ./mvnw verify -Pintegration -Dit.test=ApplicationSmokeIT
> ```
>
> If you MUST debug an IT from the IDE, manually add to the
> run config:
>
> ```
> -Dquarkus.test.integration-test-profile=dev
> -Dloxone.boot.auto-start=false
> ```
>
> For staging and prod profiles, see `BootProfileSmokeIT`
> which handles self-signed certs via openssl + path overrides at
> launch.

Concretely:

| Question                                                              | Unit                       | IT                        |
|-----------------------------------------------------------------------|----------------------------|---------------------------|
| "Does my JSON parser handle case X correctly?"                        | ✅                          | ❌ too heavy               |
| "Does the app boot with my new config?"                               | ✅ partial                  | ✅ full                    |
| "Does my endpoint return the right shape?"                            | ✅ via RestAssured          | ✅ via RestAssured         |
| "Does the binding know how to reconnect to the broker when it drops?" | partial via FakeMqttClient | ✅ `BrokerCrashRecoveryIT` |
| "End-to-end MQTT command in the packaged JAR?"                        | ❌ not testable             | ✅ `CommandRoundTripIT`    |

---

## 2. Unit tier — `@QuarkusTest`

### Location

`src/test/java/com/quaddan/iot/loxmq/...`
(same packages as the prod code).

### Tools

- **`@Inject`** as in prod — full access to the CDI container.
- **`QuarkusMock.installMockForType(fake, RealBean.class)`** —
  replaces a bean with a fake, valid for the duration of the test
  class. Used to mock `HiveMqClient` with `FakeMqttClient`
  (`src/test/java/.../transport/FakeMqttClient.java`).
- **`@TestProfile(MyProfile.class)`** where `MyProfile implements
  QuarkusTestProfile` — override `getConfigOverrides()` to point
  the app to an embedded server (JDK HttpServer, random port).
  Examples: `BootstrapOrchestratorTest.FakeMiniserverProfile`,
  `SessionOrchestratorTest.FakeMiniserverProfile`.
- **In-JVM embedded servers**: `com.sun.net.httpserver.HttpServer`
  to simulate the miniserver on the HTTP side (apiKey, getPublicKey,
  getkey2). Seen in `MiniserverHttpClientTest`,
  `BootstrapOrchestratorTest`, `SessionOrchestratorTest`.
- **AssertJ + Hamcrest + RestAssured** — depending on context.

### The `FakeMqttClient` pattern

To test everything that publishes to MQTT without starting a broker:

```java
// in the test
private FakeMqttClient fake;

@BeforeEach
void install()
{
    fake = new FakeMqttClient();
    QuarkusMock.installMockForType( fake, HiveMqClient.class );
    fake.connect();
}

@Test
void publishesOnExpectedTopic()
{
    bus.fireAsync( new SomeEvent(...) );
    FakeMqttClient.Publish p = awaitPublishOn( "expected/topic" );
    assertThat( p.retain() ).isTrue();
    assertThat( new String( p.payload(), UTF_8 ) ).isEqualTo( "..." );
}
```

This is the pattern used by `AppInfoPublisherTest`,
`LoxApp3PublisherTest`, `StatesPublisherTest`,
`CommandResponsePublisherTest`, `OutOfServiceMqttReconnector` test.

### The `FakeMiniserverWebSocket` pattern (in-JVM only)

`SessionOrchestratorTest` injects a fake that extends
`JdkMiniserverWebSocket` (the prod WS client) and exposes
`queueReply(String)`. The test scripts the conversation:

```java
fakeWs.queueReply( "{\"LL\":{\"control\":\"jdev/sys/keyexchange/...\",\"value\":\"ack\",\"Code\":\"200\"}}" );
fakeWs.

queueReply( "{\"LL\":{\"control\":\"jdev/sys/getjwt/...\",\"value\":{...token...},\"Code\":\"200\"}}" );

// ... etc.
MiniserverToken token = orchestrator.connectAndWait( 5 );
```

Important: **this pattern only works in `@QuarkusTest`** (CDI mock
installed by `QuarkusMock`). For the same thing at IT level you need
a real WebSocket server (see `FakeMiniserverFullResource` in §4).

### Current coverage (selection)

| Test class                              | What                                                   |
|-----------------------------------------|--------------------------------------------------------|
| `LoxoneConfigTest` (14)                 | Validation of the `@ConfigMapping` tree.               |
| `MiniserverVersionTest` (9)             | Parsing "X.Y.Z.B", comparison.                         |
| `LoxoneCryptoServiceTest` (11)          | RSA-OAEP, AES-CBC, HMAC.                               |
| `BinaryStatesDecoderTest` (9)           | Binary vector decoding — all event-tables.             |
| `BootstrapOrchestratorTest` (5)         | Full HTTP bootstrap against embedded JDK `HttpServer`. |
| `SessionOrchestratorTest` (9)           | Full WS handshake via in-JVM fake WS.                  |
| `ReconnectSchedulerTest` (8)            | Backoff + jitter + cancellation.                       |
| `TokenRefreshSchedulerTest` (4)         | Schedule / cancel / replace.                           |
| `MqttReconnectSchedulerTest` (4)        | Single-flight retry.                                   |
| `StatesPublisherTest` (BATCH+SINGLE, 9) | Events → publish, mode dispatch.                       |
| `CommandSubscriberTest` (4)             | MQTT message → CDI event.                              |
| `CommandResponsePublisherTest` (2)      | CDI event → MQTT publish.                              |
| `AppInfoPublisherTest` (3)              | `MiniserverConnectedEvent` → JSON retained.            |
| `LoxApp3PublisherTest` (3)              | Cache → MQTT retained verbatim.                        |
| `ApplicationSmokeTest` (7)              | Boot + management endpoints.                           |

---

## 3. Integration tier — `@QuarkusIntegrationTest`

### Location

Same packages, but the **class name ends with `IT`** (not
`Test`) — the Failsafe convention. Examples:

- `src/test/java/.../boot/ApplicationSmokeIT.java`
- `src/test/java/.../transport/LiveBrokerIT.java`
- `src/test/java/.../miniserver/bootstrap/FakeMiniserverBootstrapIT.java`
- `src/test/java/.../miniserver/session/FakeMiniserverHandshakeIT.java`
- `src/test/java/.../transport/CommandRoundTripIT.java`

### Activation

The Maven `integration` profile (declared in `pom.xml`) flips
`<skipITs>` to `false` AND sets `quarkus.profile=dev` at build time.

```bash
./mvnw verify -Pintegration                     # everything
./mvnw verify -Pintegration -Dit.test=NameOfIT  # a specific IT
```

### Anatomy of an IT

```java

@QuarkusIntegrationTest                                           // (1)
@QuarkusTestResource( value = MosquittoTestResource.class,
                      restrictToAnnotatedClass = true )           // (2)
@QuarkusTestResource( value = FakeMiniserverFullResource.class,
                      restrictToAnnotatedClass = true )           // (2')
class CommandRoundTripIT
{

    @Test
    void someScenario()
    {
        given().when()                                            // (3)
               .post( "/api/v1/bootstrap" )
               .then()
               .statusCode( 200 )
               .body( "status", equalTo( "success" ) );
    }
}
```

1. **`@QuarkusIntegrationTest`** magic annotation: Failsafe builds
   the fast-jar, launches it in a subprocess with
   `-Dquarkus.profile=dev`, waits until it's ready, then runs the
   `@Test`s. At the end, kills the subprocess.

2. **`@QuarkusTestResource`** (stackable) — starts an external
   service BEFORE the Quarkus subprocess boots, returns a `Map`
   of properties that are injected into the subprocess as
   `-D...`. See §4 for the 3 existing resources.
   `restrictToAnnotatedClass=true` = "restart the resource for
   each IT class" (we keep it isolated to not mix retained MQTT
   messages between tests).

3. **No `@Inject`** — no access to the subprocess CDI. Everything goes
   through HTTP via **RestAssured** (already a test dep).

### How to share state between the resource and the test

The `Map` returned by `start()` goes to the **subprocess only** —
not to the test JVM. So `System.getProperty("loxone.transport.connection.host")`
in the test returns `null`.

To pass values to the test (random port allocated by
Docker / OS), pattern **static getter** on the resource:

```java
// in the test resource
private static volatile String brokerHost;
private static volatile Integer brokerPort;

public static String brokerHost() { return brokerHost; }

public static int brokerPort() { return brokerPort; }

@Override
public Map< String, String > start()
{
    // ... start the container ...
    brokerHost = container.getHost();
    brokerPort = container.getMappedPort( 1883 );
    return Map.of(...);
}

@Override
public void stop()
{
    brokerHost = null;
    brokerPort = null;
}
```

In the test:

```java
String host = MosquittoTestResource.brokerHost();
int port = MosquittoTestResource.brokerPort();
```

Used by `MosquittoTestResource`, `FakeMiniserverHttpResource`,
`FakeMiniserverFullResource`.

---

### 3.5 Native parity — running ITs against the GraalVM binary

All `*IT.java` being annotated `@QuarkusIntegrationTest`, they
run indifferently against the JVM fast-jar **or** against the
native binary `target/*-runner`. The only thing that changes is the
active Maven profile:

```bash
# JVM (~3 s per IT, Java subprocess startup).
./mvnw verify -Pintegration

# Native (~10 min Mandrel build + ~1 s/IT, native subprocess startup).
./mvnw verify -Pnative,integration
```

#### Why it's necessary in addition to the JVM

GraalVM `native-image` strips at compilation everything it cannot
prove statically as used: record components,
constructors, accessors. A class or record not
listed in `NativeReflectionConfig` loses its Jackson metadata
in native, and **silently**:

- `ObjectMapper.readValue(json, Foo.class)` → `Cannot construct instance of Foo`
- `ObjectMapper.writeValueAsString(foo)` → `No serializer found for class Foo and no properties discovered to create BeanSerializer`

These bugs **only manifest in native**. The JVM exposes
records reflection by default → the `*Test.java` and
`*IT.java` run in JVM pass even when the code is broken in
native. Real case: where the `/states` page stayed 24h
in degraded mode in prod (Topology fallback empty) without any
JVM test flagging the problem.

#### The pattern to apply

For each DTO serialized manually (apart from typed REST returns that
Quarkus auto-registers):

1. **Code**: add the class to `NativeReflectionConfig.@RegisterForReflection.targets`.
2. **Test**: add an IT that exercises the endpoint that serializes the DTO,
   asserting on non-empty content post-serialization (not just
   200 OK — must detect the empty fallback).

Audit grep to re-run periodically:

```bash
grep -rn "writeValueAsString\|writeValueAsBytes\|writeValueAsByteArray\|readValue\|treeToValue" \
     src/main/java --include="*.java" | grep -v "_Test"
```

Each hit must have its entry in `NativeReflectionConfig` AND its
native IT. Reference: `LiveStatesPageIT.java` — drives
`/bootstrap` + `/connect` → `RUNNING`, polls `/states` for 5 s to
absorb the async `@ObservesAsync`, asserts that the rendered HTML contains
the names of rooms / cats / controls from the fixture (and **does not** have
the signature `{"rooms":[],"categories":[],"controls":[]}` of the type-107
fallback).

#### Operational cost

- Native build: ~5-10 min (Mandrel container, RAM > 6 GB recommended).
- Run IT against native: ~1 s per IT (subprocess starts instantly).
- To run **before each release** that touches code listed in
  `NativeReflectionConfig`, or adds a new manual serialization site.
  Not in CI on each commit (too slow) —
  command: `./mvnw verify -Pnative,integration`.

---

## 4. The test resources

Three resources, three roles. **One per external boundary**.

### 4.1 `MosquittoTestResource`

`src/test/java/.../testresources/MosquittoTestResource.java`

Starts **eclipse-mosquitto 2.0.18** in Docker via Testcontainers.
TCP port 1883 mapped to a random port. Allow-anonymous (creds
disabled on the binding side via override). Mosquitto logs piped into the
Maven console via `Slf4jLogConsumer` — indispensable for debugging.

Overrides pushed to the binding:

- `loxone.transport.connection.protocol=tcp` (the binding default is
  ws, we simplify to avoid having to configure a WS listener in
  Mosquitto)
- `loxone.transport.connection.secure=false`
- `loxone.transport.connection.host/port` ← mapped
- `loxone.transport.security.credentials.enable=false`

**Auto-detects the rootless Docker socket** via `XDG_RUNTIME_DIR/docker.sock`
if `/var/run/docker.sock` doesn't exist (Linux rootless). Tricky fix
(casing of system props, Docker API version, etc.).

Used by: `LiveBrokerIT`, `CommandRoundTripIT`.

### 4.2 `FakeMiniserverHttpResource`

`src/test/java/.../testresources/FakeMiniserverHttpResource.java`

`com.sun.net.httpserver.HttpServer` JDK on `127.0.0.1:0`. Serves
the two **bootstrap-only** endpoints:

- `GET /jdev/cfg/apiKey` → canned Gen2+SUPPORTED identity (snr
  `50:4F:94:AA:BB:CC`, version `17.0.3.31`).
- `GET /jdev/sys/getPublicKey` → fresh RSA 2048-bit wrapped in PEM
  CERTIFICATE.

**No WebSocket**. This is intentional — so that an IT can assert
"connect fails cleanly when there is no WS endpoint" (see
`FakeMiniserverBootstrapIT.connectFailsWithoutWsServer`).

Used by: `FakeMiniserverBootstrapIT`.

### 4.3 `FakeMiniserverFullResource`

`src/test/java/.../testresources/FakeMiniserverFullResource.java`

**Vert.x `HttpServer`** on `127.0.0.1:0` that serves on the SAME port:

- The 3 HTTP handshake endpoints: apiKey, getPublicKey,
  `GET /jdev/sys/getkey2/{user}` (canned salt + key + hashAlg).
- The WebSocket at `/ws/rfc6455`.

On the WS side, a **content-routed scripted dialog**:

| Substring in the incoming frame                     | Reply sent                                            |
|-----------------------------------------------------|-------------------------------------------------------|
| `jdev/sys/keyexchange`                              | `{LL:{value:"ack",Code:"200"}}`                       |
| `jdev/sys/enc/` (1st = handshake)                   | getjwt reply with token/key/validUntil                |
| `jdev/sys/enc/` (subsequent = post-RUNNING command) | command-response reply with `COMMAND_RESPONSE_MARKER` |
| `LoxAPPversion3`                                    | version timestamp reply                               |
| `data/LoxAPP3.json`                                 | minimal LoxAPP3 fixture                               |
| `enablebinstatusupdate`                             | `{LL:{value:"1",Code:"200"}}`                         |

**No crypto on the server side**. The binding encrypts its outbound
commands but the miniserver replies in clear (verified against the real
miniserver). The fake doesn't decrypt anything, it just routes on the
URL prefix.

The 1st-vs-subsequent `jdev/sys/enc/` tracking is done via a
`AtomicBoolean getjwtSeen` **per WS connection** — this is what enables
`CommandRoundTripIT` to use the same resource for the handshake
AND the post-RUNNING commands.

Used by: `FakeMiniserverHandshakeIT`, `CommandRoundTripIT`.

### Why 3 resources and not 1

- `MosquittoTestResource` has nothing to do with the miniserver.
  No reason to couple them.
- `FakeMiniserverHttpResource` vs `FakeMiniserverFullResource`:
  the bootstrap IT specifically wants **absence** of WS to
  assert the 502. If we only had the full resource, we would lose
  this scenario.

Stacking several `@QuarkusTestResource` on a single IT class
works without a hitch (tested on `CommandRoundTripIT`).

---

## 5. The CDI + SmallRye rule

This is the universal invariant to remember, surfaced after 5 debug
patches (LoxoneConfigHolder rounds 1-4):

> **Any bean that combines**:
>
> - `@Observes` **or** `@ObservesAsync` **on a method**, **AND**
> - injection of a `@ConfigMapping` (our `LoxoneConfig`) **or** of a
    > `@ConfigProperty`,
>
> **must be lazy-protected against creation from a non-main thread.**

Concretely, two interchangeable recipes:

### Recipe A — indirection via `LoxoneConfigHolder` (preferred)

```java

@Singleton                                            // not @ApplicationScoped
public class MyObserver
{
    @Inject
    LoxoneConfigHolder configHolder;          // NOT LoxoneConfig directly
    @Inject
    MqttClient         mqtt;

    private LoxoneConfig config() { return configHolder.get(); }

    public void onSomething( @Observes SomeEvent e )
    {
        var spec = config().transport().topics()....; // OK
    }
}
```

`LoxoneConfigHolder` is annotated `@Startup` → instantiated on the main
thread at boot → its `LoxoneConfig` field is resolved once,
cleanly, and then all other threads can read `holder.get()`.

Applied in: `StatesPublisher`, `OutOfServiceMqttReconnector`,
`AppInfoPublisher`, `LoxApp3Publisher`, `CommandResponsePublisher`.

### Recipe B — `@Startup` direct on the bean

```java

@Singleton
@Startup                                              // force main-thread creation
public class MyObserver
{
    @Inject
    MqttClient mqtt;

    @ConfigProperty( name = "quarkus.application.version",
                     defaultValue = "unknown" )
    String bindingVersion;

    public void onSomething( @ObservesAsync SomeEvent e )
    {
        // bindingVersion is resolved via @Startup at boot.
    }
}
```

Applied in: `AppInfoPublisher` (also has the holder, belt +
suspenders).

### Why it's a problem

ArC lazy-creates `@ApplicationScoped` / `@Singleton` beans on
first access. If this first access comes from a thread (async
executor, HiveMQ worker, WS reader) where the `SmallRyeConfigProviderResolver`
isn't bound to the TCCL, the injection of `@ConfigMapping` /
`@ConfigProperty` crashes with one of these errors:

- `SRCFG00015: No configuration is available for this class loader`
- `Error injecting <type> <bean>.<field>`

Worse: the exception is swallowed by `DefaultAsyncObserverExceptionHandler`
which logs at DEBUG, so in prod you see nothing — just a message
that is never published. `IT CommandRoundTripIT` is exactly this
kind of regression catcher.

### Symptoms to recognize this bug

- A CDI publisher has its observer correctly registered (visible
  in `/q/arc/observers`) but **nothing comes out on MQTT** after an
  event that should trigger publication.
- If you enable `quarkus.log.category."io.quarkus.arc.impl.DefaultAsyncObserverExceptionHandler".level=DEBUG`,
  you see the stack trace `Error injecting LoxoneConfig …`.
- The corresponding in-JVM test passes (the JVM test has a properly
  initialized main thread), but the IT fails.

---

## 6. Command cheat-sheet

```bash
# ─── Unit tests ────────────────────────────────────────────────────────

# Everything
./mvnw test

# Offline (recommended if you've already compiled once)
./mvnw test -o

# A single class
./mvnw test -Dtest=AppInfoPublisherTest

# A single method
./mvnw test -Dtest='AppInfoPublisherTest#firesAppInfoOnConnected'

# Several classes (Maven regex)
./mvnw test -Dtest='AppInfo*,LoxApp3*'

# Verbose mode (useful to see application json logs)
./mvnw test -Dtest=AppInfoPublisherTest -X | grep loggerName

# ─── Integration tests (Failsafe) ──────────────────────────────────────

# Everything (unit + IT)
./mvnw verify -Pintegration

# Offline
./mvnw verify -Pintegration -o

# A single IT
./mvnw verify -Pintegration -o -Dit.test=LiveBrokerIT

# A single IT, specific method
./mvnw verify -Pintegration -o -Dit.test='CommandRoundTripIT#commandRoundTrip'

# Skip unit, only run ITs (time saving in re-iteration)
./mvnw verify -Pintegration -o -DskipTests=true

# ─── Docker prerequisites for ITs ──────────────────────────────────────

# Check that Docker is reachable (rootless or rootful)
docker ps

# If rootless and Testcontainers doesn't find the socket:
#   The code in MosquittoTestResource.detectRootlessDockerSocket()
#   handles this automatically via XDG_RUNTIME_DIR. If it crashes,
#   export explicitly:
export DOCKER_HOST=unix:///run/user/$(id -u)/docker.sock

# ─── Debug subprocess IT logs ──────────────────────────────────────────

# The Quarkus subprocess logs during an IT are written to:
target/quarkus.log
#   ⇒ tail -f in parallel while mvn verify is running
```

---

## 7. File map

```
src/test/java/com/quaddan/iot/loxmq/
├── boot/
│   ├── ApplicationSmokeTest.java          # @QuarkusTest — in-JVM boot
│   └── ApplicationSmokeIT.java            # @QuarkusIntegrationTest — packaged boot
├── config/
│   └── LoxoneConfigTest.java              # @QuarkusTest — tree validation
├── miniserver/
│   ├── bootstrap/
│   │   ├── BootstrapOrchestratorTest.java # @QuarkusTest — embedded HttpServer
│   │   └── FakeMiniserverBootstrapIT.java # @QuarkusIntegrationTest — packaged + HttpResource
│   ├── crypto/
│   │   └── LoxoneCryptoServiceTest.java   # @QuarkusTest — RSA/AES/HMAC
│   ├── http/
│   │   └── MiniserverHttpClientTest.java  # @QuarkusTest — embedded HttpServer
│   ├── session/
│   │   ├── LoxApp3CacheTest.java          # @QuarkusTest
│   │   ├── ReconnectSchedulerTest.java    # @QuarkusTest
│   │   ├── SessionOrchestratorTest.java   # @QuarkusTest — fake WS via QuarkusMock
│   │   ├── TokenRefreshSchedulerTest.java # @QuarkusTest
│   │   └── FakeMiniserverHandshakeIT.java # @QuarkusIntegrationTest — packaged + FullResource
│   ├── message/
│   │   └── BinaryStatesDecoderTest.java   # @QuarkusTest — vector decoding
│   └── ...
├── transport/
│   ├── FakeMqttClient.java                # FAKE (not a test) — used via QuarkusMock
│   ├── AppInfoPublisherTest.java          # @QuarkusTest + FakeMqttClient
│   ├── LoxApp3PublisherTest.java          # @QuarkusTest + FakeMqttClient
│   ├── StatesPublisherSingleModeTest.java # @QuarkusTest + FakeMqttClient
│   ├── StatesPublisherBatchModeTest.java  # @QuarkusTest + FakeMqttClient
│   ├── CommandSubscriberTest.java         # @QuarkusTest
│   ├── CommandResponsePublisherTest.java  # @QuarkusTest + FakeMqttClient
│   ├── MqttReconnectSchedulerTest.java    # @QuarkusTest
│   ├── OutOfServiceMqttReconnector test   # @QuarkusTest
│   ├── LiveBrokerIT.java                  # @QuarkusIntegrationTest — Mosquitto live
│   └── CommandRoundTripIT.java            # @QuarkusIntegrationTest — Mosquitto + Full
└── testresources/
    ├── MosquittoTestResource.java         # Testcontainers Mosquitto
    ├── FakeMiniserverHttpResource.java    # JDK HttpServer (bootstrap only)
    └── FakeMiniserverFullResource.java    # Vert.x HTTP + WS (full handshake)
```

---

## 8. Deciding where to place a new test

Decision tree for "I want to test X":

```
                  Does the test mainly exercise Java code?
                                   │
                  ┌────────────────┴────────────────┐
                  │ YES                             │ NO (config / endpoint shape)
                  ▼                                 ▼
        Pure logic (parsing,           Does the REST endpoint return the right shape?
        crypto, decoding, calculations)?  RestAssured is enough → @QuarkusTest
                  │
       ┌──────────┴──────────┐
       │ YES                 │ NO (multi-bean interaction)
       ▼                     ▼
   Pure JUnit test       @QuarkusTest + @Inject
   (no CDI required)     + QuarkusMock if needed

       Does the test exercise the app against a REAL external service?
                                   │
                  ┌────────────────┴────────────────┐
                  │ NO (everything is in-process)   │ YES (Docker / real broker / fake server)
                  ▼                                 ▼
              @QuarkusTest                @QuarkusIntegrationTest
              (recipes above)             + @QuarkusTestResource(...)
                                          + RestAssured only

       Does the test cover the packaged JAR behavior (vs dev classpath)?
                                   │
                                   ▼ YES → @QuarkusIntegrationTest is mandatory
```

Practical rules:

- **Default: `@QuarkusTest`.** Anything that can be written in-JVM
  is written in-JVM. 10× faster, instrumentable.
- **Switch to IT only when the bug can ONLY be seen
  against the packaged JAR.** Examples: the ArC+SmallRye saga §5 (the
  in-JVM test passed, the IT caught the bug), validation that the
  Qute templates are packaged.
- **One IT per scenario, not per method**. The Quarkus subprocess takes
  ~3 s to boot. 6 methods in one IT = 1 boot. 6 separate ITs = 6 boots.

---

## 9. Anti-patterns

### ❌ Do NOT do

**1. `@Inject` in an IT.**
The test JVM has no CDI container. You only have access to beans via
HTTP. If you need to manipulate a bean directly, it's probably
a `@QuarkusTest`.

**2. `System.getProperty(...)` in an IT to read an override
pushed by `@QuarkusTestResource`.**
The `Map` returned by `start()` goes to the subprocess, not to the test JVM.
Use a static getter on the resource (see §3).

**3. `@ApplicationScoped` on a bean that combines `@Observes` +
`@Inject LoxoneConfig`.**
Will silently break if the event is fired from a non-main
thread. See §5 — use `@Singleton + LoxoneConfigHolder`.

**4. `Mqtt5BlockingClient.publishes(filter).receive(timeout)` called
AFTER the trigger.**
HiveMQ drops messages that arrived before the buffer opened.
Correct pattern: `try ( var p = observer.publishes(...) ) { trigger; p.receive(); }`.
Documented in `LiveBrokerIT`.

**5. Counter-scripted dialogs on the fake miniserver.**
The LoxAPP3 cache from the previous run can HIT and skip a
handshake step — the counter drifts. Route by
`text.contains("distinctive-substring")` instead.

**6. Hardcode a port in a test.**
OS-assigned random port: `new InetSocketAddress("127.0.0.1", 0)`
then `server.getAddress().getPort()`. Avoids collisions in CI or
when you run two suites in parallel.

**7. Re-create FakeMqttClient by hand in each test.**
The file already exists under
`src/test/java/.../transport/FakeMqttClient.java`. Reuse it.

### ✅ Do

**1. Add the test to the lowest tier that covers it.** If in-JVM
is enough, stay in-JVM.

**2. Give a meaningful `@DisplayName`.** Failsafe and the maven
console use them — that's what we read when a test fails in CI.

**3. Document the "why" in the class javadoc when the
setup is non-trivial.** See `CommandRoundTripIT.java` for
reference.

**4. If you add a new test resource**, do it in the
`testresources/` package, expose a static getter for the
runtime values (host/port), and add an entry in §4 of this
document.

---

## 10. What is not tested

Known items not covered at the moment, by choice or because not
yet implemented:

- **TLS WSS Miniserver and TLS MQTT broker in end-to-end IT**.
  The TLS code is active by default in the staging/prod profiles
  (`miniserver.connection.secure=true`, MQTT on `wss://…:8084`),
  but the test resources run in plain:
  `MosquittoTestResource` exposes port 1883 without a cert, and
  `FakeMiniserverFullResource` accepts `ws://` without TLS handshake.
  To enable them in IT, we would need to mount a self-signed cert,
  inject it into the subprocess Quarkus keystore, and reconfigure
  both fixtures. Not urgent — TLS plumbing is validated in
  continuous staging.

- **Miniserver reconnect post-OUT_OF_SERVICE in real conditions**.
  Covered in unit (`MiniserverReconnectScheduler` + mocks) but not
  in live IT: `FakeMiniserverFullResource` doesn't know how to simulate a
  TCP refusal followed by a return 30 s later (Miniserver reboot
  OOS sequence). Field observation deferred — verify the reconnect
  attempt sequence (#1 → #2 → #3 …) at the next staging/prod OOS.

- **Sparklines / SSE real-time on `/states`**. The REST endpoints
  are tested (`LiveStatesPageIT`), but the SSE stream that feeds
  the sparklines has no IT — it is validated manually via the
  live dashboard.

What is **well** covered and should not be confused with "not
tested":

| Aspect                                                       | IT coverage                                                                           |
|--------------------------------------------------------------|---------------------------------------------------------------------------------------|
| End-to-end binary state events (binary frame → MQTT publish) | `StateEventsRoundTripIT` (fake pushes binary frames post-ack `enablebinstatusupdate`) |
| Crypto round-trip (RSA + AES + jdev/sys/enc)                 | `CryptoRoundTripIT` (fake RSA-unwrap session key + AES-decrypt)                       |
| Broker reconnect after crash (SIGKILL Mosquitto)             | `BrokerCrashRecoveryIT` via `MosquittoRestartableTestResource`                        |
| Inbound MQTT commands → Miniserver → response                | `CommandRoundTripIT`                                                                  |
| Boot chain (MQTT → bootstrap → RUNNING session)              | `BootProfileSmokeIT` + `ApplicationSmokeIT`                                           |
| Admin happy path (users + schedules CRUD)                    | `AdminHappyPathIT`                                                                    |
| Native-image regression                                      | `./mvnw verify -Pnative,integration`                                                  |

---

*Align this file when a new test tier arrives.*
