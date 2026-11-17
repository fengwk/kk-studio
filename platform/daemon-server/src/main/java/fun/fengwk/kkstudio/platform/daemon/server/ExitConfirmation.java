package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;

/** 在活动调用与有界 tombstone 间共享的退出确认状态。 */
final class ExitConfirmation {

  final EnvironmentCapabilityExecutionListener listener;
  final String daemonInstanceId;
  boolean claimed;
  boolean delivered;

  ExitConfirmation(EnvironmentCapabilityExecutionListener listener, String daemonInstanceId) {
    this.listener = listener;
    this.daemonInstanceId = daemonInstanceId;
  }

  boolean claim() {
    if (claimed || delivered) {
      return false;
    }
    claimed = true;
    return true;
  }

  void complete(boolean succeeded) {
    claimed = false;
    delivered = succeeded;
  }
}
