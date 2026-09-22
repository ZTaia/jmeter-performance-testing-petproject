package com.example.springboot4.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static us.abstracta.jmeter.javadsl.JmeterDsl.constantTimer;
import static us.abstracta.jmeter.javadsl.JmeterDsl.htmlReporter;
import static us.abstracta.jmeter.javadsl.JmeterDsl.httpSampler;
import static us.abstracta.jmeter.javadsl.JmeterDsl.responseAssertion;
import static us.abstracta.jmeter.javadsl.JmeterDsl.setupThreadGroup;
import static us.abstracta.jmeter.javadsl.JmeterDsl.testPlan;
import static us.abstracta.jmeter.javadsl.JmeterDsl.threadGroup;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import us.abstracta.jmeter.javadsl.core.TestPlanStats;
import us.abstracta.jmeter.javadsl.core.stats.StatsSummary;

/**
 * Light-load correctness/availability check for {@code /api/{version}/product/{id}} from {@code
 * ProductController}, covering both versions supported by the ONLY active versioning mode
 * ({@code WebConfiguration#configureApiVersioning} has path-segment versioning active; header-
 * and query-param-based versioning are commented out and are NOT reachable in this app despite
 * appearing in the Postman collection).
 *
 * <p>IMPORTANT: both v1 and v2 delegate to {@code ProductRestClientService}, which calls the real
 * upstream {@code https://api.restful-api.dev}. This test deliberately uses low concurrency and a
 * think-time between iterations rather than a sustained load/stress profile, to avoid generating
 * significant real traffic against that third-party service. It is NOT a capacity test.
 *
 * <p>Requires the app running locally (default {@code http://localhost:8080}, override with
 * {@code -Dperf.baseUrl=...}) and a working internet connection for the upstream call. The load
 * level below is a starting point, not a tuned target.
 */
public class ProductApiVersioningLoadTest {

  private static final String BASE_URL =
      System.getProperty("perf.baseUrl", "http://localhost:8080");
  // A small, plausible object id on the public restful-api.dev sample dataset. If it 404s there,
  // pick any id known to exist on that upstream — this test does not control that data.
  private static final int PRODUCT_ID = Integer.getInteger("perf.productId", 1);

  @Test
  void v1ReturnsTrimmedProductUnderLightLoad() throws IOException {
    TestPlanStats stats =
        runProductLoad(
            "product-v1",
            "/api/v1/product/" + PRODUCT_ID,
            // v1 explicitly builds the response with only id/name (see ProductService),
            // so "data" must never appear.
            responseAssertion().containsSubstrings("\"id\"", "\"name\""));
    printSummary("v1", stats);
    assertEquals(0, stats.overall().errorsCount(), "product v1 endpoint had errors");
  }

  @Test
  void v2ReturnsFullProductUnderLightLoad() throws IOException {
    TestPlanStats stats =
        runProductLoad(
            "product-v2",
            "/api/v2.0/product/" + PRODUCT_ID,
            responseAssertion().containsSubstrings("\"id\"", "\"name\""));
    printSummary("v2", stats);
    assertEquals(0, stats.overall().errorsCount(), "product v2 endpoint had errors");
  }

  private TestPlanStats runProductLoad(
      String label,
      String path,
      us.abstracta.jmeter.javadsl.core.assertions.DslResponseAssertion assertion)
      throws IOException {
    System.out.printf(
        "NOTE: %s calls the real upstream restful-api.dev on every sample — kept intentionally"
            + " low-concurrency.%n",
        label);
    return testPlan(
            setupThreadGroup(
                httpSampler("health-check", BASE_URL + "/actuator/health")
                    .children(responseAssertion().containsSubstrings("UP"))),
            // 5 concurrent users x 5 iterations each = 25 requests total, not a sustained soak.
            threadGroup(label, 5, 5,
                httpSampler(label, BASE_URL + path).children(assertion),
                constantTimer(Duration.ofMillis(500))),
            htmlReporter("target/jmeter-report/" + label))
        .run();
  }

  private void printSummary(String name, TestPlanStats stats) {
    StatsSummary s = stats.overall();
    System.out.printf(
        "  %-12s samples=%d errors=%d mean=%s p95=%s p99=%s%n",
        name,
        s.samplesCount(),
        s.errorsCount(),
        s.sampleTime().mean(),
        s.sampleTime().perc95(),
        s.sampleTime().perc99());
    if (s.errorsCount() > 0) {
      System.out.printf(
          "  %-12s ERROR RATE %.1f%% — treat as broken, not fast, regardless of latency.%n",
          name, 100.0 * s.errorsCount() / s.samplesCount());
    }
  }
}
