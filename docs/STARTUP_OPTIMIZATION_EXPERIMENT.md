# Startup Optimization Experiment — Results & Analysis

**Application:** demo-virtual-thread  
**Environment:** Docker Compose on Linux (single host)  
**Method:** `docker compose up -d --force-recreate app`, startup time from:
```
Started DemoApplication in X seconds (process running for Y)
```

---

## Stack Versions

Two stacks were used across experiments. Results are **not cross-comparable** between stacks.

| Stack | Spring Boot | Java | Hibernate | Kafka |
|---|---|---|---|---|
| A (Phase 1) | 4.0.3 | 25.0.2 | 7.2.4 | `spring-boot-starter-kafka` |
| B (Phase 2) | 3.4.3 | 21.0.2 | 6.6.8 | `spring-kafka` |

---

## Phase 1 — Stack A (Spring Boot 4 / Java 25)

### All Measured Runs

| Run | Infrastructure | Config / Image | Startup time | WebAppContext init |
|---|---|---|---|---|
| 1 | Cold (first boot) | No optimizations | **86.9s** | 35,554ms |
| 2 | Warm | `open-in-view: false` + `lazy-init: true` | **22.3s** | 7,294ms |
| 3 | Warm | No optimizations (reverted) | **27.6s** | 7,582ms |
| 4 | Warm | `open-in-view: false` + `lazy-init: true` | **22.9s** | 9,239ms |
| 5 | Warm | AOT Cache image | **9.7s** | 4,689ms |

### Experiment A — Config Properties (Runs 3 vs 4)

| | Without | With | Saving |
|---|---|---|---|
| Startup time | 27.6s | 22.9s | **−4.7s (−17%)** |

**`spring.jpa.open-in-view: false`** — removes `OpenEntityManagerInViewInterceptor` from context. No functional impact on REST APIs.

**`spring.main.lazy-initialization: true`** — defers Hibernate, HikariCP, Kafka, Redis, and AOP proxy initialization until first use.

Trade-offs per [Spring Boot docs](https://docs.spring.io/spring-boot/reference/using/spring-beans-and-dependency-injection.html):

| Concern | Detail |
|---|---|
| First-request latency | First call triggers bean init — adds 1–5s |
| Startup error masking | Misconfigured beans fail on first use, not at startup |
| Readiness probe gap | App reports "Started" before connections are verified |
| **Native image incompatibility** | **Incompatible with GraalVM AOT — Spring cannot discover beans at build time** |

### Experiment B — AOT Cache (Runs 4 vs 5)

| | Without | With | Saving |
|---|---|---|---|
| Startup time | 22.9s | **9.7s** | **−13.2s (−57%)** |
| WebAppContext init | 9,239ms | **4,689ms** | **−49%** |

AOT Cache ([JEP 483](https://openjdk.org/jeps/483), Java 24+) stores pre-processed class metadata, loaded/linked classes, and AOT-compiled methods in a `.aot` file baked into the image. The JVM skips re-parsing this work on subsequent starts.

The training run generated a **116 MB** `app.aot` file baked into the image.

> **Note:** AOT Cache requires Java 24+. It is not available on Java 21 (Stack B).

Trade-offs:

| Concern | Detail |
|---|---|
| Image size | +116 MB — `app.aot` baked into image |
| JVM-version-specific | Rebuild required on JDK upgrade |
| Classpath coupling | Any dependency change requires regenerating the cache |

### Cold vs Warm Infrastructure (Runs 1 vs 3)

Run 1 (86.9s) vs Run 3 (27.6s) — same code, no config changes. The ~59s difference was entirely infrastructure:

- **Cold:** Kafka KRaft coordinator still electing → `NOT_COORDINATOR` retries blocked startup ~50s
- **Warm:** Kafka ready, OS page cache hot, JAR layers in memory

**Production implication:** Rolling restarts hit warm infra. Full cluster restarts (outage recovery) hit cold — size readiness probe timeouts accordingly.

---

## Phase 2 — Stack B (Spring Boot 3.4.3 / Java 21)

### All Measured Runs

All runs on warm infrastructure.

| Run | Image | Startup time |
|---|---|---|
| 1 | JVM (`eclipse-temurin:21-jre-alpine`) | 20.7s |
| 2 | JVM | 19.7s |
| 3 | JVM | 28.2s |
| 4 | JVM | 16.9s |
| 5 | Native (GraalVM 21) | 3.3s |
| 6 | Native | 2.1s |
| 7 | Native | 1.8s |
| 8 | Native | 6.5s |

### Experiment C — GraalVM Native Image

| | JVM (avg) | Native (avg) | Saving |
|---|---|---|---|
| Startup time | ~21.4s | **~3.4s** | **~84%** |
| Range | 16.9s – 28.2s | 1.8s – 6.5s | — |

#### What GraalVM Native Image does

GraalVM compiles the entire application — code + Spring + all dependencies — into a single platform-specific binary at build time. No JVM is included in the runtime image.

Spring Boot 3's `processAot` task runs before `nativeCompile` and automatically generates all required reflection hints, proxy configurations, and resource metadata for Hibernate, Redis, Kafka, and other libraries.

#### Stack

- Spring Boot 3.4.3 + Spring Framework 6.2.3 (stable native support since Spring Boot 3.0)
- GraalVM Community 21 (`ghcr.io/graalvm/native-image-community:21`)
- Hibernate 6.6.8 — bytecode provider handled automatically by Spring AOT
- `org.graalvm.buildtools.native` plugin 0.10.4

#### Dockerfile (`Dockerfile.native-graalvm`)

Single build stage using `./gradlew nativeCompile`, runtime on `debian:12-slim` (~120 MB, no JVM).

#### Critical requirement

`spring.main.lazy-initialization` must be `false` (the default). With lazy init enabled, Spring AOT cannot discover beans at build time → missing reflection hints → runtime failures.

#### Why startup time varies (1.8s – 6.5s)

The native binary itself starts in milliseconds. The observed variance comes from infrastructure:
- Kafka consumer group rebalance timing (non-deterministic)
- HikariCP initial connection pool establishment
- Redis `LettuceConnectionFactory` pool warmup

The native image worst case (6.5s) is still faster than the JVM best case (16.9s).

#### Trade-offs

| Concern | Detail |
|---|---|
| Build time | ~10 min (native) vs ~1 min (JVM) |
| Platform-specific binary | Must rebuild for each target OS/arch |
| Peak throughput | Lower than warmed-up JVM — no JIT re-optimization at runtime |
| Debugging | No standard JVM tooling; limited profiling support |
| Dynamic features | Reflection/proxies need AOT hints — Spring Boot 3 handles most automatically |

---

## Full Summary

### Phase 1 (Spring Boot 4 / Java 25, warm infra baseline = 27.6s)

| Optimization | Startup time | vs baseline |
|---|---|---|
| No optimizations | 27.6s | — |
| `open-in-view: false` + `lazy-init: true` | 22.9s | −17% |
| AOT Cache (Java 24+) | 9.7s | −65% |

### Phase 2 (Spring Boot 3.4.3 / Java 21, warm infra)

| Image | Avg startup | vs JVM baseline |
|---|---|---|
| JVM (no optimizations) | ~21.4s | — |
| **GraalVM Native Image** | **~3.4s** | **~84%** |

---

## Lessons Learned

1. **Infrastructure state dominates cold-start time** — Kafka KRaft election alone added ~50s in Run 1. Warm infra is the realistic production baseline for rolling restarts.

2. **`lazy-initialization: true` is incompatible with GraalVM native** — Spring AOT needs eager bean discovery at build time. Enabling lazy init causes missing reflection hints and runtime failures.

3. **Spring Boot 3 + GraalVM 21 is production-ready for native images** — No manual reflection config needed. `processAot` handles Hibernate, Kafka, Redis, and validation automatically.

4. **Spring Boot 4 + Hibernate 7 native image is not stable yet** — ByteBuddy bytecode provider and other Hibernate 7 internals lack complete GraalVM reachability metadata as of March 2026.

5. **AOT Cache (JEP 483) requires Java 24+** — Not available on Java 21 LTS. Provides similar startup improvement to native image while keeping the JVM.
