package fun.fengwk.kkstudio.harness.runtime.session;

import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSource;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;

import java.time.Clock;
import java.util.Objects;

/**
 * Framework-free Session bootstrap orchestration.
 *
 * <p>Resolves the initial agent snapshot from live resources and creates Session / ROOT /
 * RUNTIME_CONFIG / Main Thread via {@link ThreadCommandTransactions}. Product defaults such as the
 * bootstrap agent definition id and default yolo flag are supplied by the composition root.
 */
public final class SessionCommandCoordinator {

  private final ThreadCommandTransactions transactions;
  private final RuntimeConfigSource configSource;
  private final Clock clock;

  public SessionCommandCoordinator(
      ThreadCommandTransactions transactions, RuntimeConfigSource configSource, Clock clock) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.configSource = Objects.requireNonNull(configSource, "configSource");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Creates a Session with a frozen bootstrap agent configuration.
   *
   * @param title optional session title
   * @param agentDefinitionId live agent definition used for the initial RUNTIME_CONFIG
   * @param yoloEnabled bootstrap yolo flag (typically product default when no prior config exists)
   */
  public ThreadCommandTransactions.SessionCreation createSession(
      String title, long agentDefinitionId, boolean yoloEnabled) {
    RuntimeConfigSnapshot bootstrap = configSource.resolveAgent(agentDefinitionId, yoloEnabled);
    return transactions.createSession(title, bootstrap, clock.instant());
  }
}
