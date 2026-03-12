# Spring Boot + Gradle on AWS EKS — JVM Flags, application.yml Tuning & Further Best Practices

> **Scope**: JVM flag reference, annotated `application.yml`, Gradle build optimization, container security hardening, and secrets management. Complements `JVM_STARTUP_OPTIMIZATION.md` and `SPRING_BOOT_EKS_PRODUCTION_OPTIMIZATION.md`.
> **Stack**: Java 21 LTS · Spring Boot 3.4+ · Gradle 8.5+ · AWS EKS
> **Sources**: Spring Boot 3.4 official release notes, Oracle JDK 21 release notes, OpenJDK JEPs, AWS official docs, HikariCP docs, Gradle official docs
> **Last reviewed**: March 2026

---

## 1. JVM Flags — Production Reference

### How to Apply: `JAVA_TOOL_OPTIONS` in Kubernetes

Set JVM flags via environment variable in your Deployment — keeps them environment-specific without rebuilding the image:

```yaml
# k8s/deployment.yaml
env:
  - name: JAVA_TOOL_OPTIONS
    value: >-
      -XX:+UseContainerSupport
      -XX:MaxRAMPercentage=75.0
      -XX:InitialRAMPercentage=50.0
      -XX:+UseG1GC
      -XX:MaxGCPauseMillis=200
      -XX:+DisableExplicitGC
      -XX:+AlwaysPreTouch
      -XX:+ExitOnOutOfMemoryError
      -XX:+HeapDumpOnOutOfMemoryError
      -XX:HeapDumpPath=/tmp/heapdump.hprof
      -XX:MaxMetaspaceSize=256m
      -XX:ReservedCodeCacheSize=256m
      -Djava.security.egd=file:/dev/./urandom
```

### Flag-by-Flag Explanation

| Flag | Default? | What It Does | When to Use |
|---|---|---|---|
| `-XX:+UseContainerSupport` | On since Java 10 | JVM reads cgroup memory/CPU limits instead of host values | Always explicit — without this, JVM sees host RAM and over-allocates heap, causing OOMKilled |
| `-XX:MaxRAMPercentage=75.0` | 25% | Heap = 75% of container memory limit | Always — replaces fixed `-Xmx`; adapts if container is resized |
| `-XX:InitialRAMPercentage=50.0` | 1.5625% | Start heap at 50% of limit | Always — reduces GC pressure at startup; JVM grows heap as needed |
| `-XX:+UseG1GC` | Default Java 9+ | Enables G1 garbage collector | Explicit for clarity; good default for most services |
| `-XX:MaxGCPauseMillis=200` | 200ms | G1GC targets ≤200ms pause time | Latency-sensitive services; lower = more frequent GC cycles |
| `-XX:+DisableExplicitGC` | Off | Ignores `System.gc()` calls from libraries | Always — prevents unexpected full GC pauses from third-party code |
| `-XX:+AlwaysPreTouch` | Off | Pre-allocates all heap pages at JVM startup | Services where consistent runtime latency matters more than startup time |
| `-XX:+ExitOnOutOfMemoryError` | Off | JVM exits immediately on OOM | Always in containers — Kubernetes restarts the pod cleanly; a limping OOM pod is worse |
| `-XX:+HeapDumpOnOutOfMemoryError` | Off | Writes heap dump on OOM | Always — zero runtime overhead; critical for post-mortem diagnosis |
| `-XX:HeapDumpPath=/tmp/heapdump.hprof` | `./java_pid<pid>.hprof` | Where to write the heap dump | Always with `HeapDumpOnOutOfMemoryError`; mount `/tmp` as `emptyDir` or a PVC |
| `-XX:MaxMetaspaceSize=256m` | Unbounded | Caps Metaspace growth | Always — Spring Boot loads many classes/proxies; without a cap, Metaspace can grow unbounded and cause node-level OOM |
| `-XX:ReservedCodeCacheSize=256m` | 240m (Java 21) | Size of JIT compiled code cache | Increase if you see `CodeCache is full` warnings; too small causes JIT deoptimization and CPU spikes |
| `-Djava.security.egd=file:/dev/./urandom` | `/dev/random` | Non-blocking entropy for `SecureRandom` | Always in containers — `/dev/random` can block on low-entropy systems, stalling startup |

> **Removed**: `-XX:+ParallelRefProcEnabled` — made a no-op in Java 18, obsolete on Java 21+. Prints a warning if used. Confirmed via Oracle JDK 21 release notes.
> **Conditional only**: `-XX:+UseStringDeduplication` — adds ~10% CPU overhead for background deduplication. Only add if you have profiled and confirmed significant duplicate String pressure in your heap.

### Latency-Sensitive Services: Generational ZGC (Java 21+)

Replace G1GC flags with:

```
-XX:+UseZGC -XX:+ZGenerational
```

Per [JEP 439](https://openjdk.org/jeps/439) (Java 21): Generational ZGC delivers sub-millisecond GC pauses with lower heap overhead than non-generational ZGC. Remove `-XX:MaxGCPauseMillis` — it is not applicable to ZGC.

Trade-off: ZGC uses more memory than G1GC. Only switch if you have measured GC pause problems with G1.

### GC Logging — Enable in Production

```
-Xlog:gc*::time,uptime,level,tags
```

To rotating files (useful if you ship logs to S3 or EFS):

```
-Xlog:gc*:file=/tmp/gc.log:time,uptime,level,tags:filecount=5,filesize=20m
```

### G1GC Advanced Tuning (High-Throughput Services)

These are secondary knobs — only touch after measuring with GC logs:

| Flag | Default | Effect |
|---|---|---|
| `-XX:G1HeapRegionSize=16m` | Auto (1–32m) | Larger regions reduce region count overhead on large heaps (≥8GB) |
| `-XX:InitiatingHeapOccupancyPercent=35` | 45 | Trigger concurrent GC earlier — reduces full GC risk under bursty load |
| `-XX:G1NewSizePercent=20` | 5 | Minimum young gen size — prevents over-shrinking under low load |
| `-XX:ParallelGCThreads=N` | ~CPU count | STW GC threads — in containers, set to `min(4, vCPU_count)` to avoid over-subscription |
| `-XX:ConcGCThreads=N` | ~ParallelGCThreads/4 | Concurrent marking threads — increase if GC can't keep up with allocation rate |

---

## 2. application.yml — Full Production Configuration

```yaml
# ============================================================
# SERVER
# ============================================================
server:
  port: 8080
  # Spring Boot 3.4+: graceful shutdown is ON BY DEFAULT.
  # Explicitly set here for clarity and to document intent.
  shutdown: graceful

  compression:
    enabled: true
    mime-types: application/json,application/xml,text/html,text/plain
    min-response-size: 2KB

  tomcat:
    threads:
      max: 200          # irrelevant when virtual threads are enabled — Tomcat uses one VT per request
      min-spare: 10
    accept-count: 100
    max-connections: 8192
    connection-timeout: 20s
    keep-alive-timeout: 30s
    basedir: /application/tmp   # required when readOnlyRootFilesystem: true

# ============================================================
# SPRING LIFECYCLE & CONCURRENCY
# ============================================================
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s   # must be < terminationGracePeriodSeconds in k8s

  threads:
    virtual:
      enabled: true   # Java 21+, Spring Boot 3.2+
                      # Spring Boot 3.4 expanded coverage: Tomcat, @Async, @Scheduled,
                      # Spring Integration TaskScheduler, OtlpMeterRegistry, Undertow

  # ============================================================
  # ASYNC TASK EXECUTOR
  # Only relevant when virtual threads are DISABLED.
  # With virtual threads enabled, Spring uses a virtual thread per task.
  # ============================================================
  task:
    execution:
      pool:
        core-size: 8
        max-size: 64
        queue-capacity: 500
        keep-alive: 60s
      thread-name-prefix: async-

  # ============================================================
  # DATASOURCE / HIKARICP
  # ============================================================
  datasource:
    hikari:
      maximum-pool-size: 10         # formula: (db_max_conn - reserved) / pod_replicas
      minimum-idle: 5
      connection-timeout: 30000     # 30s max wait for a connection from pool
      idle-timeout: 600000          # 10min — close idle connections
      max-lifetime: 1800000         # 30min — must be < DB server connection timeout
      keepalive-time: 60000         # 1min ping — prevents stale connections on EKS
      pool-name: HikariPool-main
      leak-detection-threshold: 60000  # warn if connection held > 60s (debug aid)

  # ============================================================
  # JPA / HIBERNATE
  # ============================================================
  jpa:
    open-in-view: false             # CRITICAL: prevents holding DB connections for full HTTP lifecycle
    properties:
      hibernate:
        jdbc:
          batch_size: 25            # batch inserts/updates — fewer DB round trips
          fetch_size: 50            # JDBC cursor fetch size — tune for large result sets
        order_inserts: true         # required for batch_size to work correctly
        order_updates: true
        default_batch_fetch_size: 100  # prevents N+1 on lazy-loaded collections (IN clause batching)
        generate_statistics: false  # disable in production (overhead)

  # ============================================================
  # SECRETS — load from AWS Secrets Manager at startup
  # Requires: io.awspring.cloud:spring-cloud-aws-starter-secrets-manager
  # bootstrap.yml is DEPRECATED in Spring Boot 3 — use spring.config.import here
  # ============================================================
  config:
    import: "aws-secretsmanager:myapp/prod/db"
                                    # remove 'optional:' in production to fail fast on missing secrets
                                    # use 'optional:' prefix for local dev only

# ============================================================
# ACTUATOR / MANAGEMENT
# Spring Boot 3.4: management.endpoints.enabled-by-default is DEPRECATED
# Use management.endpoints.access.default instead
# ============================================================
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
    access:
      default: read-only            # Spring Boot 3.4+ — replaces enabled-by-default
  endpoint:
    health:
      show-details: when-authorized
      probes:
        enabled: true               # /actuator/health/liveness and /readiness
    prometheus:
      access: unrestricted          # Prometheus scrape needs full access
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true
  metrics:
    export:
      prometheus:
        enabled: true
  tracing:
    sampling:
      probability: 0.1              # 10% in production; 1.0 for debugging

# ============================================================
# STRUCTURED LOGGING — Spring Boot 3.4+ built-in, no extra dependencies
# Formats: ecs (Elastic), logstash, gelf (Graylog)
# ============================================================
logging:
  structured:
    format:
      console: ecs                  # JSON to stdout — CloudWatch/Fluentd/Loki can parse directly
      # file: ecs                   # uncomment to also write JSON to a file
    ecs:
      service:
        name: ${spring.application.name}
        version: ${spring.application.version:unknown}
        environment: ${spring.profiles.active:default}
  level:
    root: INFO
    org.springframework.web: WARN
    org.hibernate.SQL: WARN         # set to DEBUG only when diagnosing queries
```

### Key Settings Explained

**`server.shutdown: graceful` (Spring Boot 3.4+ default)** — Graceful shutdown is now enabled by default in Spring Boot 3.4. The explicit config is kept for documentation clarity. Ensure `timeout-per-shutdown-phase` is less than `terminationGracePeriodSeconds` in your Kubernetes Deployment.

**`spring.jpa.open-in-view: false`** — Spring Boot enables OSIV by default, which holds a DB connection open for the entire HTTP request lifecycle including response rendering. With many concurrent requests on EKS, this exhausts HikariCP fast. Always disable.

**`hibernate.default_batch_fetch_size: 100`** — This is different from `jdbc.batch_size`. It controls how Hibernate loads lazy collections using SQL `IN (?, ?, ...)` clauses instead of one query per entity. Without it, loading 100 entities with a lazy collection fires 101 queries (N+1). With it, Hibernate batches them into a handful of `IN` queries. This is one of the highest-impact single-line JPA configs for read-heavy services.

**`hibernate.jdbc.batch_size: 25`** — Without this, Hibernate issues one SQL per entity in a loop. Batching groups them into fewer round trips — significant for write-heavy services. Requires `order_inserts: true` and `order_updates: true` to work correctly.

**`logging.structured.format.console: ecs`** — New in Spring Boot 3.4. Outputs JSON logs to stdout with no extra dependencies (no Logstash encoder, no custom appender). CloudWatch Logs, Fluentd, and Loki can parse ECS JSON directly. Adds `service.name`, `service.version`, `log.level`, `@timestamp`, trace IDs automatically.

**`management.endpoints.access.default: read-only`** — New in Spring Boot 3.4. Replaces the deprecated `management.endpoints.enabled-by-default`. Allows read-only access to all endpoints by default, with `prometheus` explicitly set to `unrestricted` for scraping.

**`hikari.max-lifetime`** — Must be set lower than the DB server's connection timeout. RDS MySQL default `wait_timeout` is 8 hours, but VPC/EKS network idle timeouts can be much shorter. 30 minutes is a safe default.

**`spring.config.import: aws-secretsmanager:...`** — The correct Spring Boot 3 approach for loading secrets. `bootstrap.yml` is deprecated in Spring Boot 3. Remove `optional:` in production to fail fast on missing secrets.

**`spring.task.execution`** — Only relevant when virtual threads are disabled. With `spring.threads.virtual.enabled=true`, Spring uses a virtual thread per `@Async` task and this pool is bypassed.

**`-XX:ReservedCodeCacheSize=256m`** — The JIT compiler stores compiled native code here. If it fills up, the JVM stops JIT-compiling new methods and falls back to interpreted mode, causing sudden CPU spikes and latency degradation. The default 240m is often too small for large Spring Boot apps with many classes. 256m–512m is recommended.

**`-XX:+HeapDumpOnOutOfMemoryError`** — Zero runtime overhead. The flag is only checked after an OOM occurs. Always enable in production — it is the only way to diagnose what was in the heap when the pod died. Pair with `-XX:HeapDumpPath=/tmp/heapdump.hprof` and mount `/tmp` as an `emptyDir` or PVC.


---

## 3. Gradle Build Optimization

### `gradle.properties`

```properties
# Gradle 8.5+ required for Java 21 full support (compile + run Gradle daemon on Java 21)
# Gradle 8.4 supports Java 21 toolchains but cannot run the daemon on Java 21
org.gradle.daemon=true
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true     # Gradle 8+ — cache configuration phase
                                        # Note: some older plugins are incompatible; test before enabling
org.gradle.jvmargs=-Xmx2g -XX:+UseG1GC -XX:MaxMetaspaceSize=512m
```

### Java Toolchains (Gradle 8.5+)

Toolchains decouple the JDK used to run Gradle from the JDK used to compile and test your code. This is the correct way to pin Java 21 in CI without requiring the CI agent to have Java 21 as the system JDK:

```groovy
// build.gradle
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
```

Gradle will auto-provision the correct JDK via toolchain resolvers (e.g., Foojay). In CI, you can also pre-install it:

```yaml
# GitHub Actions
- uses: actions/setup-java@v4
  with:
    java-version: '21'
    distribution: 'temurin'
```

### CI: Cache Gradle Dependencies (GitHub Actions)

```yaml
- uses: actions/cache@v4
  with:
    path: |
      ~/.gradle/caches
      ~/.gradle/wrapper
    key: gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
    restore-keys: gradle-
```

Reduces CI build from ~5 minutes to ~1 minute for unchanged dependencies.

### Dependency Locking for Reproducible Builds

```groovy
// build.gradle
dependencyLocking {
    lockAllConfigurations()
}
```

```bash
./gradlew dependencies --write-locks
```

Commit the generated `gradle.lockfile`. On CI, Gradle verifies resolved versions match the lock file — prevents silent dependency upgrades from breaking production.

### Trade-offs

| Setting | Benefit | Cost |
|---|---|---|
| `org.gradle.parallel=true` | Faster multi-module builds | Higher CI agent CPU/memory |
| `org.gradle.caching=true` | Reuses task outputs | Cache invalidation complexity |
| `org.gradle.configuration-cache=true` | Skips config phase on re-runs | Some plugins not yet compatible — test first |
| Java toolchains | Reproducible JDK version across environments | Requires Gradle 8.5+ for Java 21 |
| Dependency locking | Reproducible builds | Must update lock files on upgrades |

---

## 4. Container Security Hardening

### Non-Root User in Dockerfile

```dockerfile
FROM bellsoft/liberica-openjre-debian:21-cds
WORKDIR /application

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser

COPY --from=builder /builder/extracted/dependencies/ ./
COPY --from=builder /builder/extracted/spring-boot-loader/ ./
COPY --from=builder /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder /builder/extracted/application/ ./

USER appuser
ENTRYPOINT ["java", "-jar", "application.jar"]
```

### Kubernetes Pod Security Context

```yaml
spec:
  template:
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
        runAsGroup: 1000
        fsGroup: 1000
        seccompProfile:
          type: RuntimeDefault       # default seccomp syscall filter — blocks ~300 dangerous syscalls

      containers:
        - name: myapp
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop:
                - ALL
```

### Handling `readOnlyRootFilesystem: true` with Spring Boot

The JVM and Tomcat write temp files at runtime. Mount writable `emptyDir` volumes:

```yaml
containers:
  - name: myapp
    volumeMounts:
      - name: tmp
        mountPath: /tmp             # JVM heap dumps, GC logs
      - name: app-tmp
        mountPath: /application/tmp # Tomcat work directory

volumes:
  - name: tmp
    emptyDir: {}
  - name: app-tmp
    emptyDir: {}
```

Configure Tomcat's work directory in `application.yml`:

```yaml
server:
  tomcat:
    basedir: /application/tmp
```

### Trade-offs

| Setting | Benefit | Cost |
|---|---|---|
| `runAsNonRoot` | Prevents root-level host access | Some base images need adjustment |
| `readOnlyRootFilesystem` | Immutable container — attacker can't write malware | Requires explicit `emptyDir` mounts |
| `allowPrivilegeEscalation: false` | Blocks privilege escalation exploits | None for standard Spring Boot apps |
| `capabilities: drop: ALL` | Minimal Linux capability surface | Verify app doesn't need specific capabilities |
| `seccompProfile: RuntimeDefault` | Blocks ~300 dangerous syscalls at kernel level | Negligible overhead |

---

## 5. Secrets Management — AWS Secrets Manager + EKS Pod Identity

Never store secrets in `application.yml`, environment variables baked into images, or Kubernetes Secrets (base64-encoded, not encrypted by default).

### Recommended: AWS Secrets Manager + EKS Pod Identity

EKS Pod Identity (the modern successor to IRSA, available since EKS 1.24) lets pods assume an IAM role without managing service account annotations manually.

**Step 1**: Store secrets in AWS Secrets Manager:
```bash
aws secretsmanager create-secret \
  --name myapp/prod/db \
  --secret-string '{"username":"dbuser","password":"<secret>"}'
```

**Step 2**: Grant the pod's IAM role read access:
```json
{
  "Effect": "Allow",
  "Action": ["secretsmanager:GetSecretValue"],
  "Resource": "arn:aws:secretsmanager:<region>:<account>:secret:myapp/prod/*"
}
```

**Step 3**: Add Spring Cloud AWS dependency in `build.gradle`:
```groovy
dependencies {
    implementation 'io.awspring.cloud:spring-cloud-aws-starter-secrets-manager'
}
```

**Step 4**: Configure in `application.yml` (Spring Boot 3 — no `bootstrap.yml`):
```yaml
spring:
  config:
    import: "aws-secretsmanager:myapp/prod/db"
  cloud:
    aws:
      region:
        static: ap-southeast-1
```

Spring Cloud AWS maps JSON keys automatically:
- `myapp/prod/db` → `{"username":"...","password":"..."}` → `spring.datasource.username`, `spring.datasource.password`

### Trade-offs

| Approach | Benefit | Cost |
|---|---|---|
| AWS Secrets Manager + Pod Identity | Centralized, audited, rotatable, no credential files | Requires IAM setup; ~100ms startup latency |
| Kubernetes Secrets (base64) | Simple | Not encrypted at rest by default; visible in etcd |
| Environment variables in Deployment | Simple | Visible in pod spec; not rotatable without redeploy |

---

## 6. Virtual Threads — What Spring Boot 3.4 Covers

With `spring.threads.virtual.enabled=true`, the following components automatically use virtual threads as of Spring Boot 3.4:

| Component | Since |
|---|---|
| Tomcat request handling | Spring Boot 3.2 |
| `@Async` methods | Spring Boot 3.2 |
| `@Scheduled` tasks | Spring Boot 3.2 |
| Spring Integration `TaskScheduler` | Spring Boot 3.4 |
| `OtlpMeterRegistry` | Spring Boot 3.4 |
| Undertow web server | Spring Boot 3.4 |

**Thread pinning** — On Java 21–23, virtual threads pin their carrier thread when entering a `synchronized` block, reducing concurrency. This is resolved in Java 24 via [JEP 491](https://openjdk.org/jeps/491). On Java 21, avoid `synchronized` in hot paths; use `ReentrantLock` instead.

**Virtual threads are not a silver bullet** — They excel at I/O-bound workloads (DB calls, HTTP calls, file I/O). For CPU-bound work (image processing, crypto, heavy computation), they offer no benefit over platform threads and can increase context-switch overhead.

---

## Summary: What to Apply and When

| Area | Config | Apply When | Version |
|---|---|---|---|
| JVM heap sizing | `MaxRAMPercentage=75.0` | Always | Java 10+ |
| Metaspace cap | `MaxMetaspaceSize=256m` | Always | All |
| Code cache | `ReservedCodeCacheSize=256m` | Always for large Spring apps | All |
| Fail fast on OOM | `ExitOnOutOfMemoryError` | Always in containers | All |
| Heap dump on OOM | `HeapDumpOnOutOfMemoryError` + path | Always | All |
| GC pauses | `MaxGCPauseMillis=200` (G1) or `UseZGC -ZGenerational` | Always; ZGC for latency-sensitive | Java 21 for ZGC |
| Entropy blocking | `java.security.egd` | Always in containers | All |
| OSIV | `open-in-view: false` | Always with JPA | All |
| N+1 prevention | `default_batch_fetch_size: 100` | Always with lazy-loaded collections | All |
| Hibernate batching | `jdbc.batch_size: 25` | Write-heavy services | All |
| Response compression | `server.compression.enabled` | Always for REST APIs | All |
| Virtual threads | `spring.threads.virtual.enabled` | Java 21+, I/O-bound services | Spring Boot 3.2+ |
| Structured logging | `logging.structured.format.console: ecs` | Always — replaces Logstash encoder | Spring Boot 3.4+ |
| Actuator access | `management.endpoints.access.default` | Spring Boot 3.4+ | Spring Boot 3.4+ |
| Graceful shutdown | `server.shutdown: graceful` | Default in 3.4+; explicit for clarity | Spring Boot 3.4+ default |
| Gradle toolchains | `java { toolchain { languageVersion = 21 } }` | Always — pins JDK version | Gradle 8.5+ |
| Gradle caching | `gradle.properties` + CI cache | Always | Gradle 8+ |
| Non-root container | Dockerfile + securityContext | Always in production | All |
| Read-only filesystem | `readOnlyRootFilesystem: true` | Production; requires emptyDir mounts | All |
| Secrets management | AWS Secrets Manager + Pod Identity | Always — never plaintext secrets | EKS 1.24+ |

---

## 7. Resilience & Fault Tolerance

A service that is fast but brittle is not production-ready. These configs prevent cascading failures — the most common cause of full outages in microservice architectures.

### Resilience4j — Circuit Breaker, Retry, Bulkhead, Timeout

Add dependency:
```groovy
implementation 'io.github.resilience4j:resilience4j-spring-boot3'
```

```yaml
resilience4j:
  # Circuit breaker: open circuit after 50% failures in last 10 calls
  circuitbreaker:
    instances:
      downstream-api:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
        register-health-indicator: true   # exposes to /actuator/health

  # Timeout: never trust external calls to return — always set
  timelimiter:
    instances:
      downstream-api:
        timeout-duration: 3s

  # Retry: exponential backoff + jitter prevents thundering herd on recovery
  retry:
    instances:
      downstream-api:
        max-attempts: 3
        wait-duration: 500ms
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
        exponential-max-wait-duration: 10s
        retry-exceptions:
          - java.io.IOException
          - java.util.concurrent.TimeoutException

  # Bulkhead: limits concurrent calls so one slow dependency can't exhaust all threads
  bulkhead:
    instances:
      downstream-api:
        max-concurrent-calls: 20
        max-wait-duration: 100ms
```

Apply to a service method:
```java
@CircuitBreaker(name = "downstream-api", fallbackMethod = "fallback")
@TimeLimiter(name = "downstream-api")
@Retry(name = "downstream-api")
@Bulkhead(name = "downstream-api")
public CompletableFuture<Response> callDownstream() { ... }
```

**Why this matters on EKS**: Without circuit breakers, a slow RDS instance or external API causes HikariCP connection pool exhaustion within seconds, which cascades to HTTP thread exhaustion, which takes down the entire pod — and then all replicas simultaneously.

### HTTP Client Timeouts — Always Set

The most common production outage cause: `RestClient`/`RestTemplate` with no timeouts. Spring Boot 3.4 introduced `ClientHttpRequestFactoryBuilder` for clean configuration:

```java
@Bean
public RestClient restClient() {
    return RestClient.builder()
        .requestFactory(
            ClientHttpRequestFactoryBuilder.httpComponents()
                .withDefaultRequestConfig(RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.ofSeconds(1))  // wait for pool slot
                    .setResponseTimeout(Timeout.ofSeconds(5))           // total response time
                    .build())
                .build())
        .build();
}
```

| Timeout type | What it guards | Recommended |
|---|---|---|
| Connection request timeout | Wait for a connection from the pool | 1s |
| Connect timeout | TCP handshake to remote host | 2s |
| Response timeout | Total time waiting for response | 3–10s depending on SLA |

---

## 8. Kubernetes Deployment Reliability

### `preStop` Hook + `terminationGracePeriodSeconds`

Kubernetes removes a pod from the load balancer **asynchronously** after sending SIGTERM. Without a `preStop` sleep, new requests can still arrive after shutdown begins — causing 502 errors during every rolling deploy:

```yaml
spec:
  template:
    spec:
      terminationGracePeriodSeconds: 60   # must be > preStop sleep + timeout-per-shutdown-phase

      containers:
        - name: myapp
          lifecycle:
            preStop:
              exec:
                command: ["sh", "-c", "sleep 5"]   # wait for LB to drain before SIGTERM
```

Relationship between these values:
```
terminationGracePeriodSeconds (60s)
  └── preStop sleep (5s)
  └── spring.lifecycle.timeout-per-shutdown-phase (30s)
  └── buffer (25s)
```

### Pod Disruption Budget

Prevents Kubernetes from evicting all pods simultaneously during node drain, cluster upgrades, or Karpenter consolidation:

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: myapp-pdb
spec:
  minAvailable: 1       # always keep at least 1 pod running
  selector:
    matchLabels:
      app: myapp
```

For high-availability services, use `minAvailable: "50%"` instead of a fixed number so it scales with replica count.

### Pod Anti-Affinity

Prevents all replicas landing on the same node — a single node failure would take down the entire service:

```yaml
affinity:
  podAntiAffinity:
    preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        podAffinityTerm:
          labelSelector:
            matchLabels:
              app: myapp
          topologyKey: kubernetes.io/hostname
```

Use `requiredDuringSchedulingIgnoredDuringExecution` for strict enforcement (but this can block scheduling if nodes are scarce).

### Resource Requests vs Limits — CPU Throttling

A common mistake: setting CPU `limit = request`. The Linux CFS scheduler throttles the container the moment it exceeds the limit — even if the node has spare CPU. JVM GC is especially sensitive to this:

```yaml
resources:
  requests:
    memory: "512Mi"
    cpu: "500m"
  limits:
    memory: "512Mi"   # memory: limit = request — prevents OOMKilled surprises from heap growth
    cpu: "2000m"      # cpu: limit > request — allows bursting; NEVER set equal to request for JVM
```

**Why memory limit = request**: If memory limit > request, the pod can grow beyond what the scheduler reserved, causing node-level memory pressure and OOMKilled on other pods. For JVM, heap is bounded by `MaxRAMPercentage` so memory usage is predictable — set limit = request.

---

## 9. Observability — Gaps That Hurt in Production

### Slow Query Detection

Catches N+1 queries and missing indexes before they become incidents:

```yaml
spring:
  jpa:
    properties:
      hibernate:
        session:
          events:
            log:
              LOG_QUERIES_SLOWER_THAN_MS: 500   # log any query taking > 500ms
```

### Correlation IDs — Trace Requests Across Log Lines

With Spring Boot 3.4 structured logging + Micrometer Tracing, `traceId` and `spanId` are automatically injected into every log line when you add:

```groovy
implementation 'io.micrometer:micrometer-tracing-bridge-otel'
implementation 'io.opentelemetry:opentelemetry-exporter-otlp'
```

For custom request IDs from upstream (API Gateway, ALB):
```java
// OncePerRequestFilter
String requestId = Optional.ofNullable(request.getHeader("X-Request-ID"))
    .orElse(UUID.randomUUID().toString());
MDC.put("requestId", requestId);
response.setHeader("X-Request-ID", requestId);  // echo back for client correlation
```

### JVM & HikariCP Metrics — Auto-Exposed

With `spring-boot-starter-actuator` on the classpath, Micrometer automatically exposes to `/actuator/prometheus`:

| Metric | What to alert on |
|---|---|
| `jvm.gc.pause` | p99 > 500ms → switch to ZGC |
| `jvm.memory.used{area=heap}` | > 85% of max → heap pressure |
| `jvm.memory.used{area=nonheap}` | Growing unbounded → Metaspace leak |
| `hikaricp.connections.pending` | > 0 sustained → pool too small or slow queries |
| `hikaricp.connections.timeout` | Any → connection pool exhausted |
| `jvm.threads.live` | Sudden spike → thread leak |

---

## 10. Spring Boot Application Config — Commonly Missed

### Background Bean Initialization (Spring Boot 3.4+)

Initializes independent beans in parallel during startup — reduces startup time on multi-core nodes with no code changes:

```yaml
spring:
  main:
    background-bean-initialization: true   # Spring Boot 3.4+
```

Note: beans with dependencies are still initialized in order. Only truly independent beans benefit.

### Application Identity — Required for Observability

Often forgotten but required for structured logging, tracing service names, and service discovery:

```yaml
spring:
  application:
    name: myapp                            # used by Micrometer, structured logs, service discovery
    version: ${APP_VERSION:unknown}        # inject from CI: -e APP_VERSION=$GIT_SHA
```

In your CI/CD pipeline:
```yaml
# GitHub Actions
- name: Build and push
  env:
    APP_VERSION: ${{ github.sha }}
```

### Lazy Initialization — Dev vs Production

```yaml
spring:
  main:
    lazy-initialization: false   # keep false in production
                                 # true speeds up startup but first request is slow
                                 # and startup errors are hidden until runtime
```

### Tomcat Access Logs — Disable in Production

Tomcat access logs are enabled by default and write to disk. On EKS, your ALB/Nginx access logs + structured application logs are sufficient. Disable to reduce I/O:

```yaml
server:
  tomcat:
    accesslog:
      enabled: false   # ALB and structured app logs cover this; avoid duplicate disk I/O
```

---

## Updated Summary Table

| Area | Config | Apply When | Version |
|---|---|---|---|
| JVM heap sizing | `MaxRAMPercentage=75.0` | Always | Java 10+ |
| Metaspace cap | `MaxMetaspaceSize=256m` | Always | All |
| Code cache | `ReservedCodeCacheSize=256m` | Always for large Spring apps | All |
| Fail fast on OOM | `ExitOnOutOfMemoryError` | Always in containers | All |
| Heap dump on OOM | `HeapDumpOnOutOfMemoryError` + path | Always | All |
| GC pauses | `MaxGCPauseMillis=200` (G1) or `UseZGC -ZGenerational` | Always; ZGC for latency-sensitive | Java 21 for ZGC |
| Entropy blocking | `java.security.egd` | Always in containers | All |
| OSIV | `open-in-view: false` | Always with JPA | All |
| N+1 prevention | `default_batch_fetch_size: 100` | Always with lazy-loaded collections | All |
| Hibernate batching | `jdbc.batch_size: 25` | Write-heavy services | All |
| Slow query logging | `LOG_QUERIES_SLOWER_THAN_MS: 500` | Always | All |
| Response compression | `server.compression.enabled` | Always for REST APIs | All |
| Virtual threads | `spring.threads.virtual.enabled` | Java 21+, I/O-bound services | Spring Boot 3.2+ |
| Structured logging | `logging.structured.format.console: ecs` | Always — replaces Logstash encoder | Spring Boot 3.4+ |
| Actuator access | `management.endpoints.access.default` | Spring Boot 3.4+ | Spring Boot 3.4+ |
| Graceful shutdown | `server.shutdown: graceful` | Default in 3.4+; explicit for clarity | Spring Boot 3.4+ default |
| Background init | `background-bean-initialization: true` | Always — free startup speedup | Spring Boot 3.4+ |
| App identity | `spring.application.name` + `version` | Always — required for observability | All |
| Circuit breaker | Resilience4j `circuitbreaker` + `timelimiter` | Any service calling external dependencies | All |
| HTTP client timeouts | `ClientHttpRequestFactoryBuilder` | Always — never leave timeouts unset | Spring Boot 3.4+ |
| preStop + grace period | `lifecycle.preStop` + `terminationGracePeriodSeconds` | Always — prevents 502s on deploy | All |
| Pod Disruption Budget | `PodDisruptionBudget` | Always — prevents full outage on drain | All |
| Pod anti-affinity | `podAntiAffinity` | Always for HA services | All |
| CPU limit > request | `resources.limits.cpu` > `requests.cpu` | Always for JVM — prevents GC throttling | All |
| Gradle toolchains | `java { toolchain { languageVersion = 21 } }` | Always — pins JDK version | Gradle 8.5+ |
| Gradle caching | `gradle.properties` + CI cache | Always | Gradle 8+ |
| Non-root container | Dockerfile + securityContext | Always in production | All |
| Read-only filesystem | `readOnlyRootFilesystem: true` | Production; requires emptyDir mounts | All |
| Secrets management | AWS Secrets Manager + Pod Identity | Always — never plaintext secrets | EKS 1.24+ |

---

## References

| Source | URL |
|---|---|
| Spring Boot 3.4 Release Notes | https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.4-Release-Notes |
| Spring Boot — Structured Logging (3.4) | https://spring.io/blog/2024/08/23/structured-logging-in-spring-boot-3-4 |
| Spring Boot Docs — Common Application Properties | https://docs.spring.io/spring-boot/appendix/application-properties/index.html |
| Spring Boot Docs — Graceful Shutdown | https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html |
| Oracle JDK 21 Release Notes | https://www.oracle.com/java/technologies/javase/21-relnote-issues.html |
| Oracle JVM Options Reference (Java 21) | https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html |
| JEP 439 — Generational ZGC (Java 21) | https://openjdk.org/jeps/439 |
| JEP 491 — Virtual Threads without Pinning (Java 24) | https://openjdk.org/jeps/491 |
| HikariCP Configuration Reference | https://github.com/brettwooldridge/HikariCP#gear-configuration-knobs-baby |
| Gradle 8.5 Release Notes — Java 21 full support | https://docs.gradle.org/8.5/release-notes.html |
| Gradle Performance Guide (official) | https://docs.gradle.org/current/userguide/performance.html |
| Snyk — 10 Kubernetes Security Context Settings | https://snyk.io/blog/10-kubernetes-security-context-settings-you-should-understand/ |
| AWS Docs — EKS Pod Identity | https://docs.aws.amazon.com/eks/latest/userguide/pod-identities.html |
| Spring Cloud AWS — Secrets Manager | https://docs.awspring.io/spring-cloud-aws/docs/3.x/reference/html/index.html#secrets-manager |
| Resilience4j Spring Boot 3 docs | https://resilience4j.readme.io/docs/getting-started-3 |
| Spring Boot 3.4 — ClientHttpRequestFactoryBuilder | https://docs.spring.io/spring-boot/reference/io/rest-client.html |
| Kubernetes — Pod Disruption Budgets | https://kubernetes.io/docs/tasks/run-application/configure-pdb/ |
| Kubernetes — Pod Lifecycle & preStop | https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#pod-termination |
| AWS Builders Library — Timeouts, retries, and backoff | https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/ |
| AWS EKS — Topology Aware Routing | https://aws.amazon.com/blogs/containers/exploring-the-effect-of-topology-aware-hints-on-network-traffic-in-amazon-elastic-kubernetes-service/ |
| Kubernetes 1.33 — Topology Aware Routing GA | https://kubernetes.io/docs/concepts/services-networking/topology-aware-routing/ |
| Spring Boot — HTTP/2 configuration | https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.embedded-container.configure-http2 |
| Karpenter — Disruption Budgets | https://karpenter.sh/docs/concepts/disruption/ |

---

## 11. HTTP/2 — Free Throughput Improvement

HTTP/2 multiplexes multiple requests over a single TCP connection, eliminating head-of-line blocking and reducing connection overhead. On EKS, where pods talk to each other over internal load balancers, this is a free latency and throughput win.

### Enable h2c (HTTP/2 cleartext) — for pod-to-pod traffic behind ALB

ALB terminates TLS, so pods communicate over plain HTTP internally. Use h2c:

```yaml
server:
  http2:
    enabled: true   # Spring Boot auto-selects h2c when TLS is not configured
```

No code changes required. Tomcat 10.1 (Spring Boot 3.x default) supports h2c natively.

**Benefits**: multiplexed streams, header compression (HPACK), reduced TCP connection overhead between microservices.

**When NOT to use**: if your service is behind a proxy that doesn't support HTTP/2 upgrade (e.g., older Nginx configs). Test with `curl --http2-prior-knowledge`.

---

## 12. Caching Strategy — L1 + L2

Caching is one of the highest-leverage optimizations. A well-designed cache layer reduces DB load, cuts latency, and improves throughput with minimal code change.

### L1: In-process cache with Caffeine (sub-millisecond)

```groovy
implementation 'com.github.ben-manes.caffeine:caffeine'
```

```yaml
spring:
  cache:
    type: caffeine
    caffeine:
      spec: maximumSize=1000,expireAfterWrite=60s
```

```java
@Cacheable("products")
public Product findById(Long id) { ... }

@CacheEvict(value = "products", key = "#id")
public void update(Long id, Product p) { ... }
```

### L2: Distributed cache with Redis (cross-pod consistency)

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST}
      port: 6379
      timeout: 500ms          # never let Redis block your request thread
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
          min-idle: 2
          max-wait: 200ms
  cache:
    redis:
      time-to-live: 300s      # TTL for all Redis cache entries
      cache-null-values: false # don't cache null — prevents stale nulls masking DB errors
```

### L1 + L2 together (two-level cache)

Use Caffeine as L1 (hot, local, sub-ms) and Redis as L2 (warm, shared, ~1ms). On a cache miss in L1, check L2 before hitting the DB. On L2 hit, populate L1.

```java
@Bean
public CacheManager cacheManager(RedisConnectionFactory redis) {
    // L1: Caffeine
    CaffeineCacheManager l1 = new CaffeineCacheManager();
    l1.setCaffeine(Caffeine.newBuilder().maximumSize(500).expireAfterWrite(30, SECONDS));

    // L2: Redis
    RedisCacheManager l2 = RedisCacheManager.builder(redis)
        .cacheDefaults(RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(5))
            .disableCachingNullValues())
        .build();

    return new CompositeCacheManager(l1, l2);
}
```

### Cache sizing guidance

| Data type | L1 (Caffeine) | L2 (Redis) | TTL |
|---|---|---|---|
| Reference data (countries, configs) | Yes | Yes | Hours |
| User session / auth tokens | No | Yes | Minutes |
| Hot query results | Yes | Yes | 30–60s |
| Large payloads (>100KB) | No | Yes | Minutes |
| Frequently mutated data | No | No | — |

---

## 13. Kubernetes Probe Tuning — Correct Configuration

Probes are frequently misconfigured and cause either cascading restarts (liveness too aggressive) or slow rollouts (readiness too lenient).

### Three-probe pattern (Spring Boot 3.x)

```yaml
containers:
  - name: myapp
    # startupProbe: gives the app time to boot before liveness/readiness kick in
    # Without this, a slow-starting app gets killed in a restart loop
    startupProbe:
      httpGet:
        path: /actuator/health/liveness
        port: 8080
      failureThreshold: 30      # 30 × 10s = 5 minutes max startup time
      periodSeconds: 10

    # livenessProbe: restarts the pod if the JVM is deadlocked or unresponsive
    # NEVER point at /actuator/health — aggregate health includes DB/Redis checks
    # A DB outage would restart all pods simultaneously → cascading failure
    livenessProbe:
      httpGet:
        path: /actuator/health/liveness
        port: 8080
      initialDelaySeconds: 0    # startupProbe handles the delay
      periodSeconds: 10
      failureThreshold: 3
      timeoutSeconds: 5

    # readinessProbe: removes pod from load balancer if it can't serve traffic
    # Safe to include downstream checks here (DB, Redis) — failure = no traffic, not restart
    readinessProbe:
      httpGet:
        path: /actuator/health/readiness
        port: 8080
      initialDelaySeconds: 0
      periodSeconds: 5
      failureThreshold: 3
      timeoutSeconds: 3
```

### Key rules

| Rule | Why |
|---|---|
| Always use `startupProbe` for Spring Boot | Prevents restart loops during slow startup (CDS, AOT, heavy bean init) |
| Liveness → `/liveness` only | Aggregate `/health` includes DB — DB outage restarts all pods |
| Readiness → `/readiness` | Can include downstream checks; failure removes from LB, not restart |
| `liveness.failureThreshold` ≥ 3 | Transient GC pause or slow response shouldn't kill the pod |
| `liveness.timeoutSeconds` ≥ 5 | GC pause can delay response; too short = false positive restart |

---

## 14. EKS Network Optimization — Topology Aware Routing

By default, Kubernetes routes service traffic to any healthy pod regardless of availability zone. On EKS with 3 AZs, ~67% of pod-to-pod traffic crosses AZ boundaries — adding ~1–3ms latency and incurring AWS inter-AZ data transfer charges (~$0.01/GB each way).

### Enable topology-aware routing (Kubernetes 1.27+ GA)

```yaml
apiVersion: v1
kind: Service
metadata:
  name: myapp
  annotations:
    service.kubernetes.io/topology-mode: "Auto"   # prefer same-AZ endpoints
spec:
  selector:
    app: myapp
  ports:
    - port: 80
      targetPort: 8080
```

**Requirements**: at least 3 replicas spread across AZs (use `topologySpreadConstraints`), and the `EndpointSlice` controller must be enabled (default on EKS 1.21+).

```yaml
# Spread pods evenly across AZs
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: topology.kubernetes.io/zone
    whenUnsatisfiable: DoNotSchedule
    labelSelector:
      matchLabels:
        app: myapp
```

**Impact**: reduces p99 latency for inter-service calls by 1–3ms, and cuts inter-AZ data transfer costs by 60–70% for high-traffic services.

---

## 15. Virtual Threads + HikariCP — The Pool Size Trap

With virtual threads enabled, Tomcat can handle thousands of concurrent requests — each blocking on a DB call. If HikariCP pool is too small, all those virtual threads queue waiting for a connection. The pool becomes the bottleneck.

### The problem

```
10,000 virtual threads → all call DB → HikariCP pool = 10 connections
→ 9,990 threads blocked waiting → latency spikes
```

### Correct sizing with virtual threads

Virtual threads change the calculus: you no longer need to limit concurrency at the thread level (threads are cheap), but you still need to limit DB connections (DB has a hard limit).

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 50       # increase from the typical 10 when using virtual threads
                                  # formula: min(db_max_conn / pod_count, 50)
      minimum-idle: 10
      connection-timeout: 5000    # reduce from 30s — fail fast if pool is exhausted
```

**Why not unlimited?** The DB server (RDS) has a hard connection limit. RDS `db.t3.medium` = 170 max connections. With 5 pods: `170 / 5 = 34` max per pod. Set `maximum-pool-size` to ~30 with headroom for admin connections.

### JDBC drivers and virtual thread pinning (Java 21–23)

On Java 21–23, JDBC drivers that use `synchronized` internally (most do) pin the carrier thread during DB calls. This reduces the effective concurrency of virtual threads back toward platform thread behavior.

Mitigations:
- **Java 24+**: JEP 491 eliminates pinning entirely — upgrade if possible
- **Java 21–23**: use async-capable drivers (R2DBC) for reactive stacks, or accept the pinning and size the pool accordingly
- Monitor with: `-Djdk.tracePinnedThreads=full` during load testing

---

## 16. JVM Memory Budget — Full Picture

A common mistake: setting `MaxRAMPercentage=75` and assuming the JVM uses only 75% of container memory. The JVM has multiple memory regions beyond the heap:

```
Container memory limit (e.g. 1GB)
├── Heap (MaxRAMPercentage=75%) = 768MB
├── Metaspace (MaxMetaspaceSize=256m) = up to 256MB
├── Code Cache (ReservedCodeCacheSize=256m) = up to 256MB
├── Thread stacks (~1MB per platform thread × thread count)
│   With virtual threads: carrier threads only (~8–16 × 1MB = ~16MB)
├── Direct memory (ByteBuffers, Netty, etc.)
└── JVM overhead (~50–100MB)
```

**The math**: `768 + 256 + 256 + 16 + 50 = ~1.35GB` — already over the 1GB limit.

### Correct approach: size the container for total JVM footprint

```yaml
# For a 1GB container:
-XX:MaxRAMPercentage=50.0       # heap = 512MB
-XX:MaxMetaspaceSize=128m
-XX:ReservedCodeCacheSize=128m
# Total: ~512 + 128 + 128 + 16 + 50 = ~834MB — fits with headroom

# For a 2GB container:
-XX:MaxRAMPercentage=60.0       # heap = 1228MB
-XX:MaxMetaspaceSize=256m
-XX:ReservedCodeCacheSize=256m
# Total: ~1228 + 256 + 256 + 16 + 50 = ~1806MB — fits
```

**Rule of thumb**: heap should be 50–65% of container memory, not 75%, once you account for all non-heap regions. Use 75% only for containers ≥ 4GB where non-heap overhead is proportionally smaller.

---

## 17. Gradle — Version Catalog & Dependency Management

### Version Catalog (`gradle/libs.versions.toml`)

Centralizes all dependency versions — eliminates version drift across modules and makes upgrades a single-line change:

```toml
[versions]
spring-boot = "3.4.3"
java = "21"
resilience4j = "2.2.0"

[libraries]
spring-boot-starter-web = { module = "org.springframework.boot:spring-boot-starter-web" }
resilience4j-spring-boot3 = { module = "io.github.resilience4j:resilience4j-spring-boot3", version.ref = "resilience4j" }

[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
```

```groovy
// build.gradle — clean, version-free references
dependencies {
    implementation libs.spring.boot.starter.web
    implementation libs.resilience4j.spring.boot3
}
```

### Dependency vulnerability scanning

```groovy
// build.gradle
plugins {
    id 'org.owasp.dependencycheck' version '9.0.9'
}

dependencyCheck {
    failBuildOnCVSS = 7.0          // fail CI on high/critical CVEs
    suppressionFile = 'dependency-check-suppressions.xml'
}
```

Run in CI: `./gradlew dependencyCheckAnalyze`

---

## Final Checklist — Production Readiness

Use this before every production deployment:

### JVM
- [ ] `UseContainerSupport` explicit, `MaxRAMPercentage` sized for total JVM footprint (not just heap)
- [ ] `MaxMetaspaceSize` + `ReservedCodeCacheSize` set
- [ ] `ExitOnOutOfMemoryError` + `HeapDumpOnOutOfMemoryError` with path
- [ ] GC logging enabled (`-Xlog:gc*`)
- [ ] GC choice validated: G1GC for throughput, ZGC for latency

### Spring Boot
- [ ] `open-in-view: false`
- [ ] `default_batch_fetch_size` set (N+1 prevention)
- [ ] `jdbc.batch_size` + `order_inserts/updates` set
- [ ] Structured logging enabled (`logging.structured.format.console: ecs`)
- [ ] `spring.application.name` + `version` set
- [ ] Virtual threads enabled (Java 21+)
- [ ] `background-bean-initialization: true` (Spring Boot 3.4+)
- [ ] HTTP/2 enabled (`server.http2.enabled: true`)
- [ ] All HTTP clients have explicit timeouts
- [ ] Circuit breakers on all external dependencies

### Kubernetes
- [ ] Three-probe pattern: `startupProbe` + `livenessProbe` (`/liveness`) + `readinessProbe` (`/readiness`)
- [ ] `preStop` sleep + `terminationGracePeriodSeconds` > shutdown phase timeout
- [ ] `PodDisruptionBudget` defined
- [ ] Pod anti-affinity configured
- [ ] `topologySpreadConstraints` for AZ spread
- [ ] Topology-aware routing annotation on Service
- [ ] CPU limit > request (never equal for JVM)
- [ ] Memory limit = request
- [ ] `readOnlyRootFilesystem: true` with `emptyDir` mounts
- [ ] `runAsNonRoot`, `allowPrivilegeEscalation: false`, `capabilities: drop: ALL`
- [ ] `seccompProfile: RuntimeDefault`

### AWS / EKS
- [ ] Secrets in AWS Secrets Manager, loaded via `spring.config.import`
- [ ] Pod Identity (not IRSA) for IAM role assumption
- [ ] Karpenter `NodePool` disruption budget configured
- [ ] HikariCP pool sized for virtual threads + DB connection limit

### Observability
- [ ] Prometheus `ServiceMonitor` scraping `/actuator/prometheus`
- [ ] Alerts on: GC pause p99, heap %, HikariCP pending/timeout, thread count
- [ ] Slow query logging enabled (`LOG_QUERIES_SLOWER_THAN_MS: 500`)
- [ ] Correlation IDs propagated (traceId/spanId in structured logs)
- [ ] Heap dump path writable and on persistent storage or S3-synced

### Build
- [ ] Gradle 8.5+ with Java 21 toolchain
- [ ] Version catalog (`libs.versions.toml`)
- [ ] Dependency locking (`gradle.lockfile`)
- [ ] OWASP dependency check in CI
- [ ] Gradle caching in CI (`~/.gradle/caches`)
