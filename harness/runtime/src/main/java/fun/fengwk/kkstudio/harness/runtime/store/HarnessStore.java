package fun.fengwk.kkstudio.harness.runtime.store;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

/**
 * 单一持久化存储根：Harness 持久化原语的唯一入口。
 *
 * <p>本接口不是 Repository / Specification / generic save / UnitOfWork 框架：不提供任何业务 use-case 方法（例如
 * applyTerminalModel、applyToolBatch、startTurn、stop、approve、decideNextAction、harvest、enqueue），更新只允许修改
 * Thread / Command / Invocation / Work 的 current state；Session 与 Entry 是 append-only 不可变记录。
 *
 * <p>事务语义（所有实现必须遵守）：回调正常返回即提交（除非实现选择加入调用方已有的外层事务，此时提交/回滚由外层事务决定），抛出 {@link RuntimeException} 或
 * {@link Error} 时完整回滚并原样重抛；回调返回 null 合法（void 场景）。事务句柄只能由执行回调的同一线程在回调内使用； 跨线程使用或回调结束后的任何句柄调用都必须被实现以
 * {@link IllegalStateException} 拒绝。实现必须拒绝重入（回调内再次调用同一 Store 的 {@link
 * #transaction}）。并发由实现决定：生产实现允许并发事务，测试参考实现使用全局 monitor 串行化。
 *
 * <p>多实体锁顺序（所有多行事务必须遵守，用于收敛已知锁逆序与数据库死锁路径）：携带 frozen task 策略的 Join 准入先获取全局 Join 准入锁 （{@link
 * Transaction#joins()} 的 {@code lockAdmission}），再获取执行树锁（{@link Transaction#threads()} 的 {@code
 * lockTree}）；若涉及 Session 锁，先锁 Session（{@code lockForKeyShare} 或 {@code lockForUpdate}），再按 {@link
 * UuidOrder} 升序锁 Thread，再锁其 Commands，再锁其 Driver 私有行，再按 callIndex 升序锁同 Assistant Entry 的
 * ToolInvocation siblings，最后锁 Work；同一事务锁多行 Work 时，同层 Work 必须按 (type, id) 升序（例如先 THREAD Work 再 MODEL
 * Work）。创建、请求或强制删除 Work 的业务事务必须先锁 owning Thread；dispatcher claim、heartbeat/lease 等单 Work
 * 调度事务是唯一例外，它们不得创建新的业务 wake。实现必须在实际获取新锁前以 {@link IllegalStateException} 拒绝已知逆序；重复访问本事务已持有的锁合法。
 *
 * <p>读取约定：所有 find/lock 返回 {@link java.util.Optional}；所有 list 返回不可变列表；list 入参被防御性拷贝且拒绝 null 元素。唯一键 /
 * 引用完整性违反抛 {@link IllegalArgumentException}；未锁定即更新抛 {@link IllegalStateException}。
 *
 * <p>时间：唯一权威时间是 {@link Transaction#now()}——生产实现取数据库时钟，同一事务内保持不变。所有持久化时间戳都取自它，
 * 时间只记录事实发生的时刻，不承担排序或单调性约束 （顺序由父链、序号与版本表达）。映射到 SQL timestamp 列的时间统一使用毫秒精度；任何非 null 值若包含亚毫秒部分，实现必须以
 * {@link IllegalArgumentException} 拒绝，禁止静默截断或四舍五入。
 */
public interface HarnessStore {

  /**
   * 开启并执行一个事务，返回回调结果（可为 null）。
   *
   * <p>回调内只能通过句柄执行 typed primitives；回调正常返回即提交，抛出 {@link RuntimeException} 或 {@link Error}
   * 即回滚并重抛。句柄在回调返回后失效。
   *
   * <p>实现可以加入调用方已有的外层事务（例如 Spring {@code
   * PROPAGATION_REQUIRED}）：此时回调正常返回只表示当前事务边界内的写入已准备好，实际提交/回滚由外层事务决定；
   * 未加入外层事务的调用则保持回调返回即提交的语义。回调抛异常在两种模式下都会使当前事务边界失效（标记 rollback-only 或直接回滚），外层事务随后提交时会被拒绝。实现不得改用
   * {@code REQUIRES_NEW}：owner 锁与 harness durable 更新必须留在同一物理事务里。
   */
  <T> T transaction(Function<Transaction, T> callback);

  /**
   * 把进程内副作用登记到当前 {@link #transaction} 的物理提交之后。
   *
   * <p>只能由正在执行的 store callback 调用。无外层事务时，副作用在该 callback 成功提交后、{@link #transaction}
   * 返回前执行；实现加入了外层事务时， 副作用只在该物理事务真正 {@code afterCommit} 时执行，rollback 不执行。callback 抛异常时不得执行已登记的动作。
   *
   * <p>动作执行时 store 事务边界已释放且提交已可见：动作可以开启新的 store 事务读取已提交状态；但动作不属于任何事务，其失败不得影响已提交结果，也不得被 {@link
   * #transaction} 重试或回滚。
   */
  void afterCommit(Runnable action);

  /**
   * 断言当前调用不处于任何环境事务中。
   *
   * <p>供要求独立物理事务边界、不能加入调用方环境事务的入口在开始工作前调用。内存实现是 no-op；生产实现检测到真实环境事务时必须在 该工作执行前拒绝。无事务时立即返回。
   */
  void assertNoAmbientTransaction();

  /** 一次事务内的 typed persistence 句柄：只有事务时钟、id 分配与九个聚合视图。 */
  interface Transaction {

    /** 本事务的权威时间（毫秒精度）：首次调用时取数据库时钟，之后在本事务内返回同一值。 */
    Instant now();

    /** 分配下一个 durable UUID id：只保证全局唯一；事务失败可能已消耗 ID，不要求 rollback 复用。 */
    UUID nextId();

    /** Session 聚合视图。 */
    SessionStore sessions();

    /** Entry 聚合视图。 */
    EntryStore entries();

    /** Thread 聚合视图。 */
    ThreadStore threads();

    /** Thread Command 聚合视图。 */
    CommandStore commands();

    /** ToolInvocation 聚合视图。 */
    ToolStore tools();

    /** Work 调度原语视图。 */
    WorkStore work();

    /** Thread Join 聚合视图。 */
    JoinStore joins();

    /** Stop 回执聚合视图。 */
    StopReceiptStore stops();

    /** 执行退出账本聚合视图。 */
    ExecutionExitStore exits();
  }
}
