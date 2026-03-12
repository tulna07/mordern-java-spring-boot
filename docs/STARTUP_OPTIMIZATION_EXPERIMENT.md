# Startup Optimization Experiment — Results & Analysis

**Application:** demo-virtual-thread  
**Stack:** Spring Boot 4.0.3 · Java 25.0.2 · JPA + Redis + Kafka + PostgreSQL 17  
**Environment:** Docker Compose on Linux (single host)

---

## Experiment Design

Two experiments were run:

**Experiment A** — Config-only properties tested against a warm infrastructure baseline:
```yaml
spring:
  jpa:
    open-in-view: false
  main:
    lazy-initialization: true
```

**Experiment B** — AOT Cache (Java 24+ JVM feature) tested against the optimized config baseline.

Each run used `docker compose up -d --force-recreate app`. Startup time taken from:
```
Started DemoApplication in X seconds (process running for Y)
```

---

## All Runs — Measured Results

All times from actual `docker compose logs app` output.

| Run | Infrastructure | Image / Config | Startup time | WebAppContext init |
|---|---|---|---|---|
| 1 | Cold (first boot) | No optimizations | **86.9s** | 35,554ms |
| 2 | Warm | `open-in-view: false` + `lazy-init: true` | **22.3s** | 7,294ms |
| 3 | Warm | No optimizations (reverted) | **27.6s** | 7,582ms |
| 4 | Warm | `open-in-view: false` + `lazy-init: true` | **22.9s** | 9,239ms |
| 5 | Warm | AOT Cache (`book-aot-cache` image) | **9.7s** | 4,689ms |

---

## Experiment A — Config Properties

**Apples-to-apples (warm infra, Runs 3 vs 4):**

| | Without | With | Saving |
|---|---|---|---|
| Startup time | 27.6s | 22.9s | **−4.7s (−17%)** |

### `spring.jpa.open-in-view: false`

Removes `JpaWebConfiguration` and `OpenEntityManagerInViewInterceptor` from the context. No functional impact on REST APIs. Spring Boot logs a warning when not explicitly set — confirmed eliminated in Run 2 and Run 4 logs.

### `spring.main.lazy-initialization: true`

Defers bean creation until first use. Defers out of the startup path:
- Hibernate `EntityManagerFactory`
- HikariCP connection pool
- Kafka consumer bootstrap
- Redis `LettuceConnectionFactory` pool
- AOP proxies for `@Transactional`, `@Cacheable`, `@KafkaListener`

From [Spring Boot official docs](https://docs.spring.io/spring-boot/reference/using/spring-beans-and-dependency-injection.html):
> "A disadvantage of lazy initialization is that it can delay the discovery of a problem with the application."

**Trade-offs:**

| Concern | Detail |
|---|---|
| First-request latency | First call to each endpoint triggers bean init — can add 1–5s |
| Startup error masking | Misconfigured beans fail on first use, not at startup |
| Readiness probe gap | App reports "Started" before connections are established |

**Mitigation:** Use `readinessProbe` pointing at `/actuator/health` — triggers lazy init of datasource, Redis, and Kafka health indicators before traffic is routed.

---

## Experiment B — AOT Cache

**Apples-to-apples (warm infra, Run 4 vs Run 5):**

| | Without AOT Cache | With AOT Cache | Saving |
|---|---|---|---|
| Startup time | 22.9s | **9.7s** | **−13.2s (−57%)** |
| WebAppContext init | 9,239ms | **4,689ms** | **−49%** |

### What AOT Cache does

AOT Cache (Java 24+, [JEP 483](https://openjdk.org/jeps/483)) pre-processes and stores class metadata, loaded/linked classes, and ahead-of-time compiled methods in a `.aot` file during a training run at build time. On subsequent starts, the JVM skips re-parsing that work.

The training run generated a **116 MB** `app.aot` file (122,617,856 bytes) baked into the image.

From [Spring Boot 4.0.3 official docs](https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html):
> "You have to use the cache file with the extracted form of the application, otherwise it has no effect."

### Dockerfile used (`Dockerfile.aot-cache`)

Three stages: build → training run (generates `app.aot`) → runtime (uses `app.aot`).

### Trade-offs

| Concern | Detail |
|---|---|
| Image size | +122 MB larger than baseline — the `app.aot` file (116 MB) is baked into the image (`book`: 315 MB → `book-aot-cache`: 437 MB) |
| `.aot` file is JVM-version-specific | Rebuild required on JDK upgrade |
| Classpath must match between training and production | Any dependency change requires regenerating the cache |
| Training run needs app to start partially | DB connection failure during training is expected and handled with `|| true` |

---

## Full Comparison (warm infra)

| Optimization | Startup time | vs baseline | Image size |
|---|---|---|---|
| No optimizations (baseline) | 27.6s | — | 315 MB |
| Config only (`open-in-view` + `lazy-init`) | 22.9s | −17% | 315 MB |
| AOT Cache (on top of config) | **9.7s** | **−65%** | **437 MB (+122 MB)** |

---

## Cold vs Warm Infrastructure

Run 1 (86.9s) vs Run 3 (27.6s) — same code, no config changes. The 59s difference was entirely infrastructure state:

- **Cold:** Kafka KRaft coordinator still electing → repeated `NOT_COORDINATOR` retries blocked startup ~50s
- **Warm:** Kafka coordinator ready, OS page cache hot, JAR layers in memory

**Production implication:** Rolling restarts always hit warm infra. Full cluster restarts (outage recovery) hit cold infra — size readiness probe timeouts accordingly.

---

## Observed Warnings

| Warning | Status |
|---|---|
| `open-in-view is enabled by default` | ✅ Eliminated by `open-in-view: false` |
| `PostgreSQLDialect does not need to be specified` | ⚠️ Still present — remove `spring.jpa.properties.hibernate.dialect` to fix |
| `Multiple Spring Data modules found` | ℹ️ Expected — JPA + Redis on classpath, resolved correctly via strict mode |
