package fun.fengwk.kkstudio.core.environment.gateway;

/**
 * Immutable READY bridge for Environment daemon connections.
 *
 * <p>Invoked after protocol locks are released when a daemon becomes READY. Implementations own any
 * off-stack scheduling.
 */
@FunctionalInterface
public interface EnvironmentReadyListener {

  /** Notifies that {@code environmentName} is READY for ENVIRONMENT tool dispatch. */
  void onEnvironmentReady(String environmentName);
}
