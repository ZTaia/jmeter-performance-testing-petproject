# Spring Boot 4 Demo Project

This repository is a fork of [k2he/Spring-boot-4](https://github.com/k2he/Spring-boot-4), the original author's Spring Boot 4 + Java 25 demo application. It is used here, unmodified in its application behavior, as the system under test (SUT) for the JMeter performance testing work described below.

## Performance Testing (JMeter Java DSL)

These tests use [jmeter-java-dsl](https://github.com/abstracta/jmeter-java-dsl) (run as JUnit 5 tests, not raw `.jmx` files) to load-test two areas of this application: the virtual-threads-vs-traditional-threads comparison endpoints, and the versioned product API.

### Prerequisites

- Java 25 (matches `pom.xml`'s `java.version`).
- The application running locally, reachable at `http://localhost:8080` by default (override with `-Dperf.baseUrl=...` on any test run). The tests do not start the app themselves.
- The project declares `spring-boot-docker-compose` as a dependency and ships a `compose.yaml` (starts the `grafana-lgtm` observability stack). Spring Boot's Docker Compose support will attempt to run `docker compose up` for that file automatically when the app starts via `./mvnw spring-boot:run` — if Docker isn't running, startup will fail on that step. Either start Docker Desktop first, or disable the behavior with `--spring.docker.compose.enabled=false` if you don't need the Grafana/OTel stack for a given test run.
- Internet access is required for `ProductApiVersioningLoadTest`, since the product endpoints it exercises call out to the real `https://api.restful-api.dev`.

### Running the tests

```sh
# 1. Start the app (in a separate terminal)
./mvnw spring-boot:run
# ...or, without the Docker Compose stack:
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.docker.compose.enabled=false

# 2. Run a specific performance test class
./mvnw test -Dtest=VirtualThreadsComparisonTest
./mvnw test -Dtest=ProductApiVersioningLoadTest

# Optional: point at a different host, or a different product id
./mvnw test -Dtest=ProductApiVersioningLoadTest -Dperf.baseUrl=http://localhost:9090 -Dperf.productId=3
```

Each test class first calls `GET /actuator/health` in a setup thread group and asserts the response contains `"UP"` before generating any load.

### Test suite

#### VirtualThreadsComparisonTest

- **Endpoint(s):** `GET /threads/v1/traditional` (backed by a fixed 5-thread `ThreadPoolTaskExecutor`, 100-item queue) vs. `GET /threads/v1/virtual` (backed by `Executors.newVirtualThreadPerTaskExecutor()`), defined in `VirtualThreadController` / `ThreadConfig`. Neither endpoint calls any external service.
- **Type:** Load test, run at two separate concurrency profiles — not a stress test in the strict sense, since neither profile escalates load to find a breaking point. Worth flagging: the "high-concurrency" profile (50 users) exceeds the traditional endpoint's own executor capacity (5 core/max threads), so it's likely to surface queueing effects on that endpoint specifically, even though the test itself doesn't search for a failure threshold.
- **Profile:** two scenarios, each running the traditional and virtual endpoints as separate sequential test plans at matched concurrency:
  - Low-concurrency: 10 threads, ramp-up 5s, hold 20s
  - High-concurrency: 50 threads, ramp-up 10s, hold 30s
  
  (Thread count ramps up via `rampToAndHold`; each thread loops the sampler continuously for the hold duration, so total request count depends on response time, not a fixed iteration count.)
- **What it checks:** `responseAssertion` on both endpoints requires the response body contain `"completed"` (the literal text returned by `ThreadService`); asserts zero errors on both endpoints per scenario. After each scenario it prints (to stdout, not asserted) mean/p95/p99/error count for both endpoints, flags if either endpoint's p99 exceeds 2x its mean, and reports which endpoint had the lower mean response time for that run — this is a runtime observation from that specific execution, not a general claim about virtual vs. traditional threads.

#### ProductApiVersioningLoadTest

- **Endpoint(s):** `GET /api/v1/product/{id}` and `GET /api/v2.0/product/{id}` (`ProductController`). Path-segment versioning is the only active mode in this app (`WebConfiguration`); both versions call the real upstream `https://api.restful-api.dev` via `ProductRestClientService` on every sample.
- **Type:** Load test at light/normal concurrency — explicitly not a stress test. The class's own Javadoc states this is deliberately kept low-volume to avoid hammering the third-party upstream.
- **Profile:** 5 threads × 5 iterations each = 25 requests total, per version, with a 500ms `constantTimer` think-time between iterations. Product id defaults to `1`, overridable via `-Dperf.productId=`.
- **What it checks:** `responseAssertion` requires the response body contain both `"id"` and `"name"`; asserts zero errors. v1 is documented (via `ProductService`) to always omit the `data` field since it explicitly builds a trimmed response — the test does not currently assert that absence, only the presence of `id`/`name`. v2 is not asserted to contain `data`, since that depends on what the upstream actually returns for the given id.

### Viewing results

Each test run writes an HTML report per scenario under `target/jmeter-report/<label>/`, e.g.:
- `target/jmeter-report/traditional-low-concurrency/`
- `target/jmeter-report/virtual-high-concurrency/`
- `target/jmeter-report/product-v1/`
- `target/jmeter-report/product-v2/`

Open `index.html` in each folder for the full JMeter dashboard (response time graphs, percentiles, throughput). `target/` is build output and is not committed to version control.

## Original Application

This is a demo project showcasing new features in **Spring Boot 4** and **Java 25**.

### Features

#### Spring Boot 4
1. **API Versioning**  
   Flexible API versioning supporting formats like `/api/v1/product`, `/api/v2.0/product` and `X-API-Version: v1, v2, v1.0, v2.0`.
   API versioning can be enabled in Header-based, Path-based, or Query parameter-based modes (see `WebConfiguration`).
   API versioning can also able to enable from application.yml file

2. **Enhanced HTTP Service Clients**  
   Modern HTTP clients for easier and more robust REST integrations.
   - It enables to define an HTTP service through a Java interface with @HttpExchange-annotated methods. (see `ProductRestClientService`)
   - Used sample APIs on https://restful-api.dev/ 
3. **Null Safety with JSpecify**  
   Improved null-safety using [JSpecify](https://jspecify.dev/) annotations.

4. **OpenTelemetry Integration**  
   Distributed tracing and observability with [OpenTelemetry](https://opentelemetry.io/).
   View traces in Grafana:
    - Open http://localhost:3000
    - Go to Drilldown
    - View Logs, Metrics, Traces
   ![img.png](img.png)
5. **Spring Boot Docker Compose**  
   Native support for running and managing services with Docker Compose.

#### Java 25
- **Virtual Threads**  
  Utilizes Java 25's virtual threads for lightweight, scalable concurrency.
  To observe the performance difference, you can execute 200 GET requests to both endpoints and generate an Apache Bench (ab) benchmark report. Example commands:

    1. `ab -n 200 -c 200 -m GET "http://localhost:8080/threads/v1/traditional"`
    2. `ab -n 200 -c 200 -m GET "http://localhost:8080/threads/v1/virtual"`

  Use Google Gemini to analyze and visualize the benchmark results as below:
  ![img_1.png](img_1.png)

### Getting Started

1. **Build the project:**
   ```sh
   ./mvnw clean package
   ```
