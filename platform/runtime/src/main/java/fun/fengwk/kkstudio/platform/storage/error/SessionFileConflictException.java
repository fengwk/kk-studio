package fun.fengwk.kkstudio.platform.storage.error;

/** Session 文件并发写入或版本冲突异常（CAS revision 不匹配或并发创建重名文件）。 */
public class SessionFileConflictException extends RuntimeException {

  public SessionFileConflictException(String message) {
    super(message);
  }

  public SessionFileConflictException(String message, Throwable cause) {
    super(message, cause);
  }
}
