package fun.fengwk.kkstudio.platform.environment.gateway;

/** Registration token 无效或被拒绝时的异常。 */
public class RegistrationRejectedException extends RuntimeException {
  public RegistrationRejectedException(String message) {
    super(message);
  }
}
