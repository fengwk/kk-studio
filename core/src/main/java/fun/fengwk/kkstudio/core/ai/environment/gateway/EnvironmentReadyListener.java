package fun.fengwk.kkstudio.core.ai.environment.gateway;

import fun.fengwk.kkstudio.harness.tool.EnvironmentId;

/**
 * Immutable READY bridge for Environment daemon connections.
 *
 * <p>Invoked after protocol locks are released when a daemon becomes READY. Implementations own any
 * off-stack scheduling.
 */
@FunctionalInterface
public interface EnvironmentReadyListener {

  /**
   * Notifies that the environment bound to {@code environmentId} is READY for ENVIRONMENT tool
   * dispatch.
   */
  void onEnvironmentReady(EnvironmentId environmentId);
}
