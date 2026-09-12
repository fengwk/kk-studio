package fun.fengwk.kkstudio.platform.cloudfs.domain.error;

/** 虚拟路径格式或约束校验失败时抛出。 */
public class CloudPathValidationException extends CloudFileSystemException {

  public CloudPathValidationException(String message) {
    super(message);
  }

  public CloudPathValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
