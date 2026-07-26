package fun.fengwk.kkstudio.core.harness.thread.command;

import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.thread.HarnessThread;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;

import java.time.Instant;

/**
 * Bootstrap helper for final-schema tests: Threads are created UNBOUND and only become usable after
 * an explicit bootstrap that creates the Session/ROOT/RUNTIME_CONFIG bundle and binds the head.
 */
public final class TestThreads {
  private TestThreads() {}

  /** UNBOUND Thread + freshly bootstrapped Session bundle, with the post-bootstrap epoch. */
  public record Bootstrapped(
      long threadId,
      long executionEpoch,
      long sessionId,
      long rootEntryId,
      long configEntryId,
      HarnessThread thread) {}

  public static Bootstrapped bootstrap(
      ThreadCommandTransactions transactions, String title, Instant now) {
    return bootstrap(transactions, title, TestRuntimeConfigs.bootstrap(), now);
  }

  public static Bootstrapped bootstrap(
      ThreadCommandTransactions transactions,
      String title,
      RuntimeConfigSnapshot initialConfig,
      Instant now) {
    HarnessThread unbound = transactions.createThread(now);
    ThreadCommandTransactions.BootstrapResult result =
        transactions.bootstrapThread(
            unbound.id(), unbound.executionEpoch(), title, initialConfig, now);
    return new Bootstrapped(
        result.thread().id(),
        result.thread().executionEpoch(),
        result.session().id(),
        result.rootEntry().id(),
        result.configEntry().id(),
        result.thread());
  }
}
