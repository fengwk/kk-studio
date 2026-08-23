package fun.fengwk.kkstudio.platform.storage.error;

/**
 * 上传对象校验失败：对象大小或 SHA-256 校验和与声明不一致，或对象缺失无法复制。
 *
 * <p>Web 层映射为 409：上传保持 PENDING（或 READY 但对象未就绪），客户端重传/重试后再次 complete。
 *
 * @author fengwk
 */
public class StorageVerificationException extends RuntimeException {

  public StorageVerificationException(String message) {
    super(message);
  }
}
