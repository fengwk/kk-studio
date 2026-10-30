package fun.fengwk.kkstudio.harness.runtime.store;

import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Thread Join 聚合的事务内视图。
 *
 * <p>携带 frozen task 策略的 Join 准入先获取全局准入锁，再获取执行树锁；Join 行随源 Command 同事务创建。
 */
public interface JoinStore {

  /** 在任何 tree / Session / Thread / Invocation / Work 锁之前获取全局 Join 准入事务级锁。 */
  void lockAdmission();

  /** 插入 Join；源 command 必须同事务存在。 */
  void insert(ThreadJoin join);

  /** 按 invocationId 读取 Join。 */
  Optional<ThreadJoin> find(UUID invocationId);

  /** 读取子 Thread 上尚未冻结结果且未被 supersede 的 Join。 */
  List<ThreadJoin> loadIncomplete(UUID childThreadId);

  /** 统计父 Thread 下未完成的 NOTIFY_PARENT 直接子 Join 数量。 */
  int countIncompleteChildren(UUID parentThreadId);

  /** 判断父 Thread 是否存在未完成的 SETTLE_OWNER_TURN 子 Join。 */
  boolean hasIncompleteOwnerChild(UUID parentThreadId);

  /** 全局统计未完成的执行子 Join 数量。 */
  int countIncompleteSubagent();

  /** 更新 Join；只允许首次冻结、单调提醒、首次交付与首次 supersede。 */
  void update(ThreadJoin join);

  /** 删除子 Thread 的全部 Join 并返回行数；要求子 Thread 已锁定。 */
  int deleteByChild(UUID childThreadId);

  /** 整体删除一批 Thread 涉及的 Join 并返回行数。 */
  int deleteForThreads(List<UUID> threadIds);
}
