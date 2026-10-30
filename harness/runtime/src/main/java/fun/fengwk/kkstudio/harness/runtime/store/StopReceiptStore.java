package fun.fengwk.kkstudio.harness.runtime.store;

import fun.fengwk.kkstudio.harness.runtime.StoppedThreadReceipt;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stop 回执聚合的事务内视图。
 *
 * <p>回执身份 {@code (threadId, stopRequestId)} 不可重用；一次 Stop 的完整范围由 {@code (rootThreadId,
 * rootStopRequestId)} 标识。
 */
public interface StopReceiptStore {

  /** 持久保存一次 Stop 的完整回执集合；每条回执的 Thread 必须已锁定。 */
  void insertReceipts(
      UUID rootThreadId, UUID rootStopRequestId, List<StoppedThreadReceipt> receipts);

  /** 按 {@code (threadId, stopRequestId)} 读取单条回执。 */
  Optional<StoppedThreadReceipt> findReceipt(UUID threadId, UUID stopRequestId);

  /** 读取 Thread 自己的全部回执，按创建顺序。 */
  List<StoppedThreadReceipt> loadByThread(UUID threadId);

  /** 读取一次 Stop 根对产生的完整回执集合，按 Thread UUID 升序。 */
  List<StoppedThreadReceipt> loadByRootRequest(UUID rootThreadId, UUID rootStopRequestId);
}
