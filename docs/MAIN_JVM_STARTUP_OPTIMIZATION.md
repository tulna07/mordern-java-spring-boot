# JVM Startup Optimization — Spring Boot + Gradle on AWS EKS

> **Sources**: Spring Boot 4.x official docs, Spring Framework reference, spring.io engineering blog (Aug 2024), InfoQ (Mar 2025 — JEP 483 ships), callistaenterprise.se (Jul 2024 — CRaC walkthrough), makariev.com (Jun 2024 — CDS/Native benchmark), GraalVM official docs  
> **Build tool**: Gradle (all examples use Gradle)  
> **Last reviewed**: March 2026

---

## Why Startup Time Matters on EKS

Slow JVM startup has direct operational consequences on Kubernetes:

- **Rolling deployments stall** — new pods must pass readiness probes before old ones are terminated
- **HPA scale-out drops requests** — burst traffic arrives before new pods are ready
- **Spot/Fargate node recycling** — nodes are replaced frequently; every restart is a cold start
- **Wasted CPU budget** — JVM init spikes CPU, inflating resource requests and node costs

---

## Quick Wins Before Infrastructure Changes

Before investing in CDS, Native Image, or CRaC, apply these config-only changes. They require no build changes and have measurable impact.

### 1. Lazy Initialization

```yaml
spring:
  main:
    lazy-initialization: true
```

Defers bean creation until first use. Hibernate, HikariCP, Kafka consumer, Redis connections, and AOP proxies are all initialized on first request rather than at startup.

From [Spring Boot official docs](https://docs.spring.io/spring-boot/reference/using/spring-beans-and-dependency-injection.html):
> "A disadvantage of lazy initialization is that it can delay the discovery of a problem with the application."

**Trade-off:** Misconfigured beans fail on first use, not at startup. Mitigate with a readiness probe that hits `/actuator/health` before routing traffic — this triggers lazy initialization of datasource, Redis, and Kafka health indicators.

**Measured on this codebase:** −17% startup time on warm infrastructure restart.

### 2. Disable Open Session in View

```yaml
spring:
  jpa:
    open-in-view: false
```

Removes `JpaWebConfiguration` and `OpenEntityManagerInViewInterceptor` from the context. Has no functional impact on REST APIs (no view rendering). Spring Boot logs a warning when this is not explicitly set.

### 3. Diagnose Slow Beans with `/actuator/startup`

Before optimizing, identify which beans are actually slow. Spring Boot Actuator exposes a startup timeline endpoint:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,startup
spring:
  application:
    startup: buffering
```

After startup:
```bash
curl http://localhost:8080/actuator/startup | \
  jq '.timeline.events | sort_by(.duration) | reverse | .[0:10]'
```

Returns the 10 slowest beans by initialization time. Disable `lazy-initialization` temporarily when using this — lazy beans show near-zero time since they initialize on first use, not at startup.

---

## The 3 Production-Grade Approaches

> CDS and AOT Cache are **the same concept** at different Java versions — not separate approaches. AOT Cache (Java 24+, JEP 483) is the direct successor to CDS (Java 17/21) with richer caching. They share the same workflow and Gradle setup.

| Approach | Min Java | Startup Gain | Peak Throughput | Code Changes | Complexity | Status |
|---|---|---|---|---|---|---|
| **CDS** (Java 17/21) | Java 17+ | ~40% faster | Same as JVM | None | Low | ✅ Stable |
| **AOT Cache** (Java 24+ — successor to CDS) | Java 24+ | ~40% faster | Same as JVM | None | Low | ✅ Stable (Java 24+) |
| **Spring AOT on JVM** | Java 17+ | Moderate | Same as JVM | None | Low | ✅ Stable |
| **GraalVM Native Image** | Java 17+ | ~20x faster cold start | Lower than warmed JVM | Possibly significant | High | ✅ Stable |
| **CRaC** | Java 21+ (CRaC JDK only) | ~10x faster | Full JIT warmup | Minor | High | ⚠️ Maturing |

> **Note on AOT Cache gain**: JEP 483 (Java 24) delivers up to ~40% faster startup for framework-heavy apps like Spring PetClinic (per InfoQ/Oracle benchmarks). The spring.io blog referenced "3x faster" figures from unreleased Project Leyden early-access builds — those are not yet available in any stable JDK release.

---

## Approach 1 — CDS / AOT Cache

### What It Is

**Class Data Sharing (CDS)** is a mature JVM feature that pre-processes and caches class metadata into a shared archive (`.jsa` file). On startup, the JVM loads from this archive instead of re-parsing classes from scratch, reducing both startup time and memory.

**AOT Cache** (Java 24+, JEP 483) is the official successor to CDS. It extends CDS by also caching loaded and linked classes plus ahead-of-time compiled methods — not just raw class metadata — giving larger gains with the same low-friction workflow.

> From Spring Boot 4.x official docs: *"Spring Boot supports both CDS and AOT cache, and it is recommended that you use AOT cache if it is available in the JVM version you are using (Java 24 or later)."*

Spring Boot 3.3+ provides first-class support via `jarmode=tools` and Buildpacks (`BP_JVM_AOTCACHE_ENABLED` for Java 24+, `BP_JVM_CDS_ENABLED` for Java 17/21).

### How It Works

```
Build JAR → Extract JAR (jarmode=tools) → Training Run (captures class loading) → Archive/Cache file → Production Run (loads from archive)
```

The training run starts the app just long enough to capture class loading, then exits via `spring.context.exit=onRefresh`. The archive is baked into the container image — no runtime complexity.

> **Critical constraint** (from official Spring Boot docs): The archive/cache file must be used with the **extracted** form of the application. Using it with the fat JAR directly has no effect.

### Setup: Buildpacks via Gradle (Simplest — Recommended for CI/CD)

Buildpacks handle extraction, training run, and archive automatically. In `build.gradle`:

```groovy
plugins {
    id 'org.springframework.boot' version '3.4.x'
}

tasks.named("bootBuildImage") {
    environment["BP_JVM_AOTCACHE_ENABLED"] = "true"   // Java 24+ (AOT Cache)
    // environment["BP_JVM_CDS_ENABLED"] = "true"     // Java 17/21 (CDS)
    imageName = "${project.name}:${project.version}"
}
```

Build and push to ECR:

```bash
./gradlew bootBuildImage
docker tag myapp:1.0.0 <account>.dkr.ecr.<region>.amazonaws.com/myapp:1.0.0
docker push <account>.dkr.ecr.<region>.amazonaws.com/myapp:1.0.0
```

### Setup: Dockerfile with CDS (Java 17 / 21)

```dockerfile
# ---- Stage 1: Build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew bootJar -x test

# ---- Stage 2: Training run — creates the CDS archive ----
FROM eclipse-temurin:21-jdk AS optimizer
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination application
WORKDIR /app/application
RUN java -XX:ArchiveClassesAtExit=application.jsa \
         -Dspring.context.exit=onRefresh \
         -jar app.jar

# ---- Stage 3: Runtime image ----
FROM eclipse-temurin:21-jre
WORKDIR /app/application
COPY --from=optimizer /app/application /app/application
ENTRYPOINT ["java", \
  "-XX:SharedArchiveFile=application.jsa", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
```

### Setup: Dockerfile with AOT Cache (Java 24+)

Only the training run flags differ from CDS:

```dockerfile
# ---- Stage 1: Build ----
FROM eclipse-temurin:24-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew bootJar -x test

# ---- Stage 2: Training run — creates the AOT cache ----
FROM eclipse-temurin:24-jdk AS optimizer
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination application
WORKDIR /app/application
RUN java -XX:AOTCacheOutput=app.aot \
         -Dspring.context.exit=onRefresh \
         -jar app.jar

# ---- Stage 3: Runtime image ----
FROM eclipse-temurin:24-jre
WORKDIR /app/application
COPY --from=optimizer /app/application /app/application
ENTRYPOINT ["java", \
  "-XX:AOTCache=app.aot", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
```

### EKS Kubernetes Deployment

No special permissions needed:

```yaml
containers:
  - name: myapp
    image: <account>.dkr.ecr.<region>.amazonaws.com/myapp:1.0.0
    resources:
      requests:
        cpu: "1000m"   # JVM init is CPU-heavy; boost during startup
        memory: "512Mi"
      limits:
        cpu: "2000m"
        memory: "512Mi"
    startupProbe:
      httpGet:
        path: /actuator/health
        port: 8080
      failureThreshold: 20
      periodSeconds: 3
    readinessProbe:
      httpGet:
        path: /actuator/health/readiness
        port: 8080
      periodSeconds: 5
    livenessProbe:
      httpGet:
        path: /actuator/health/liveness
        port: 8080
      periodSeconds: 10
```

### Performance Numbers

**CDS benchmark** (Spring Boot 3.3, 1 CPU / 512MB, source: makariev.com):

| Configuration | Startup Time | Notes |
|---|---|---|
| Plain JVM (fat JAR) | 6.5s | Baseline |
| Extracted JAR + CDS | ~3.9s | ~40% faster |
| Extracted JAR + CDS + Spring AOT | ~3.2s | ~51% faster |
| After 15-min JIT warmup | ~3400 req/s | JVM reaches peak throughput |

**AOT Cache** (JEP 483, Java 24, source: InfoQ / Oracle / Spring PetClinic benchmark):

- Up to **~40% faster startup** for framework-heavy apps
- Gains are higher for apps that load more classes (Spring Boot benefits significantly)
- Full JIT peak throughput retained

### Trade-offs

| Pros | Cons |
|---|---|
| Zero code changes | Archive is JVM-version-specific (rebuild on JDK upgrade) |
| Same JVM, same JIT, same peak throughput | Classpath must be identical between training and production runs |
| Works with every Spring library | Moderate gain — not as dramatic as Native or CRaC |
| Full JVM tooling (JFR, heap dumps, etc.) | Training run must not trigger DB migrations |
| Production-ready since Spring Boot 3.3 | |
| Lowest operational risk | |


---

## Approach 2 — Spring AOT Processing on JVM (without GraalVM)

**Source:** [Spring Boot 4.0.3 Gradle Plugin — Ahead-of-Time Processing](https://docs.spring.io/spring-boot/gradle-plugin/aot.html)

This is distinct from both AOT Cache and GraalVM Native Image. Spring AOT analyzes the application at **build time** and generates:
- Pre-computed `BeanFactory` definitions
- Evaluated `@ConditionalOn*` conditions (moved from runtime to build time)
- Reduced runtime reflection

The result runs on a standard JVM — no GraalVM required.

### Enable in Gradle

```groovy
// build.gradle
apply plugin: 'org.springframework.boot.aot'
```

The plugin is applied automatically when the GraalVM Native Image plugin is present. Apply it manually to use AOT on a standard JVM without native compilation.

Build:
```bash
./gradlew processAot bootJar
```

Run with AOT-generated code:
```bash
java -Dspring.aot.enabled=true -jar app.jar
```

Both the Gradle task (`processAot`) and the runtime flag (`-Dspring.aot.enabled=true`) are required.

### Trade-off

AOT evaluates `@ConditionalOn*` conditions at build time. Environment-specific conditions must be stable at build time — profile-specific beans that differ between environments may require separate builds per environment.

---

## Approach 3 — GraalVM Native Image

### What It Is

Ahead-of-time (AOT) compilation that produces a **standalone native binary**. No JVM at runtime — the app is a self-contained executable. Spring Boot performs AOT processing at build time, generating source code and GraalVM hint files so the binary can be compiled statically.

Production-ready since **Spring Boot 3.0 / Spring Framework 6**.

### Key Constraints (from official Spring Boot docs)

- Beans defined in your application **cannot change at runtime**
- `@Profile` and `@ConditionalOnProperty` have limitations in native mode
- Reflection, dynamic proxies, and serialization require explicit configuration (Spring AOT handles most automatically via generated hint files)
- The classpath is **fixed at build time** — no dynamic class loading
- No lazy class loading — everything is loaded into memory on startup

### Observability in Native Image

JFR and heap dumps are **supported** in GraalVM native image but require explicit build-time flags (per GraalVM official docs):

```bash
# Enable JFR and heap dump support at native compile time
native-image --enable-monitoring=jfr,heapdump ...
```

Without these flags, JFR and heap dumps are not available. Standard JVM tools like `jmap`, `jstack`, and VisualVM do not work with native executables. Use Micrometer + CloudWatch/Prometheus for production observability instead.

### Setup: Gradle Configuration

In `build.gradle`:

```groovy
plugins {
    id 'org.springframework.boot' version '3.4.x'
    id 'io.spring.dependency-management' version '1.1.x'
    id 'org.graalvm.buildtools.native' version '0.10.x'  // required
    id 'java'
}
```

The Spring Boot Gradle plugin **automatically configures AOT tasks** when the GraalVM Native Image plugin is applied — no additional configuration needed.

### Build Option A: Buildpacks (Recommended — no local GraalVM install needed)

```bash
# Requires JDK 25+ for Buildpacks (per official Spring Boot docs)
./gradlew bootBuildImage
```

The resulting image uses `paketobuildpacks/builder-noble-java-tiny` — no JVM, minimal attack surface.

```bash
docker tag myapp:1.0.0 <account>.dkr.ecr.<region>.amazonaws.com/myapp:1.0.0
docker push <account>.dkr.ecr.<region>.amazonaws.com/myapp:1.0.0
```

### Build Option B: Native Binary via Native Build Tools

Install GraalVM locally (via SDKMAN):

```bash
sdk install java 25.r25-nik
sdk use java 25.r25-nik
```

Compile:

```bash
./gradlew nativeCompile
# Output: build/native/nativeCompile/myapp
```

### Setup: Dockerfile for EKS

```dockerfile
# ---- Stage 1: Native compile ----
FROM ghcr.io/graalvm/native-image-community:21 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew nativeCompile -x test

# ---- Stage 2: Minimal runtime — no JVM needed ----
FROM ubuntu:22.04
RUN apt-get update && apt-get install -y libstdc++6 && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/build/native/nativeCompile/myapp /app/myapp
ENTRYPOINT ["/app/myapp"]
```

### EKS Kubernetes Deployment

No special permissions needed. Lower resource requests due to minimal memory footprint:

```yaml
containers:
  - name: myapp
    image: <account>.dkr.ecr.<region>.amazonaws.com/myapp:1.0.0
    resources:
      requests:
        cpu: "250m"
        memory: "64Mi"
      limits:
        cpu: "1000m"
        memory: "128Mi"
    startupProbe:
      httpGet:
        path: /actuator/health
        port: 8080
      failureThreshold: 5
      periodSeconds: 1
    readinessProbe:
      httpGet:
        path: /actuator/health/readiness
        port: 8080
      periodSeconds: 5
    livenessProbe:
      httpGet:
        path: /actuator/health/liveness
        port: 8080
      periodSeconds: 10
```

### Performance Numbers (Spring Boot 3.3, 1 CPU / 512MB, source: makariev.com)

| Configuration | Startup Time | Req/sec (30s test) | Memory |
|---|---|---|---|
| Plain JVM | 6.5s | ~495 req/s | 512MB |
| Native Image | **~0.3s** | **~1630 req/s** | ~64MB |
| Native Image (0.5 CPU / 64MB) | ~1.2s | ~280 req/s | 64MB |
| Plain JVM after 15-min warmup | — | ~3400 req/s | 512MB |

> Native image wins on cold start and memory. JIT-warmed JVM surpasses native throughput after ~15 minutes of sustained load — because native image has no JIT compiler at runtime.

### Trade-offs

| Pros | Cons |
|---|---|
| Fastest cold start (~0.3s) | Long build times (minutes; needs 8GB+ RAM in CI) |
| Lowest memory footprint (~64MB) | Lower peak throughput than warmed JVM |
| Smallest container images | Not all libraries are compatible |
| Ideal for scale-to-zero, spot nodes, Fargate | `@Profile` / `@ConditionalOnProperty` have limitations |
| Production-ready | JFR/heap dumps require explicit build flags; standard JVM tools don't work |
| | Harder to debug in production |


---

## Approach 4 — CRaC (Coordinated Restore at Checkpoint)

### What It Is

An OpenJDK project (based on Linux CRIU) that **snapshots a fully running, warmed-up JVM process** to disk, then restores from that snapshot on subsequent starts — including JIT-compiled code and the initialized Spring context.

Spring Boot 3.2+ / Spring Framework 6.1+ provides lifecycle management for CRaC: auto-closing and reopening sockets, thread pools, and other resources around the checkpoint/restore cycle.

> CRaC is only available on **Linux** and requires a CRaC-enabled JDK distribution — either [Azul Zulu CRaC](https://www.azul.com/downloads/?package=jdk-crac) or [BellSoft Liberica CRaC](https://bell-sw.com/pages/downloads/?package=jdk-crac). Standard OpenJDK/Temurin does not include CRaC.

### How It Works

```
Start app → Warm up JVM (send real traffic) → Checkpoint (serialize JVM state to disk)
                                                          ↓
                                        Future starts restore from snapshot (~100–200ms)
```

### Two Modes

**Automatic checkpoint** (`-Dspring.context.checkpoint=onRefresh`):
- Checkpoint taken after Spring context refresh, before the app starts serving traffic
- Simpler to set up; no JIT warmup captured
- Runtime config must be baked in at build time — credentials end up in checkpoint files

**On-demand checkpoint**:
- Checkpoint taken after full warmup with real traffic via `jcmd <pid> JDK.checkpoint`
- Captures JIT-warmed state — best performance on restore
- More complex orchestration (separate warmup step before checkpoint)

### Gradle Dependency

```groovy
dependencies {
    implementation 'org.crac:crac'  // Spring Boot BOM manages the version
}
```

### Setup: Dockerfile (Automatic Checkpoint Mode)

CRIU requires Linux privileges during the checkpoint step. Standard `docker build` does not allow this — a custom BuildKit builder with `security.insecure` entitlement is required.

```dockerfile
# syntax=docker/dockerfile:1.3-labs
FROM azul/zulu-openjdk:21-jdk-crac

WORKDIR /app
COPY build/libs/*.jar app.jar

# --security=insecure is required because CRIU needs Linux privileges
# CRaC exits with code 137 (SIGKILL) on successful checkpoint — this is expected
RUN --security=insecure \
    java -Dspring.context.checkpoint=onRefresh \
         -XX:CRaCCheckpointTo=/checkpoint \
         -jar app.jar \
    || if [ $? -eq 137 ]; then return 0; else return 1; fi

EXPOSE 8080
ENTRYPOINT ["java", "-XX:CRaCRestoreFrom=/checkpoint"]
```

Build using an insecure Docker builder:

```bash
# One-time setup: create insecure builder
docker buildx create --name insecure-builder \
  --buildkitd-flags '--allow-insecure-entitlement security.insecure'

# Build
./gradlew bootJar
docker buildx --builder insecure-builder build \
  --allow security.insecure \
  --load \
  -t myapp-crac:1.0.0 .

# Push to ECR
docker tag myapp-crac:1.0.0 <account>.dkr.ecr.<region>.amazonaws.com/myapp-crac:1.0.0
docker push <account>.dkr.ecr.<region>.amazonaws.com/myapp-crac:1.0.0
```

### EKS Kubernetes Deployment

CRaC requires the `CHECKPOINT_RESTORE` Linux capability. Use the **minimum required capability** — never use `privileged: true` in production (it grants full host access to any attacker who compromises the container).

```yaml
containers:
  - name: myapp
    image: <account>.dkr.ecr.<region>.amazonaws.com/myapp-crac:1.0.0
    securityContext:
      capabilities:
        add:
          - CHECKPOINT_RESTORE
          - SYS_PTRACE   # required by CRIU
    resources:
      requests:
        cpu: "500m"
        memory: "512Mi"
      limits:
        cpu: "2000m"
        memory: "512Mi"
    startupProbe:
      httpGet:
        path: /actuator/health
        port: 8080
      failureThreshold: 10
      periodSeconds: 2
    readinessProbe:
      httpGet:
        path: /actuator/health/readiness
        port: 8080
      periodSeconds: 5
    livenessProbe:
      httpGet:
        path: /actuator/health/liveness
        port: 8080
      periodSeconds: 10
```

### Spring Data JPA Workaround

Spring Data JPA does not work out-of-the-box with CRaC. This is documented in the [Spring Lifecycle Smoke Tests status page](https://github.com/spring-projects/spring-lifecycle-smoke-tests/blob/ci/STATUS.adoc). Add a dedicated Spring profile:

```yaml
# src/main/resources/application.yml
spring:
  config:
    activate:
      on-profile: crac
  jpa:
    database-platform: org.hibernate.dialect.MySQLDialect
    hibernate:
      ddl-auto: none
    properties:
      hibernate:
        temp:
          use_jdbc_metadata_defaults: false
  sql:
    init:
      mode: never
  datasource:
    hikari:
      allow-pool-suspension: true  # required for HikariCP to survive checkpoint/restore
```

Activate during checkpoint build: `SPRING_PROFILES_ACTIVE=docker,crac`

### Optional: CRaC Resource Callbacks

If your app manages resources not handled by Spring (e.g., custom thread pools, native connections), implement the `Resource` interface:

```java
import org.crac.*;

@Component
public class MyCracResource implements Resource {

    public MyCracResource() {
        Core.getGlobalContext().register(this);
    }

    @Override
    public void beforeCheckpoint(Context<? extends Resource> context) {
        // close custom resources before snapshot
    }

    @Override
    public void afterRestore(Context<? extends Resource> context) {
        // reopen custom resources after restore
    }
}
```

### Security Warning

> Checkpoint files contain a **full memory snapshot** of the JVM, including any secrets, credentials, or tokens present at checkpoint time. This is documented by both the Spring Framework team and Azul. Store images in a **private ECR repository** with strict IAM policies. Treat checkpoint images as sensitive artifacts — do not push them to public registries.

### Performance Numbers (Spring Boot 3.2, MacBook M1, source: callistaenterprise.se)

| Microservice | Without CRaC | With CRaC | Improvement |
|---|---|---|---|
| product-composite | 1.66s | 0.13s | **12.7x faster (92%)** |
| product | 2.22s | 0.23s | **9.9x faster (90%)** |
| recommendation | 2.20s | 0.24s | **9.3x faster (89%)** |

> These benchmarks use automatic checkpoint mode (no JIT warmup). On-demand checkpoint with warmup would restore with full JIT-compiled performance.

### Trade-offs

| Pros | Cons |
|---|---|
| ~10x faster startup | Linux-only (CRIU dependency) |
| Full JIT warmup retained on restore (on-demand mode) | Checkpoint files contain secrets in memory |
| No AOT compilation constraints | Requires CRaC-enabled JDK (Azul Zulu / BellSoft Liberica only) |
| Works with most Spring libraries | Spring Data JPA needs manual workaround |
| | Requires `CHECKPOINT_RESTORE` + `SYS_PTRACE` Linux capabilities in EKS |
| | Checkpoint is OS/arch-specific (Linux x86_64 only) |
| | More complex CI/CD pipeline (insecure builder required) |


---

## Full Comparison

| | CDS (Java 17/21) | AOT Cache (Java 24+) | Spring AOT on JVM | GraalVM Native Image | CRaC |
|---|---|---|---|---|---|
| **Startup time** | ~40% faster | ~40% faster | Moderate gain | **~0.3s** | **~0.1–0.2s** |
| **Peak throughput** | Same as JVM | Same as JVM | Same as JVM | Lower (no JIT) | Full JIT (on-demand) |
| **Memory footprint** | Moderate reduction | Moderate reduction | No change | **Very low (~64MB)** | Same as JVM |
| **Code changes required** | None | None | None | Possibly significant | Minor (callbacks) |
| **Build complexity** | Low | Low | Low | High | High |
| **CI/CD impact** | Minimal | Minimal | Minimal | Long build times | Privileged builds required |
| **Library compatibility** | Universal | Universal | Universal | Partial | Mostly universal |
| **JVM tooling (JFR, etc.)** | Full | Full | Full | Requires explicit build flags | Full |
| **EKS Linux capabilities** | None | None | None | None | `CHECKPOINT_RESTORE` + `SYS_PTRACE` |
| **Security concerns** | None | None | None | None | Secrets in checkpoint files |
| **Production readiness** | ✅ Stable | ✅ Stable (Java 24+) | ✅ Stable | ✅ Stable | ⚠️ Maturing |

---

## Recommended Ranking

### Rank 1 — CDS / AOT Cache
**Best default choice for most teams. Start here.**

Zero risk, zero code changes, meaningful startup improvement. Works with every Spring library and every JVM tool. The archive is baked into the container image — no runtime complexity. Use CDS on Java 17/21, switch to AOT Cache when you move to Java 24+.

### Rank 2 — GraalVM Native Image
**Best for scale-to-zero, spot-heavy, or memory-constrained workloads.**

Unmatched cold start (~0.3s) and memory efficiency (~64MB). Ideal when pods are frequently recycled (Fargate, spot nodes). Requires investment: validate library compatibility, adjust CI pipeline for long build times. Not ideal for services that need sustained high throughput after long uptime — JIT-warmed JVM surpasses native throughput after ~15 minutes.

### Rank 3 — CRaC
**Best when you need both fast startup AND full JIT peak performance.**

The only approach that gives you a warmed JIT on restore (on-demand mode). Worth the complexity if your service is latency-sensitive and scales frequently. Requires careful security handling of checkpoint files, a CRaC-enabled JDK, and Linux-specific CI/CD setup. Always check the [Spring Lifecycle Smoke Tests status page](https://github.com/spring-projects/spring-lifecycle-smoke-tests/blob/ci/STATUS.adoc) for library compatibility before committing.

---

## Decision Guide by Scenario

| Scenario | Recommended Approach |
|---|---|
| Existing app, minimal risk, quick win | **CDS** (Java 17/21) or **AOT Cache** (Java 24+) |
| Greenfield on Java 24+ | **AOT Cache** |
| Scale-to-zero / Fargate / spot-heavy | **GraalVM Native Image** |
| Latency-sensitive + frequent HPA scale-out | **CRaC** (if team can handle complexity) |
| Memory-constrained nodes | **GraalVM Native Image** |
| Long-running services, high sustained throughput | **CDS / AOT Cache** (native image's JIT disadvantage matters here) |
| Fast start + full JIT peak throughput both required | **CRaC** (on-demand checkpoint mode) |

---

## Universal EKS Tuning (Apply Regardless of Approach)

### 1. Boost CPU During Startup

JVM init is CPU-heavy. Temporarily over-provision and use a `startupProbe` to give the pod time before liveness kicks in:

```yaml
resources:
  requests:
    cpu: "1250m"
    memory: "512Mi"
startupProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  failureThreshold: 30
  periodSeconds: 2
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  periodSeconds: 10
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  periodSeconds: 5
```

### 2. Always Use Extracted JAR Layout

Required for CDS/AOT Cache. Also more efficient in general (better Docker layer caching):

```bash
java -Djarmode=tools -jar app.jar extract --destination application
java -jar application/app.jar
```

### 3. JVM Container Flags

Always include these for correct container-aware memory/CPU detection:

```bash
java \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -jar application/app.jar
```

### 4. Graceful Shutdown

Enable graceful shutdown so in-flight requests complete before pod termination:

```yaml
# application.yml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

---

## References

| Source | URL |
|---|---|
| Spring Boot Docs — AOT Cache & CDS | https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html |
| Spring Boot Docs — Spring AOT on JVM (Gradle) | https://docs.spring.io/spring-boot/gradle-plugin/aot.html |
| Spring Boot Docs — GraalVM Native Images | https://docs.spring.io/spring-boot/reference/packaging/native-image/introducing-graalvm-native-images.html |
| Spring Boot Docs — First GraalVM Native App (Gradle) | https://docs.spring.io/spring-boot/how-to/native-image/developing-your-first-application.html |
| Spring Boot Docs — CRaC Checkpoint & Restore | https://docs.spring.io/spring-boot/reference/packaging/checkpoint-restore.html |
| Spring Boot Docs — CDS with Buildpacks | https://docs.spring.io/spring-boot/how-to/aot-cache.html |
| Spring.io Blog — CDS Support and Project Leyden | https://spring.io/blog/2024/08/29/spring-boot-cds-support-and-project-leyden-anticipation |
| InfoQ — Java Applications Can Start 40% Faster in Java 24 (JEP 483) | https://www.infoq.com/news/2025/03/java-24-leyden-ships |
| Benchmark: CDS vs Native Image (Spring Boot 3.3) | https://www.makariev.com/blog/spring-boot-cds-native-image-dockerfile/ |
| CRaC with Spring Boot 3.2 — Full Walkthrough | https://callistaenterprise.se/blogg/teknik/2024/07/01/SpringBoot-with-CRaC-part1-automatic-checkpoint/ |
| GraalVM Docs — JFR with Native Image | https://www.graalvm.org/reference-manual/native-image/guides/build-and-run-native-executable-with-jfr/ |
| GraalVM Docs — Heap Dump from Native Executable | https://www.graalvm.org/latest/reference-manual/native-image/guides/create-heap-dump/ |
| JEP 483 — Ahead-of-Time Class Loading & Linking | https://openjdk.org/jeps/483 |
| Spring Lifecycle Smoke Tests — CRaC Compatibility Status | https://github.com/spring-projects/spring-lifecycle-smoke-tests/blob/ci/STATUS.adoc |
