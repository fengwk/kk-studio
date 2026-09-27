package fun.fengwk.kkstudio.platform.harness.task.repo;

import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskMessages.Outcome;
import fun.fengwk.kkstudio.platform.harness.task.SubagentTask;
import fun.fengwk.kkstudio.platform.harness.task.SubagentTaskDraft;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code harness_subagent_task} 的持久化端口。
 *
 * <p>本端口只提供异步 task 的归属、执行、终态与投递事实，不承载调度：接受与结算都由 Runtime 的 store 事务驱动，写方法必须在调用方的 store 事务内执行
 * （写失败必须让整个接受/交付事务回滚）。
 *
 * <p>状态推进一律是 CAS：{@link #settleResult} 只从 OPEN 前进，{@link #markDelivered} 只从 SETTLED
 * 前进。调用方必须检查返回值，未命中说明 并发方已经推进，必须让当前事务回滚或跳过，绝不能当作成功继续。
 */
public interface SubagentTaskRepository {

  /**
   * 插入一行委派记录（OPEN）；主键冲突（同 invocation 重放或同子 Thread 已有 OPEN 行）抛 {@code
   * DataIntegrityViolationException}。
   *
   * <p>时间戳由数据库事务时间填充，调用方不提供。
   *
   * @return 是否恰好插入一行
   */
  boolean insert(SubagentTaskDraft draft);

  /** 按 invocation id 读取；不存在返回 {@code null}。 */
  SubagentTask findByInvocationId(UUID invocationId);

  /**
   * 按 {@code (created_at, invocation_id)} 稳定顺序枚举未交付（OPEN 或 SETTLED）记录，至多 {@code limit} 行。
   *
   * <p>{@code afterCreatedAt}/{@code afterInvocationId} 为 keyset 游标：为 null
   * 时从头开始，否则只取游标之后的行。扫描端用它轮转批次，使长期无法推进的记录 不会永久占据批次前部而让其后的记录饿死。
   */
  List<SubagentTask> listUndeliveredAfter(
      Instant afterCreatedAt, UUID afterInvocationId, int limit);

  /**
   * 在调用事务内串行化某个委派树的额度校验：按固定顺序（根 Thread → 父 Thread）获取事务级数据库 advisory 锁并在事务结束时释放。
   *
   * <p>额度是按持久事实聚合的，单纯计数无法阻止并发接受同时读到旧计数；本方法让"读计数 + 插入记录"在锁内完成，使 {@code maxConcurrency}/{@code
   * maxTotalConcurrency} 成为并发安全的硬约束。advisory 锁不参与 Harness store 的实体锁序（它不锁任何协议行）， 因此不会与 store
   * 的锁序断言冲突，也不重入 store 事务。
   *
   * <p>固定顺序（根先于父）使任意两个并发接受都以同序申请锁，避免互相等待。
   */
  void lockQuota(UUID rootThreadId, UUID parentThreadId);

  /** 统计某个父 Thread 仍在执行的委派数（父级并发额度的持久事实）。 */
  int countOpenByParentThreadId(UUID parentThreadId);

  /** 统计某个根 Thread 仍在执行的委派数（树级并发额度的持久事实）。 */
  int countOpenByRootThreadId(UUID rootThreadId);

  /**
   * 委派子树（含传入 Thread 自身）内是否仍有正在执行的委派（{@code OPEN}）。
   *
   * <p>用于聚合"这棵子树是否还有在跑的执行"：运行中的执行是真实活动，不因祖先已停止而消失。
   */
  boolean hasOpenInSubtree(UUID threadId);

  /**
   * 委派子树（含传入 Thread 自身）内仍未交付终态的父 Thread id（去重）。
   *
   * <p>是否构成活动由调用方按父 Thread 的停止状态判定：父已停止的记录不会被交付，因而不构成活动。
   */
  List<UUID> listSettledParentThreadIdsInSubtree(UUID threadId);

  /** 该子 Thread 是否还有未交付执行（{@code OPEN} 或 {@code SETTLED}）：继续委派前必须为空，避免旧结果与新 prompt 串扰。 */
  boolean hasUndeliveredByChildThreadId(UUID childThreadId);

  /** OPEN → SETTLED 的 CAS：写入执行终态与报告；返回是否命中（未命中说明已由并发方推进）。 */
  boolean settleResult(
      UUID invocationId, Outcome outcome, String report, String partialResult, String error);

  /** SETTLED → DELIVERED 的 CAS：返回是否命中（未命中说明已交付或已被并发交付）。 */
  boolean markDelivered(UUID invocationId);

  /** 仅在仍在执行且旧阈值匹配时推进软提醒阈值，返回是否命中。 */
  boolean updateReminderTurn(UUID invocationId, long previousReminderTurn, long reminderTurn);
}
