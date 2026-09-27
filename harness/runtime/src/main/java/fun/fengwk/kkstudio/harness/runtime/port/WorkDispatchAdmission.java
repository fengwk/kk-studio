package fun.fengwk.kkstudio.harness.runtime.port;

/**
 * 宿主派发准入端口：Harness 在可能开始一次新的对外执行之前，询问宿主此刻是否允许。
 *
 * <p>Harness 只拥有执行机制，不拥有产品状态；只有模型调用与工具执行才是「对外部派发」，因此宿主按产品事实（例如 Issue 的控制暂停、业务阻塞、 归档、活动 Run
 * 坐标与正在进入下一阶段）决定是否放行。
 *
 * <p><b>只有会产生新对外执行的 claim 才会被询问</b>（{@link WorkDispatchRequest} 的 READY 调用）。以下 claim 结构性不询问、始终放行，
 * 因为拒绝它们只会让已经存在的对外执行无法收尾，而不会阻止任何新的对外调用：
 *
 * <ul>
 *   <li>{@code DISPATCHING}/{@code RUNNING} 的模型与工具调用：只观察、恢复或收敛已可能存在的对外 handle；暂停一个 Issue 时，在途执行必须
 *       仍能被轮询、恢复与取消，否则暂停永远无法安全收尾。
 *   <li>{@code WAITING_INPUT}/{@code WAITING_APPROVAL} 与终态调用的 claim：只收敛 durable 事实，不触碰外部系统。
 *   <li>THREAD Work：只物化已有结果与规划 turn；新 turn 是否成立由产品侧 {@code TurnResolver} 判定，本条路径不会自行开始对外执行。
 * </ul>
 *
 * <p>拒绝只延后执行：Work 保持 durable、lease 被归还并按配置退避重排，invocation 事实不被改写；恢复放行后同一 Work 会被重新 claim。
 * 默认实现放行一切，因此没有产品层的纯 Harness 部署不需要宿主策略。
 *
 * <p><b>局限（不是严格无竞态）</b>：准入判定发生在 claim 与 handoff 之间，与宿主后续的产品状态变更（例如人工暂停、Issue 进入下一阶段） 之间存在 TOCTOU
 * 窗口——判定通过后状态可能立即改变，本次执行仍会开始。真正的强一致需要产品侧在同一事务内冻结判定，或由被拒绝的执行结果 自行收敛；本端口只缩小窗口，不消除它。
 */
@FunctionalInterface
public interface WorkDispatchAdmission {

  /** 默认实现：不设产品门禁，放行一切新的对外执行。 */
  WorkDispatchAdmission ALLOW_ALL = request -> true;

  /**
   * 判定一次新的对外执行是否放行；返回 {@code false} 只延后执行，不丢弃 Work。
   *
   * @param request 该次 claim 的宿主可见事实，绝不为 {@code null}
   */
  boolean admits(WorkDispatchRequest request);
}
