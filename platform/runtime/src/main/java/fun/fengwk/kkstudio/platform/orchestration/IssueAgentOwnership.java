package fun.fengwk.kkstudio.platform.orchestration;

import java.util.Optional;
import java.util.UUID;

/**
 * Issue+Agent owner 的产品层级与稳定 Thread 绑定，由 Project 应用实现。
 *
 * <p>平台编排只经由本接口锁定 Project/Issue 并读写 {@code (issueId, agentName) -> threadId} 绑定，自身不感知 Project
 * 领域。锁序固定为先 Project 后 Issue：接受与交互路径用共享锁（{@code Project KEY SHARE -> Issue UPDATE}），不阻塞同 owner
 * 的并发写入；深删除路径用排他锁。所有方法都必须在调用方的事务内执行。
 */
public interface IssueAgentOwnership {

  /**
   * 接受路径：锁定产品层级并读取绑定 Thread。
   *
   * @return 已绑定的 Thread；尚未完成首次接受时为空
   * @throws IllegalArgumentException Project/Issue 缺失、已归档或层级不一致
   */
  Optional<UUID> lockForAcceptance(OwnerRef.IssueAgent owner);

  /**
   * 深删除路径：排他锁定产品层级并读取绑定 Thread。
   *
   * @return 已绑定的 Thread；owner 不存在或尚未绑定时为空，表示没有可删除的归属
   */
  Optional<UUID> lockForDeletion(OwnerRef.IssueAgent owner);

  /** 读取绑定 Thread，不加锁。 */
  Optional<UUID> findBoundThread(OwnerRef.IssueAgent owner);

  /** 删除绑定行；它持有指向 harness Thread 的外键，必须先于 Thread 删除。 */
  void deleteBinding(OwnerRef.IssueAgent owner);

  /**
   * 交互路径：执行根属于 Issue+Agent 时锁定其产品层级；根没有 Issue 归属时不做任何事。
   *
   * @throws IllegalStateException 绑定仍存在但 Project/Issue 缺失或层级不一致（归属事实损坏）
   */
  void lockRootForInteraction(UUID rootThreadId);

  /** 执行根的 Issue+Agent 归属；根没有 Issue 归属时为空。 */
  Optional<BoundRoot> findBoundRoot(UUID rootThreadId);

  /** 一个绑定执行根的展示事实。 */
  record BoundRoot(UUID issueId, String agentName, String issueTitle) {}
}
