package fun.fengwk.kkstudio.harness.runtime.session;

/** 持久化树不满足同 Session、无循环且连续的结构约束。 */
public class InvalidSessionTreeException extends RuntimeException {
  public InvalidSessionTreeException(String message) {
    super(message);
  }
}
