package fun.fengwk.kkstudio.platform.storage.error;

/**
 * 受管资源读取超过冻结的绝对截止时间：S3 getObject 握手、响应体读取或窗口扫描任一阶段超时。
 *
 * <p>与普通 IO 失败区分，便于读取边界给出 "超时" 而不是 "内容非法" 的结论；触发时底层连接已被中止（abort），不会继续排空剩余响应体。
 *
 * @author fengwk
 */
public class StorageReadTimeoutException extends RuntimeException {

  public StorageReadTimeoutException(String message) {
    super(message);
  }

  public StorageReadTimeoutException(String message, Throwable cause) {
    super(message, cause);
  }
}
