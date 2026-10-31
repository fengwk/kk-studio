package fun.fengwk.kkstudio.harness.environment.server;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;

import java.util.concurrent.atomic.AtomicBoolean;

/** 有界 tombstone 中保留的退出确认。 */
final class ExitConfirmation {

  final EnvironmentCapabilityExecutionListener listener;
  final String daemonInstanceId;
  final AtomicBoolean delivered = new AtomicBoolean();

  ExitConfirmation(EnvironmentCapabilityExecutionListener listener, String daemonInstanceId) {
    this(listener, daemonInstanceId, false);
  }

  ExitConfirmation(
      EnvironmentCapabilityExecutionListener listener, String daemonInstanceId, boolean delivered) {
    this.listener = listener;
    this.daemonInstanceId = daemonInstanceId;
    this.delivered.set(delivered);
  }

  boolean claim() {
    return delivered.compareAndSet(false, true);
  }
}
