# Spring Boot + Gradle on AWS EKS — Production Optimization Beyond JVM Startup

> **Scope**: This document covers production-grade optimizations *beyond* JVM startup time (which is covered in `JVM_STARTUP_OPTIMIZATION.md`). Topics include container image efficiency, runtime concurrency, JVM memory/GC tuning, health probes, observability, and EKS-level scaling.  
> **Sources**: Spring Boot 4.x official docs, spring.io engineering blog, OpenJDK JEP docs, GraalVM docs, AWS official blogs, InfoQ, Baeldung (reviewed), danvega.dev  
> **Build tool**: Gradle  
> **Last reviewed**: March 2026

---

## 1. Efficient Container Images

### Why It Matters

A fat JAR copied into a single Docker layer means every code change rebuilds and re-pushes the entire image — including all dependencies (which rarely change). On EKS, this means slower ECR pulls on every new node, longer CI pipelines, and wasted bandwidth.

### Layered JARs (Official Spring Boot Approach)

Spring Boot's `jarmode=tools` extracts the JAR into stable layers that Docker can cache independently. From the official Spring Boot 4.x docs, the recommended Dockerfile structure is:

```dockerfile
# ---- Stage 1: Extract layers ----
FROM bellsoft/liberica-openjre-debian:21-cds AS builder
WORKDIR /builder
COPY build/libs/*.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---- Stage 2: Runtime ----
FROM bellsoft/liberica-openjre-debian:21-cds
WORKDIR /application
# Each COPY is a separate Docker layer — only changed layers are re-pulled
COPY --from=builder /builder/extracted/dependencies/ ./
COPY --from=builder /builder/extracted/spring-boot-loader/ ./
COPY --from=builder /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder /builder/extracted/application/ ./
ENTRYPOINT ["java", "-jar", "application.jar"]
```

**Layer order matters**: dependencies (rarely change) go first, application code (changes every build) goes last. Docker only re-uploads the changed layers.

### What the Layers Contain

| Layer | Contents | Change Frequency |
|---|---|---|
| `dependencies` | Third-party JARs (stable releases) | Rarely |
| `spring-boot-loader` | Spring Boot launcher | Rarely |
| `snapshot-dependencies` | SNAPSHOT JARs | Occasionally |
| `application` | Your compiled classes + resources | Every build |

### Gradle: Ensure Layered JAR is Enabled

Layered JARs are enabled by default in Spring Boot 2.3+. Verify in `build.gradle`:

```groovy
tasks.named("bootJar") {
    layered {
        enabled = true  // default: true
    }
}
```

### Trade-offs

| Benefit | Cost |
|---|---|
| Faster CI/CD (only changed layers pushed) | Slightly more complex Dockerfile |
| Faster ECR pulls on new EKS nodes | None significant |
| AOT Cache / CDS compatible layout | |

---

## 2. Virtual Threads (Project Loom)

### What It Is

Virtual threads (JEP 444, finalized in Java 21) are lightweight threads managed by the JVM, not the OS. They are designed for I/O-bound workloads — the dominant pattern in Spring Boot microservices (DB calls, HTTP calls, queue reads).

Spring Boot 3.2+ enables virtual threads for Tomcat, Jetty, and `@Async` with a single property.

### The Problem They Solve

Traditional platform threads are expensive OS resources. A typical Spring Boot app with a Tomcat thread pool of 200 threads can handle 200 concurrent blocking requests. With virtual threads, the JVM can schedule millions of lightweight threads on a small pool of carrier (OS) threads — blocking I/O simply parks the virtual thread and frees the carrier for another task.

### How to Enable (Spring Boot 3.2+, Java 21+)

In `application.yml`:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

That's all. Spring Boot automatically configures:
- Tomcat to use a virtual thread executor
- `@Async` tasks to use virtual threads
- Scheduled tasks to use virtual threads

### Known Issue: Thread Pinning (Java 21–23)

On Java 21–23, virtual threads are **pinned** to their carrier OS thread when they enter a `synchronized` block or call native code. A pinned virtual thread blocks its carrier, defeating the purpose.

**Affected in Spring Boot ecosystem (Java 21–23)**:
- `synchronized` blocks in older JDBC drivers
- Some Hibernate internals
- Some third-party libraries

**Resolved in Java 24** (JEP 491 — "Synchronize Virtual Threads without Pinning"): virtual threads no longer pin when entering `synchronized` blocks. This is a major improvement for production use.

**Detection**: Run with `-Djdk.tracePinnedThreads=full` to log pinning events.

**Mitigation on Java 21–23**: Replace `synchronized` with `ReentrantLock` in your own code. For third-party libraries, upgrade to versions that have addressed pinning.

### When Virtual Threads Help vs. Don't Help

| Workload | Virtual Threads Benefit |
|---|---|
| High-concurrency I/O (DB, HTTP, queues) | ✅ Significant — more concurrent requests per pod |
| CPU-bound computation | ❌ No benefit — CPU is the bottleneck, not threads |
| Low-concurrency services | ⚠️ Minimal — thread pool was never the bottleneck |

### Trade-offs

| Benefit | Cost |
|---|---|
| Higher throughput for I/O-bound workloads | Thread pinning on Java 21–23 (resolved in Java 24) |
| Simpler code than reactive (no WebFlux needed) | Some libraries not yet virtual-thread-aware |
| Single property to enable | CPU-bound workloads see no improvement |
| Reduces need to tune Tomcat thread pool size | |

---

## 3. JVM Memory and GC Tuning for Containers

### Container-Aware JVM Flags (Always Required)

Without these flags, the JVM reads host machine memory/CPU instead of container limits — leading to OOMKilled pods or under-utilization:

```bash
java \
  -XX:+UseContainerSupport \      # reads cgroup limits (default: on in Java 11+)
  -XX:MaxRAMPercentage=75.0 \     # heap = 75% of container memory limit
  -XX:InitialRAMPercentage=50.0 \ # start heap at 50% to reduce GC pressure at startup
  -jar application.jar
```

> `UseContainerSupport` is on by default in Java 11+, but explicitly setting it is a good defensive practice.

### Choosing a Garbage Collector

| GC | Best For | Tradeoff |
|---|---|---|
| **G1GC** (default Java 9+) | General-purpose, balanced throughput/latency | Good default; no action needed |
| **ZGC** (Java 15+ production) | Latency-sensitive services; sub-millisecond pauses | Slightly higher CPU overhead; larger heap footprint |
| **Parallel GC** | Batch/throughput-maximizing workloads | High pause times; not suitable for interactive services |

**For most Spring Boot microservices on EKS**: G1GC is the right default. Switch to ZGC only if you have measured GC pause latency problems.

Enable ZGC:

```bash
java -XX:+UseZGC -XX:MaxRAMPercentage=75.0 -jar application.jar
```

### Heap Sizing in Kubernetes

Setting `-Xms` and `-Xmx` to the same value prevents heap resizing overhead but also prevents the JVM from releasing memory back to the OS. In Kubernetes, this makes memory usage appear flat and can confuse HPA memory-based scaling.

**Recommended approach**: Use `MaxRAMPercentage` instead of fixed `-Xmx`, and let the JVM manage heap growth:

```bash
# Preferred for EKS
-XX:MaxRAMPercentage=75.0

# Avoid unless you have a specific reason
# -Xms512m -Xmx512m
```

### Trade-offs

| Benefit | Cost |
|---|---|
| Prevents OOMKilled pods | Wrong percentages can still cause OOM |
| ZGC reduces tail latency | ZGC uses more CPU and memory than G1GC |
| Container-aware sizing avoids over/under-provisioning | Requires profiling to tune correctly |

---

## 4. Health Probes — Correct Configuration

### Why This Is Critical

Misconfigured health probes are one of the most common causes of production incidents on Kubernetes:
- **Liveness probe on `/actuator/health`** (the aggregate endpoint) will restart pods if *any* health indicator fails — including non-critical ones like a downstream service being slow. This can cause cascading restarts across your entire fleet.
- **Missing startup probe** causes liveness probe to fire during slow JVM startup, restarting pods in a loop.

### Spring Boot Actuator Setup

Add Actuator dependency in `build.gradle`:

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
}
```

Enable Kubernetes probes (auto-enabled when running on Kubernetes; enable manually for local testing):

```yaml
# application.yml
management:
  health:
    probes:
      enabled: true
  endpoint:
    health:
      show-details: always
  endpoints:
    web:
      exposure:
        include: health, info, metrics, prometheus
```

### Correct Probe Endpoints

| Probe | Endpoint | What It Checks |
|---|---|---|
| **Liveness** | `/actuator/health/liveness` | Is the app in a broken state that requires restart? |
| **Readiness** | `/actuator/health/readiness` | Is the app ready to accept traffic? |
| **Startup** | `/actuator/health` | Is the app done starting up? |

> From the spring.io engineering blog: *"The Liveness state tells whether the internal state is valid. If Liveness is broken, this means that the application itself is in a failed state and cannot recover from it."* Do **not** wire external dependencies (DB, cache) to liveness — only to readiness.

### Production-Grade Probe Configuration

```yaml
# k8s/deployment.yaml
startupProbe:
  httpGet:
    path: /actuator/health        # broad check during startup only
    port: 8080
  failureThreshold: 30            # 30 * 3s = 90s max startup time
  periodSeconds: 3

livenessProbe:
  httpGet:
    path: /actuator/health/liveness   # NEVER use /actuator/health here
    port: 8080
  initialDelaySeconds: 0
  periodSeconds: 10
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 0
  periodSeconds: 5
  failureThreshold: 3
```

### Graceful Shutdown (Required for Zero-Downtime Deploys)

```yaml
# application.yml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

When a pod receives `SIGTERM`, Spring Boot stops accepting new requests, waits for in-flight requests to complete (up to 30s), then shuts down. Without this, rolling deployments drop in-flight requests.

Also set `terminationGracePeriodSeconds` in the Deployment to be longer than the shutdown timeout:

```yaml
spec:
  template:
    spec:
      terminationGracePeriodSeconds: 60  # > timeout-per-shutdown-phase
```

### Trade-offs

| Benefit | Cost |
|---|---|
| Prevents cascading restart storms | Requires careful probe tuning per service |
| Zero-downtime rolling deployments | Graceful shutdown adds deployment time |
| Correct traffic routing during startup/shutdown | |

---

## 5. HikariCP Connection Pool Tuning

### Why It Matters

HikariCP is Spring Boot's default connection pool. The defaults are conservative and designed for safety, not optimal throughput. On EKS with multiple pod replicas, misconfigured pool sizes can exhaust database connections or leave threads waiting unnecessarily.

### Key Properties

```yaml
# application.yml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10          # max connections per pod
      minimum-idle: 5                # keep 5 connections warm
      connection-timeout: 30000      # 30s max wait for a connection
      idle-timeout: 600000           # 10min before idle connection is closed
      max-lifetime: 1800000          # 30min max connection lifetime (< DB timeout)
      keepalive-time: 60000          # 1min keepalive ping (prevents stale connections)
```

### Sizing `maximum-pool-size` for EKS

The right pool size depends on your DB's max connections and number of pod replicas:

```
max-pool-size per pod = (DB max connections - reserved connections) / number of pod replicas
```

Example: RDS PostgreSQL with 100 max connections, 8 pod replicas, 10 reserved:
```
(100 - 10) / 8 = ~11 connections per pod
```

> **Important**: `maximum-pool-size` is per pod. With 8 replicas × 10 connections = 80 total connections to the DB. Always account for all replicas.

### `max-lifetime` Must Be Less Than DB Timeout

If `max-lifetime` exceeds the database's `wait_timeout` (MySQL) or `tcp_keepalives_idle` (PostgreSQL), the DB closes the connection before HikariCP does — causing `Connection is closed` errors in production.

Set `max-lifetime` to at least 30 seconds less than the DB's connection timeout.

### Trade-offs

| Benefit | Cost |
|---|---|
| Prevents DB connection exhaustion | Requires knowing DB max connections |
| Reduces connection wait latency | Under-sizing causes thread starvation |
| `keepalive-time` prevents stale connections on EKS | Slightly more DB load from keepalive pings |

---

## 6. Observability — Metrics, Tracing, Logging

### Why It Matters on EKS

Without proper observability, diagnosing issues across multiple pods and services is guesswork. Spring Boot 3+ ships with Micrometer as the observability facade — it supports metrics, distributed tracing, and structured logging through a unified API.

### Metrics with Micrometer + Prometheus

Add dependencies in `build.gradle`:

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'io.micrometer:micrometer-registry-prometheus'
}
```

Expose the Prometheus endpoint:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, prometheus, info
  metrics:
    export:
      prometheus:
        enabled: true
```

Scrape with Prometheus on EKS using a `ServiceMonitor` (if using Prometheus Operator):

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: myapp
spec:
  selector:
    matchLabels:
      app: myapp
  endpoints:
    - port: http
      path: /actuator/prometheus
      interval: 15s
```

### Distributed Tracing with Micrometer + OpenTelemetry

Spring Boot 3+ uses Micrometer Tracing as the tracing facade. From the spring.io blog (Oct 2024): *"Micrometer Observation provides a unified observability API that simplifies instrumentation and publishing of the collected data."*

Add dependencies:

```groovy
dependencies {
    implementation 'io.micrometer:micrometer-tracing-bridge-otel'
    implementation 'io.opentelemetry:opentelemetry-exporter-otlp'
}
```

Configure OTLP export to AWS Distro for OpenTelemetry (ADOT) or any OTel Collector:

```yaml
management:
  tracing:
    sampling:
      probability: 0.1   # 10% sampling in production; 1.0 for debug
  otlp:
    tracing:
      endpoint: http://otel-collector:4318/v1/traces
```

> **Note**: For GraalVM native images, use Micrometer Tracing (not the Java agent) — Java agents cannot be used with native executables.

### Structured Logging

Use structured JSON logging for EKS + CloudWatch Logs Insights or any log aggregation system:

```groovy
// build.gradle
dependencies {
    implementation 'net.logstash.logback:logstash-logback-encoder:7.x'
}
```

```xml
<!-- src/main/resources/logback-spring.xml -->
<configuration>
  <springProfile name="!local">
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
      <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
    </appender>
    <root level="INFO">
      <appender-ref ref="JSON"/>
    </root>
  </springProfile>
</configuration>
```

### Trade-offs

| Benefit | Cost |
|---|---|
| Unified API (Micrometer) works with multiple backends | Sampling configuration requires tuning |
| Trace IDs propagated automatically across services | OTLP exporter adds slight latency overhead |
| Structured logs enable fast querying in CloudWatch | JSON logs are larger than plain text |
| Native image compatible (Micrometer Tracing) | Java agent approach doesn't work with native |

---

## 7. EKS Autoscaling — HPA, Karpenter, KEDA

### HPA (Horizontal Pod Autoscaler) — Pod-Level Scaling

HPA scales pod replicas based on CPU/memory metrics. For Java, **CPU-based HPA is unreliable** because JVM CPU usage is spiky during startup and GC, not proportional to actual load.

**Better approach**: Scale on custom metrics (request rate, queue depth) using KEDA, or use memory-based HPA carefully.

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: myapp
  minReplicas: 2
  maxReplicas: 20
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 60   # trigger scale-out at 60% CPU
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300   # wait 5min before scaling down
      policies:
        - type: Pods
          value: 1
          periodSeconds: 60             # remove max 1 pod per minute
    scaleUp:
      stabilizationWindowSeconds: 0    # scale up immediately
```

### KEDA — Event-Driven Autoscaling (Scale to Zero)

HPA cannot scale to zero. KEDA (Kubernetes Event-Driven Autoscaling) can scale based on external metrics — SQS queue depth, Kafka lag, HTTP request rate — and scale to zero when idle.

AWS officially supports KEDA with Amazon Managed Service for Prometheus as the metrics source.

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: myapp-scaler
spec:
  scaleTargetRef:
    name: myapp
  minReplicaCount: 0    # scale to zero when idle
  maxReplicaCount: 20
  triggers:
    - type: aws-sqs-queue
      metadata:
        queueURL: https://sqs.<region>.amazonaws.com/<account>/myqueue
        queueLength: "5"   # scale up when queue depth > 5
        awsRegion: <region>
```

> **Note**: Scale-to-zero with Java requires fast startup. Combine KEDA with GraalVM Native Image or CRaC for effective scale-to-zero.

### Karpenter — Node-Level Autoscaling

Karpenter replaces the Cluster Autoscaler for node provisioning. It provisions the right node type for pending pods in under 60 seconds (vs. minutes with Cluster Autoscaler).

For Java workloads, configure Karpenter `NodePool` to prefer memory-optimized instances:

```yaml
apiVersion: karpenter.sh/v1
kind: NodePool
metadata:
  name: java-workloads
spec:
  template:
    spec:
      requirements:
        - key: karpenter.k8s.aws/instance-family
          operator: In
          values: ["m6i", "m7i", "r6i"]   # memory-optimized for JVM
        - key: karpenter.sh/capacity-type
          operator: In
          values: ["spot", "on-demand"]
  disruption:
    consolidationPolicy: WhenUnderutilized
    consolidateAfter: 30s
```

### Trade-offs

| Approach | Benefit | Cost |
|---|---|---|
| HPA (CPU) | Simple setup | Unreliable for JVM workloads |
| HPA (custom metrics) | Accurate scaling | Requires metrics pipeline |
| KEDA | Scale to zero; event-driven | Requires fast startup (Native/CRaC) |
| Karpenter | Fast node provisioning; cost savings | More complex than Cluster Autoscaler |

---

## 8. Resource Requests and Limits

### Why Getting This Right Matters

- **Requests too low**: Pod gets scheduled on an overloaded node; JVM startup CPU spike causes throttling; slow startup triggers liveness probe failure
- **Requests too high**: Nodes are underutilized; costs increase; HPA doesn't scale out when needed
- **Limits = Requests (CPU)**: CPU throttling occurs even when the node has spare capacity — avoid setting CPU limits equal to requests for JVM workloads

### Recommended Pattern for JVM Workloads

```yaml
resources:
  requests:
    cpu: "500m"      # what the pod needs at steady state
    memory: "512Mi"  # set based on actual heap + off-heap usage
  limits:
    cpu: "2000m"     # allow burst for JVM startup and GC
    memory: "512Mi"  # keep memory limit = request to prevent OOMKilled surprises
```

> **CPU limits**: Setting CPU limits too low causes JVM throttling. Many production teams set no CPU limit (only request) and rely on node-level resource management. This is a valid approach on EKS with Karpenter.

> **Memory limits**: Always set memory limit = memory request for Java. The JVM will use up to `MaxRAMPercentage` of the limit. If the pod exceeds the limit, it gets OOMKilled — which is preferable to the node running out of memory.

### Vertical Pod Autoscaler (VPA) in Recommendation Mode

VPA can observe actual resource usage and recommend right-sized requests without automatically changing them:

```yaml
apiVersion: autoscaling.k8s.io/v1
kind: VerticalPodAutoscaler
metadata:
  name: myapp-vpa
spec:
  targetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: myapp
  updatePolicy:
    updateMode: "Off"   # recommendation only — don't auto-update
```

Check recommendations with `kubectl describe vpa myapp-vpa`.

---

## Summary: Optimization Priority Order

Apply these in order — earlier items have the highest impact-to-effort ratio:

| Priority | Optimization | Effort | Impact |
|---|---|---|---|
| 1 | Layered JAR Dockerfile | Low | Faster CI/CD, faster ECR pulls |
| 2 | Correct health probes (liveness/readiness/startup) | Low | Prevents restart storms, zero-downtime deploys |
| 3 | Graceful shutdown | Low | Zero-downtime rolling deploys |
| 4 | Container-aware JVM flags (`UseContainerSupport`, `MaxRAMPercentage`) | Low | Prevents OOMKilled, correct heap sizing |
| 5 | HikariCP tuning | Low-Medium | Prevents DB connection exhaustion |
| 6 | Virtual threads (Java 21+) | Low | Higher I/O throughput per pod |
| 7 | Micrometer + Prometheus + structured logging | Medium | Observability for diagnosis and scaling decisions |
| 8 | HPA / KEDA autoscaling | Medium | Cost efficiency, handles traffic bursts |
| 9 | GC tuning (ZGC for latency-sensitive) | Medium | Reduced tail latency |
| 10 | Karpenter node autoscaling | Medium-High | Faster node provisioning, cost savings |
| 11 | Right-size resource requests/limits | Ongoing | Cost efficiency, prevents throttling |

---

## References

| Source | URL |
|---|---|
| Spring Boot Docs — Dockerfiles (Layered JARs) | https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html |
| Spring Boot Docs — Kubernetes Health Probes | https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes |
| Spring.io Blog — Liveness and Readiness Probes | https://spring.io/blog/2020/03/25/liveness-and-readiness-probes-with-spring-boot |
| Spring.io Blog — OpenTelemetry with Spring | https://spring.io/blog/2024/10/28/lets-use-opentelemetry-with-spring |
| JEP 444 — Virtual Threads (Java 21) | https://openjdk.org/jeps/444 |
| JEP 491 — Synchronize Virtual Threads without Pinning (Java 24) | https://openjdk.org/jeps/491 |
| danvega.dev — Virtual Threads Without Pinning (Java 24) | https://www.danvega.dev/blog/jdk-24-virtual-threads-without-pinning |
| AWS Blog — Autoscaling with KEDA + Amazon Managed Prometheus | https://aws.amazon.com/blogs/mt/autoscaling-kubernetes-workloads-with-keda-using-amazon-managed-service-for-prometheus-metrics/ |
| AWS Community — Scale to Zero in EKS with KEDA | https://community.aws/content/2pQdqmtX2F6doH4WNecO1aHTjhf/scale-to-zero-in-eks-with-keda |
| Microsoft Tech Blog — Hardening Spring Boot Health Probes | https://techcommunity.microsoft.com/blog/microsoftmissioncriticalblog/hardening-spring-boot-health-probes-on-aks |

---

## 9. JVM Flags Reference — Production Containers

Apply via `JAVA_TOOL_OPTIONS` environment variable in your Kubernetes Deployment (preferred — works without changing Dockerfile):

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
      -XX:+ParallelRefProcEnabled
      -XX:+DisableExplicitGC
      -XX:+AlwaysPreTouch
      -XX:+UseStringDeduplication
      -Djava.security.egd=file:/dev/./urandom
```

Or in your Dockerfile `ENTRYPOINT` if you prefer baking them in:

```dockerfile
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:InitialRAMPercentage=50.0", \
  "-XX:+UseG1GC", \
  "-XX:MaxGCPauseMillis=200", \
  "-XX:+ParallelRefProcEnabled", \
  "-XX:+DisableExplicitGC", \
  "-XX:+AlwaysPreTouch", \
  "-XX:+UseStringDeduplication", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "application.jar"]
```

### What Each Flag Does and Why

| Flag | What It Does | When to Use |
|---|---|---|
| `-XX:+UseContainerSupport` | JVM reads cgroup memory/CPU limits instead of host values | Always — default on Java 11+ but explicit is safer |
| `-XX:MaxRAMPercentage=75.0` | Heap = 75% of container memory limit | Always — replaces fixed `-Xmx` |
| `-XX:InitialRAMPercentage=50.0` | Start heap at 50% — reduces GC pressure at startup | Always with `MaxRAMPercentage` |
| `-XX:+UseG1GC` | Enables G1 garbage collector | Default on Java 9+; explicit for clarity |
| `-XX:MaxGCPauseMillis=200` | G1GC targets max 200ms pause time | Latency-sensitive services; lower = more frequent GC |
| `-XX:+ParallelRefProcEnabled` | Processes reference objects in parallel during GC | Always — reduces stop-the-world time |
| `-XX:+DisableExplicitGC` | Ignores `System.gc()` calls from libraries | Always — prevents unexpected full GC pauses |
| `-XX:+AlwaysPreTouch` | Pre-allocates heap pages at startup | Services where consistent latency matters more than startup time |
| `-XX:+UseStringDeduplication` | G1GC deduplicates identical String objects in heap | Apps with many duplicate strings (REST APIs, JSON processing) |
| `-Djava.security.egd=file:/dev/./urandom` | Faster random number generation (avoids `/dev/random` blocking) | Always in containers — `/dev/random` can block on low-entropy systems |

### For Latency-Sensitive Services: Switch to ZGC

Replace G1GC flags with:

```
-XX:+UseZGC
-XX:SoftMaxHeapSize=<75% of limit>
```

Remove `-XX:MaxGCPauseMillis` and `-XX:+UseStringDeduplication` (not applicable to ZGC).

> ZGC delivers sub-millisecond GC pauses but uses more memory than G1GC. Only switch if you have measured GC pause problems with G1GC.

### GC Logging (Enable in Production for Diagnosis)

```
-Xlog:gc*:file=/tmp/gc.log:time,uptime,level,tags:filecount=5,filesize=20m
```

Rotate into 5 files × 20MB. In EKS, redirect to stdout instead if using a log aggregator:

```
-Xlog:gc*::time,uptime,level,tags
```

---

## 10. application.yml — Production Configuration Reference

A complete, annotated production `application.yml` covering all key tuning areas:

```yaml
# ============================================================
# SERVER / TOMCAT
# ============================================================
server:
  port: 8080
  shutdown: graceful                    # drain in-flight requests on SIGTERM

  # HTTP response compression — reduces payload size over the network
  compression:
    enabled: true
    mime-types: application/json,application/xml,text/html,text/plain
    min-response-size: 2KB              # only compress responses >= 2KB

  tomcat:
    threads:
      max: 200                          # max worker threads (default 200)
                                        # with virtual threads enabled, this is irrelevant
      min-spare: 10                     # keep 10 threads warm
    accept-count: 100                   # queue depth when all threads busy
    max-connections: 8192               # max simultaneous connections (default 8192)
    connection-timeout: 20s             # drop connections idle for 20s
    keep-alive-timeout: 30s             # HTTP keep-alive timeout

# ============================================================
# SPRING LIFECYCLE
# ============================================================
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s     # wait up to 30s for in-flight requests

  # ============================================================
  # VIRTUAL THREADS (Java 21+, Spring Boot 3.2+)
  # ============================================================
  threads:
    virtual:
      enabled: true                     # enables virtual threads for Tomcat + @Async

  # ============================================================
  # DATASOURCE / HIKARICP
  # ============================================================
  datasource:
    hikari:
      maximum-pool-size: 10             # tune: (db_max_conn - reserved) / pod_replicas
      minimum-idle: 5
      connection-timeout: 30000         # 30s max wait for a connection from pool
      idle-timeout: 600000              # 10min — close idle connections
      max-lifetime: 1800000             # 30min — must be < DB connection timeout
      keepalive-time: 60000             # 1min ping to prevent stale connections on EKS
      pool-name: HikariPool-main
      leak-detection-threshold: 60000   # warn if connection held > 60s (debug aid)

  # ============================================================
  # JPA / HIBERNATE
  # ============================================================
  jpa:
    open-in-view: false                 # CRITICAL: disable OSIV — prevents holding DB
                                        # connections for the full HTTP request lifecycle
    properties:
      hibernate:
        jdbc:
          batch_size: 25                # batch inserts/updates — reduces DB round trips
          fetch_size: 50                # JDBC fetch size for queries
        order_inserts: true             # reorder inserts for batching
        order_updates: true             # reorder updates for batching
        generate_statistics: false      # disable in production (overhead)

# ============================================================
# ACTUATOR / MANAGEMENT
# ============================================================
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: when-authorized     # don't expose internals publicly
      probes:
        enabled: true                   # enables /actuator/health/liveness and /readiness
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
      probability: 0.1                  # 10% trace sampling in production

# ============================================================
# LOGGING
# ============================================================
logging:
  level:
    root: INFO
    org.springframework.web: WARN       # reduce noise from Spring MVC
    org.hibernate.SQL: WARN             # set to DEBUG only when diagnosing queries
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
```

### Key Configs Explained

**`spring.jpa.open-in-view: false`** — This is one of the most impactful settings to get right. Spring Boot enables Open Session In View (OSIV) by default, which holds a database connection open for the entire HTTP request lifecycle — including time spent rendering the response. On EKS with many concurrent requests, this exhausts your HikariCP pool fast. Always disable it in production.

**`server.compression.enabled: true`** — Compresses JSON/XML responses before sending over the network. Reduces bandwidth between your pods and clients. Has negligible CPU cost for typical API payloads.

**`hibernate.jdbc.batch_size: 25`** — Without this, Hibernate issues one SQL statement per entity in a loop. With batching, it groups them into fewer round trips. Significant throughput improvement for write-heavy services.

**`server.tomcat.threads.max`** — Only relevant when virtual threads are **disabled**. With `spring.threads.virtual.enabled=true`, Tomcat uses a virtual thread per request and this setting is effectively bypassed.

**`hikari.leak-detection-threshold`** — Not a performance setting, but invaluable in production. Logs a warning if a connection is held longer than the threshold, helping catch connection leaks before they exhaust the pool.

---

## 11. Gradle: Passing JVM Flags via `build.gradle`

For local development and CI runs, set JVM args in `build.gradle` so they apply consistently:

```groovy
// build.gradle
tasks.named("bootRun") {
    jvmArgs = [
        "-XX:+UseContainerSupport",
        "-XX:MaxRAMPercentage=75.0",
        "-XX:+UseG1GC",
        "-Djava.security.egd=file:/dev/./urandom"
    ]
}
```

For production, prefer `JAVA_TOOL_OPTIONS` in the Kubernetes Deployment (shown in section 9) — it keeps JVM flags out of the image and makes them environment-specific without rebuilding.

---

## Updated Summary Table

| Area | Config Location | Key Setting | Impact |
|---|---|---|---|
| Heap sizing | `JAVA_TOOL_OPTIONS` | `MaxRAMPercentage=75.0` | Prevents OOMKilled |
| GC pauses | `JAVA_TOOL_OPTIONS` | `MaxGCPauseMillis=200` | Reduces tail latency |
| GC overhead | `JAVA_TOOL_OPTIONS` | `ParallelRefProcEnabled` | Faster GC cycles |
| Entropy blocking | `JAVA_TOOL_OPTIONS` | `java.security.egd` | Prevents startup stalls |
| DB connection leak | `application.yml` | `open-in-view: false` | Prevents pool exhaustion |
| DB throughput | `application.yml` | `hibernate.jdbc.batch_size` | Fewer DB round trips |
| Network payload | `application.yml` | `server.compression.enabled` | Reduced bandwidth |
| Concurrency | `application.yml` | `spring.threads.virtual.enabled` | Higher I/O throughput |
| Graceful shutdown | `application.yml` | `server.shutdown: graceful` | Zero-downtime deploys |
| Pool sizing | `application.yml` | `hikari.maximum-pool-size` | Prevents DB exhaustion |

---

## References (additions)

| Source | URL |
|---|---|
| Spring Boot Docs — Common Application Properties | https://docs.spring.io/spring-boot/appendix/application-properties/index.html |
| Spring Boot Docs — Graceful Shutdown | https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html |
| Oracle JVM Options Reference | https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html |
| JEP 491 — Virtual Threads without Pinning (Java 24) | https://openjdk.org/jeps/491 |
| HikariCP Configuration Reference | https://github.com/brettwooldridge/HikariCP#gear-configuration-knobs-baby |
