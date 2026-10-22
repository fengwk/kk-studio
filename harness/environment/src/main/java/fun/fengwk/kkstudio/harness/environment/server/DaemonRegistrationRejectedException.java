package fun.fengwk.kkstudio.harness.environment.server;

/** HELLO 注册被拒绝：daemon 不得重试，连接以 {@code REGISTRATION_REJECTED} 错误关闭。 */
public final class DaemonRegistrationRejectedException extends RuntimeException {

  public DaemonRegistrationRejectedException(String message) {
    super(message);
  }
}
