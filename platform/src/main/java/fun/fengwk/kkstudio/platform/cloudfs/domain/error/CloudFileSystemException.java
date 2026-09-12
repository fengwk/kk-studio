package fun.fengwk.kkstudio.platform.cloudfs.domain.error;

/** Cloud File System 基础领域异常。 */
public class CloudFileSystemException extends RuntimeException {

  public CloudFileSystemException(String message) {
    super(message);
  }

  public CloudFileSystemException(String message, Throwable cause) {
    super(message, cause);
  }
}
