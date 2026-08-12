package fun.fengwk.kkstudio.core.storage.error;

/**
 * 存储状态冲突：并发去重解析在重试上限内仍未收敛。
 *
 * <p>Web 层映射为 409，调用方可重试 complete。
 *
 * @author fengwk
 */
public class StorageConflictException extends RuntimeException {

  public StorageConflictException(String message) {
    super(message);
  }
}
