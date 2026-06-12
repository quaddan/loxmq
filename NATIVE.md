<img src="src/main/resources/META-INF/resources/images/loxmq-logo.svg" alt="loxmq">

# NATIVE

Practical guide for the native-image target (GraalVM Mandrel). The
native binary **is** the reference artifact for prod deployment:
~50 ms startup, ~50–80 MB RSS, standalone (no JVM required). The
fast-jar remains usable for iterating in dev (`mvn quarkus:dev`).

For the historical "why" of each blocker encountered + resolved,
see the project's git history.

---

## TL;DR — build & test

```bash
# Build native via Mandrel container (~3 min warm cache, ~10 min cold).
./mvnw package -Pnative -Dquarkus.profile=dev \
            -Dquarkus.native.container-build=true -DskipTests

# The binary is in target/loxmq-1.0.0-runner (~88 MB)
# Launch directly (no JVM needed):
./target/loxmq-1.0.0-runner

# With env override (e.g. disable auto-start for smoke test):
./target/loxmq-1.0.0-runner -Dloxone.boot.auto-start=false
```

Notes:

- `-Dquarkus.profile=dev` is required locally — the
  `staging`/`prod` profiles pin Let's Encrypt certs to
  `/opt/loxmq/certs/…` (Option B layout, renamed),
  which only exist on real deployment machines (the centralized
  certbot LXC pushes certs there via rsync).
- `-Dquarkus.native.container-build=true` delegates to a Mandrel
  container (image `quay.io/quarkus/ubi9-quarkus-mandrel-builder-image`),
  no need to install GraalVM locally. Rootless Docker OK.
- `-DskipTests` speeds up the build (JVM tests have already run via
  `mvn test` separately; native tests are prohibitive timewise).

---

## The 5 pitfalls we've already hit

Any code modification must be validated against these 5 categories.
The golden rule: **the native binary is the goal, the fast-jar is
just a shortcut for iterating**.

### 1 · 🟥 Jackson — serialization / deserialization

GraalVM strips reflection metadata (constructors, accessors) for
any class it cannot statically prove is reflectively used. Jackson
uses it on all DTOs.

**Deserialization sites to scan**:

```
ObjectMapper.readValue(...)      // src/main/java — grep "readValue.*\.class"
ObjectMapper.treeToValue(...)    // SessionOrchestrator (TokenValue)
ObjectMapper.convertValue(...)   // n/a currently
```

**Serialization sites**:

```
ObjectMapper.writeValueAsBytes(...)   // StatesPublisher (BATCH path), AppInfo, OOS
ObjectMapper.writeValueAsString(...)  // n/a currently
```

**Rule**: any new class (record or bean) read or written by
these calls must be added to
`src/main/java/.../util/NativeReflectionConfig.java` in the `targets`
of `@RegisterForReflection`. Nested inner records must be
listed separately (`Foo.Bar.class`).

The `NativeReflectionConfig` file is the **single source of truth** —
even though it's verbose, that's intentional: a grep "Foo.class" in
this file instantly answers "is Foo registered?".

Runtime symptom when a DTO is missing:

```
Cannot construct instance of `…Foo`: cannot deserialize from Object value
(no delegate- or property-based Creator): this appears to be a native
image, in which case you may need to configure reflection for the class
```

or on the write side:

```
No serializer found for class `…Bar` and no properties discovered to
create BeanSerializer
```

Reference patches: **045** (bootstrap chain + TokenValue),
**046** (DecodedMessages outbound).

### 2 · 🟥 Qute — dashboard templates

Qute in native uses **ValueResolvers generated at build-time**
rather than runtime reflection. They only cover **declared methods**
on the target class — not inherited methods.

**Concrete consequence**: `enum.name()` does NOT work in native
(`name()` is declared on `java.lang.Enum`). Same for
`object.toString()` and anything inherited.

**Rule**: in Qute templates, do not call any inherited method.
Pre-compute in Java before passing to the template:

```java
// DashboardResource.dashboard() — pre-compute
return Templates.dashboard(…,
                           modeResolver.effective( ).

name(),       // instead of passing the enum
    sessionTracker.

state().

name(),
    identity.

map( id ->id.

httpsStatus().

name()),
        …
        );
```

And in the template, compare Strings, do not call `.name()`:

```html
{#if effectiveMode == 'SECURE'} …       <!-- instead of effectiveMode.name() == 'SECURE' -->
{#switch bootstrapStatus} …             <!-- instead of bootstrap.status.name() -->
```

For simple records / beans accessed by a declared accessor
(`record.field()`), `@TemplateData(target = Foo.class)` in
`NativeReflectionConfig` is enough — Qute generates the ValueResolver
at build time.

`@CheckedTemplate` validates the signature at compile time. Once the
template is aligned on the String signature, the build breaks if someone
puts `.name()` back.

Runtime symptom:

```
Property "name" not found on the base object "…ConnectionMode"
in expression {effectiveMode.name()}
```

Reference patches: **047** (4 enums pre-computed).

### 3 · 🟥 CDI — STATIC_INIT vs RUNTIME_INIT

GraalVM splits init into two phases: **STATIC_INIT** (constants
embedded in the image) and **RUNTIME_INIT** (executed at binary
boot). Some synthetic beans (`io.vertx.core.Vertx`) are
mandatory RUNTIME_INIT. If a STATIC_INIT bean tries to access
Vertx via `@Inject` at create-time, the binary fails at boot.

**Rule**: any new bean that:

- injects `Vertx` (directly, or through an `ExecutionHolder` /
  reactive-messaging connector chain), OR
- injects another bean that chains to Vertx

must be checked so it doesn't get instantiated in STATIC_INIT.
If a third-party bean does so unintentionally (case of the
`quarkus-hivemq-client` extension with its `HiveMQMqttConnector`), exclude it:

```properties
quarkus.arc.exclude-types = io.quarkiverse.hivemqclient.smallrye.reactive.HiveMQMqttConnector
```

**Sub-rule for CDI cycles**: if beans `A @Inject B` and
`B @Inject A`, use `Instance<>` on the lazy side to break the cycle
at create-time:

```java

@Inject
Instance< A > aInstance;
// Later: aInstance.get().doSomething();
```

Runtime symptom:

```
Synthetic bean instance for io.vertx.core.Vertx not initialized yet:
a synthetic bean initialized during RUNTIME_INIT must not be accessed
during STATIC_INIT
```

Reference patches: **044** (exclude `HiveMQMqttConnector`), **042**
(`Instance<MqttMetrics>` in `KeepAliveScheduler`).

### 4 · 🟥 Reflection on synthetic fields

GraalVM does not, by default, keep reflection metadata on the
synthetic fields generated by javac (`val$delegate`, `this$0`, …).
Application code that walks these fields via reflection does NOT
work in native.

**Known case**: `LoggingProducers.walk()` descends into `val$delegate`
to reach the real `ConsoleHandler` hidden by the anonymous wrappers of
`LoggingSetupRecorder` (and reflects `FileHandler.getFile()` to locate
`warn.log` for the strict-WARN filter). The stable handler classes are
registered in `LoggingReflectionConfig` (`methods`/`fields` hints), so
the `getHandlers()` / `getFile()` reflection works in native; only the
*synthetic-field* descent into the dev-mode anonymous wrapper is
JVM-only — and native never needs it, because Quarkus's build-time
logging recorder yields a flatter handler tree where `getHandlers()`
reaches the `ConsoleHandler` directly.

**Rule**: avoid writing code that walks synthetic fields by
reflection. When unavoidable, accept that this code will only work
in JVM (and fail-soft: the native falls back, doesn't crash).

If we really need this reflection in native, we need a
`reflect-config.json` targeting the exact field name — but this name
depends on javac/JDK and can change between versions.

### 5 · 🟥 JCTools / Unsafe — GraalVM substitutions

Lock-free libs (JCTools, indirectly via Netty / HiveMQ /
Disruptor / Caffeine) call `sun.misc.Unsafe.arrayIndexScale(Class)`
in their static-init and store the result in a constant. GraalVM's
points-to analysis can't follow some storage patterns →
embeds the value from the build host → memory corruption at
runtime → **segfault under load**.

**Rule**: if a new Maven dependency uses lock-free queues /
ring buffers / array-base offsets, check after native build
whether the following warning appears:

```
Warning: RecomputeFieldValue.ArrayIndexScale automatic field value
transformation failed. The automatic registration was attempted because
a call to jdk.internal.misc.Unsafe.arrayIndexScale(Class) was detected
in the class initializer of …
```

If so → add a substitution in
`src/main/java/.../util/graal/Target_*.java` modeled on
`Target_JCTools`. Recipe:

```java

@TargetClass( className = "fully.qualified.OffendingClass" )
final class Target_offending_class
{
    @Alias
    @RecomputeFieldValue( kind = Kind.ArrayIndexShift, declClass = Object[].class )
    public static int OFFENDING_SHIFT_FIELD;
}
```

The `org.graalvm.nativeimage:svm:25.0.3` (provided scope) provides
the `@TargetClass` / `@Alias` / `@RecomputeFieldValue` annotations.

Runtime symptom — segfault after a few hundred allocations
under the affected lib:

```
Segfault detected, aborting process. Use '-XX:-InstallSegfaultHandler'
to disable the segfault handler at run time and create a core dump
instead.
```

Reference patches: **046** (JCTools standalone not covered by
`quarkus-netty`).

### 6 · 🟥 Runtime-only value captures at class-init

GraalVM initializes classes at **build-time** by default. Any
`static final` constant initialized with a value that depends on the
runtime environment (timezone, hostname, env vars, current time, locale, …)
captures the value of the **builder container**, not that of the
deployment host.

**Known case**: `DashboardTemplateExtensions.LOCAL` initialized with
`DateTimeFormatter.ofPattern(...).withZone(ZoneId.systemDefault())`
baked the UTC zone of the Mandrel container in hard. Silent regression:
the JVM dashboard displayed the local time, the native binary displayed
UTC. Symptom: timestamps offset by N hours (= zone offset of the
deploy host vs UTC).

**Rule**: for any value that MUST be runtime, either:

- compute it in the method instead of caching it (the recompute
  cost is almost always negligible — `ZoneId.systemDefault()`
  uses an internal JDK cache),
- or defer the entire class to runtime via
  `--initialize-at-run-time=fully.qualified.ClassName` in
  `quarkus.native.additional-build-args`.

Common symptom: no crash, just an incorrect value (different
from what the JVM mode would have shown). To look for if a
"JVM dev vs native prod" comparison reveals a mismatch on values derived
from the environment.

Reference patch: **050** (timezone snapshot in
`DashboardTemplateExtensions`).

---

## Workflow for non-trivial patches

For any patch that touches Jackson, Qute, CDI scoping, new
Maven dependencies, or reflection:

```bash
# 1. Implement + standard JVM tests
./mvnw test -o
# ↓ must pass (~40 s)

# 2. Confirm the native builds
./mvnw package -Pnative -Dquarkus.profile=dev \
            -Dquarkus.native.container-build=true -DskipTests
# ↓ must succeed (~3 min warm cache)

# 3. Launch the binary, check it reaches RUNNING
./target/loxmq-*-runner &
sleep 8
curl -sS -o /dev/null -w "GET / → HTTP %{http_code}\n" http://localhost:8080/
curl -sS http://localhost:8080/api/v1/state | jq '.session.state, .broker.connected'
pkill -f 'loxmq-.*-runner'

# 4. If steps 2-3 OK → commit + push + tag
```

"Safe" patches (docs, comments, purely-JVM refactors,
test additions) can skip steps 2-3. **When in doubt:
rebuild native.**

---

## Critical files for the native

| File                                                               | Role                                                         |
|--------------------------------------------------------------------|--------------------------------------------------------------|
| `pom.xml` § profile `native`                                       | Enables `quarkus.native.enabled=true`                        |
| `pom.xml` § dep `quarkus-hivemq-client:2.5.0`                      | Quarkiverse extension that ships HiveMQ/Netty native hints   |
| `pom.xml` § dep `org.graalvm.nativeimage:svm:25.0.3` (provided)    | `@TargetClass`, `@Alias`, `@RecomputeFieldValue` annotations |
| `src/main/resources/application.yaml` § `quarkus.arc.exclude-types` | CDI beans to skip (HiveMQMqttConnector)                      |
| `src/main/java/.../util/NativeReflectionConfig.java`               | Central list of classes registered for reflection            |
| `src/main/java/.../util/graal/Target_JCTools.java`                 | GraalVM substitutions for JCTools standalone                 |

---

## Observable differences JVM vs native

| Metric                             | JVM (fast-jar) | Native                                                           |
|------------------------------------|----------------|------------------------------------------------------------------|
| Cold start                         | ~2 s           | ~50 ms (×40)                                                     |
| Full boot to RUNNING               | ~2.4 s         | ~350 ms (×7)                                                     |
| RSS memory at rest                 | ~250 MB        | ~50–80 MB (×4)                                                   |
| Binary                             | jar + ~80 deps | 88 MB standalone                                                 |
| Build time                         | ~5 s           | ~3 min (warm cache)                                              |
| Tests under load (1000+ publishes) | ✅              | ✅ (+)                                                            |
| `LoxmqConsoleFormatter` colors     | ✅              | ✅ (via `@RegisterForReflection` on the JBoss LogManager classes) |
| Dashboard / REST endpoints         | ✅              | ✅                                                                |

---

*Doc to update when a different native pitfall appears or when
a deferred item is resolved.*
