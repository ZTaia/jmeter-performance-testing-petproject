package com.example.springboot4.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static us.abstracta.jmeter.javadsl.JmeterDsl.httpSampler;
import static us.abstracta.jmeter.javadsl.JmeterDsl.htmlReporter;
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
 * Compares {@code /threads/v1/traditional} (fixed 5-thread pool) against {@code
 * /threads/v1/virtual} (virtual-thread-per-task) from {@code VirtualThreadController} /
 * {@code ThreadConfig}, at matched concurrency, to see where virtual threads pull ahead as
 * concurrency increases. Neither endpoint calls out to any external service.
 *
 * <p>Requires the app running locally (default {@code http://localhost:8080}, override with
 * {@code -Dperf.baseUrl=...}). Load levels below are starting points, not tuned targets — adjust
 * threads/duration to taste.
 */
public class VirtualThreadsComparisonTest {

  private static final String BASE_URL =
      System.getProperty("perf.baseUrl", "http://localhost:8080");
  private static final String TRADITIONAL_PATH = "/threads/v1/traditional";
  private static final String VIRTUAL_PATH = "/threads/v1/virtual";

  @Test
  void comparesTraditionalAndVirtualThreadsAtLowConcurrency() throws IOException {
    runComparison("low-concurrency", 10, Duration.ofSeconds(5), Duration.ofSeconds(20));
  }

  @Test
  void comparesTraditionalAndVirtualThreadsAtHighConcurrency() throws IOException {
    runComparison("high-concurrency", 50, Duration.ofSeconds(10), Duration.ofSeconds(30));
  }

  private void runComparison(String scenarioLabel, int threads, Duration rampUp, Duration holdFor)
      throws IOException {
    TestPlanStats traditionalStats =
        runEndpointLoad("traditional-" + scenarioLabel, TRADITIONAL_PATH, threads, rampUp, holdFor);
    TestPlanStats virtualStats =
        runEndpointLoad("virtual-" + scenarioLabel, VIRTUAL_PATH, threads, rampUp, holdFor);

    assertEquals(0, traditionalStats.overall().errorsCount(), "traditional endpoint had errors");
    assertEquals(0, virtualStats.overall().errorsCount(), "virtual endpoint had errors");

    printComparison(scenarioLabel, threads, traditionalStats, virtualStats);
  }

  private TestPlanStats runEndpointLoad(
      String label, String path, int threads, Duration rampUp, Duration holdFor)
      throws IOException {
    return testPlan(
            setupThreadGroup(
                httpSampler("health-check", BASE_URL + "/actuator/health")
                    .children(responseAssertion().containsSubstrings("UP"))),
            threadGroup(label)
                .rampToAndHold(threads, rampUp, holdFor)
                .children(
                    httpSampler(label, BASE_URL + path)
                        .children(responseAssertion().containsSubstrings("completed"))),
            htmlReporter("target/jmeter-report/" + label))
        .run();
  }

  private void printComparison(
      String scenarioLabel, int threads, TestPlanStats traditional, TestPlanStats virtual) {
    StatsSummary t = traditional.overall();
    StatsSummary v = virtual.overall();

    System.out.printf(
        "%n=== Virtual Threads Comparison: %s (%d concurrent users) ===%n",
        scenarioLabel, threads);
    printSummary("traditional", t);
    printSummary("virtual", v);

    Duration tP99 = t.sampleTime().perc99();
    Duration tMean = t.sampleTime().mean();
    Duration vP99 = v.sampleTime().perc99();
    Duration vMean = v.sampleTime().mean();

    if (tP99.toMillis() > tMean.toMillis() * 2) {
      System.out.println(
          "NOTE: traditional p99 is more than 2x its mean — possible thread-pool queueing"
              + " under this concurrency.");
    }
    if (vP99.toMillis() > vMean.toMillis() * 2) {
      System.out.println(
          "NOTE: virtual p99 is more than 2x its mean — investigate before trusting the"
              + " average alone.");
    }

    String faster = vMean.compareTo(tMean) < 0 ? "virtual" : "traditional";
    System.out.printf(
        "At %d concurrent users, %s threads had the lower mean response time (%s vs %s).%n",
        threads, faster, vMean, tMean);
  }

  private void printSummary(String name, StatsSummary stats) {
    System.out.printf(
        "  %-12s samples=%d errors=%d mean=%s p95=%s p99=%s%n",
        name,
        stats.samplesCount(),
        stats.errorsCount(),
        stats.sampleTime().mean(),
        stats.sampleTime().perc95(),
        stats.sampleTime().perc99());
  }
}
