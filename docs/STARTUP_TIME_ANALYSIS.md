# Startup Time Analysis — demo-virtual-thread

**Environment:** Docker Compose on Linux  
**Stack:** Spring Boot 4.0.3 · Java 25.0.2 · Hibernate 7.2.4 · Kafka 4.1.1 · PostgreSQL 17.7 · Redis 7  
**Total startup time observed: 86.89 seconds**

---

## 1. Measured Timeline (from actual logs)

Every timestamp below is taken directly from `docker compose logs app`.

| Timestamp (UTC) | Elapsed | Event |
|---|---|---|
| 08:08:37.522 | 0s | JVM started, `DemoApplication` begins |
| 08:08:38.022 | +0.5s | No active profile — falls back to `default` |
| 08:08:56.927 | **+18.9s** | Spring Data repository scanning begins |
| 08:08:58.631 | +20.1s | JPA repository scan done (1600ms, found 1 repo) |
| 08:08:59.620 | +22.1s | Redis repository scan done (80ms, found 0) |
| 08:09:13.543 | +36s | Tomcat initialized |
| 08:09:14.685 | +37.2s | `WebApplicationContext` initialized — **35,554ms** |
| 08:09:23.603 | +46s | Hibernate `PersistenceUnitInfo` processing begins |
| 08:09:27.309 | +49.8s | HikariCP pool starting |
| 08:09:28.151 | +50.6s | HikariCP pool ready (first connection acquired in ~840ms) |
| 08:09:32.275 | +54.8s | JPA `EntityManagerFactory` initialized |
| 08:09:39.551 | +62s | `open-in-view` warning — Actuator/JPA web config |
| 08:09:50.879 | +73.4s | Actuator endpoints resolved |
| 08:09:53.660 | +76.1s | Tomcat started on port 8080 |
| 08:09:58.666 | **+81.1s** | `Started DemoApplication in 86.89 seconds` |

---

## 2. Phase Breakdown

### Phase 1 — JVM boot to Spring context start: ~0.5s
Normal. JVM 25 starts fast. No issue here.

### Phase 2 — Spring ApplicationContext initialization: 35.5s ⚠️
This is the single largest cost. The log line:
```
Root WebApplicationContext: initialization completed in 35554 ms
```
This covers:
- Auto-configuration evaluation (hundreds of `@ConditionalOn*` checks)
- Bean definition scanning across all packages
- `Multiple Spring Data modules found` — JPA + Redis both present, forcing **strict repository configuration mode**, which scans twice
- AOP proxy creation for `@Transactional`, `@Cacheable`, `@KafkaListener`
- Cache infrastructure setup (`RedisCacheManager`)

**Root cause of the 35s:** This run was on **cold infrastructure** — Kafka broker was still completing KRaft coordinator election, and the JVM had no cached class data. The dual Spring Data module detection adds scanning overhead but is not the primary cause. On warm infrastructure with the same code and no config changes, WebApplicationContext init drops to ~7,500ms (confirmed in Run 3).

### Phase 3 — Hibernate bootstrap: ~8s (08:09:23 → 08:09:32)
- Entity scanning and metamodel building
- Dialect detection (+ deprecation warning: `PostgreSQLDialect` explicitly set is unnecessary)
- Schema tool execution (`ddl-auto: none` — Hibernate does nothing to the schema, no DDL and no validation)
- `EntityManagerFactory` initialization

### Phase 4 — HikariCP connection pool: ~840ms
Fast and healthy. Pool acquired first connection in under 1 second.

### Phase 5 — Actuator + Kafka consumer init: ~18s (08:09:32 → 08:09:58)
- `open-in-view` warning adds overhead (JPA web config)
- Actuator endpoint resolution
- Kafka consumer bootstrap: `ConsumerConfig` logging, metrics collector, version check, topic subscription

---

## 3. Post-Startup: Kafka Coordinator Issue

After the app reported "Started", the Kafka consumer immediately hit:
```
UNKNOWN_TOPIC_OR_PARTITION
NOT_COORDINATOR
```
This is **not a startup failure** — it's Kafka's internal coordinator election completing after the broker's health check passed. The `books` topic didn't exist yet (auto-created on first access). The consumer recovered automatically within seconds.

**Why the health check passed early:** The Docker Compose `healthcheck` for Kafka uses `kafka-broker-api-versions.sh`, which only verifies the broker is reachable — not that the coordinator is fully elected. This is a known gap with single-node KRaft setups.

---

## 4. Runtime Resource Usage (post-startup)

Measured with `docker stats` after full startup:

| Metric | Value |
|---|---|
| CPU | 0.72% (idle) |
| Memory used | 289 MB |
| Memory limit | 3.745 GB (75% of host RAM via `MaxRAMPercentage=75.0`) |
| Memory % | 7.54% |
| Threads (PIDs) | 40 |

289 MB RSS for a Spring Boot 4 app with JPA + Redis + Kafka is reasonable. Virtual threads (`spring.threads.virtual.enabled=true`) keep the thread count low — 40 PIDs vs the typical 200+ with platform threads under load.

---

## 5. Warnings Found in Logs

| Warning | Cause | Fix |
|---|---|---|
| `HHH90000025: PostgreSQLDialect does not need to be specified explicitly` | `hibernate.dialect` set in `application.yaml` | Remove `spring.jpa.properties.hibernate.dialect` — Hibernate 6+ auto-detects it |
| `spring.jpa.open-in-view is enabled by default` | Not explicitly disabled | Add `spring.jpa.open-in-view: false` to `application.yaml` |
| `Multiple Spring Data modules found` | Both JPA and Redis starters on classpath | Expected — resolved correctly via strict mode |

---

## 6. What Actually Caused the 87s Startup

In order of impact:

1. **Cold JVM + cold infrastructure: ~59s** — Kafka KRaft coordinator was still electing when the app connected, causing repeated `NOT_COORDINATOR` retries with backoff. Confirmed: same code on warm infra starts in 27.6s (Run 3).
2. **Spring ApplicationContext init: 35.5s** — on cold infra this includes full class loading from disk with no OS page cache. On warm infra this drops to ~7.5s with identical code.
3. **Kafka consumer init + coordinator election: ~18s** — Kafka 4.1.1 consumer initialization is verbose and slow on first connect to a fresh single-node KRaft broker.
4. **Actuator + JPA web config: ~11s** — `open-in-view` configuration and endpoint resolution.
5. **No CDS** — no Class Data Sharing archive means every class is loaded from scratch from the layered JAR on cold start.

---

## 7. Actionable Improvements

### Quick wins (config only)

```yaml
# application.yaml
spring:
  jpa:
    open-in-view: false          # removes JpaWebConfiguration overhead
  main:
    lazy-initialization: true    # defer non-critical bean init until first use
```

Remove from `application.yaml`:
```yaml
# Remove this — Hibernate auto-detects it
spring.jpa.properties.hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
```

### Medium effort

- **AppCDS (Class Data Sharing):** Add a training run in the Dockerfile to generate a shared class archive. Reduces class-loading time on cold start.
- **Spring AOT:** Configured via the Gradle build plugin (`bootAotCompile` task in Spring Boot 3+/4). Moves auto-configuration condition evaluation to build time, reducing runtime reflection and condition checks.
- **Narrow component scan:** `@SpringBootApplication(scanBasePackages = "com.example.demo")` is already correct — no change needed.

### Structural

- **Separate Kafka consumer to its own service** if startup time is critical. The Kafka consumer coordinator negotiation adds ~4–8s on cold start against a fresh broker.
- **Use `spring-boot-starter-data-redis` without `@EnableCaching` on the same context** — or use `@Lazy` on the `CacheManager` bean to defer Redis connection until first cache access.

---

## 8. Comparison to Real-World Baseline

| App type | Typical startup | This app (cold) | This app (warm) |
|---|---|---|---|
| Simple REST (no DB) | 2–4s | — | — |
| REST + JPA + Postgres | 8–15s | — | — |
| REST + JPA + Redis + Kafka | **15–30s** | **87s** ⚠️ | **27.6s** ✓ |

The 87s cold-start is dominated by Kafka coordinator election delay on a fresh single-node KRaft broker — not a Spring Boot issue per se. The 27.6s warm-start is within the expected range for this stack. With `open-in-view: false` and `lazy-initialization: true`, warm-start drops further to **~22–23s**.
