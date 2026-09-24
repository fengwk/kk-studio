package fun.fengwk.kkstudio.platform.storage.error;

/**
 * 受管资源读取被取消：读取线程在读取期间处于中断状态。
 *
 * <p>与超时区分，表示调用方主动取消而不是预算耗尽；触发时底层连接已被中止（abort）。本异常不修改线程中断状态，调用方仍可观察中断位继续取消。
 *
 * @author fengwk
 */
public class StorageReadInterruptedException extends RuntimeException {

  public StorageReadInterruptedException(String message) {
    super(message);
  }

  public StorageReadInterruptedException(String message, Throwable cause) {
    super(message, cause);
  }
}
