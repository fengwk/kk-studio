package fun.fengwk.kkstudio.platform.cloudfs.domain.error;

/** 文件系统通用参数校验异常（文本内容、字节大小、UTF-8 编码、模式串等）。 */
public class CloudFileSystemValidationException extends CloudFileSystemException {

  public CloudFileSystemValidationException(String message) {
    super(message);
  }

  public CloudFileSystemValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
