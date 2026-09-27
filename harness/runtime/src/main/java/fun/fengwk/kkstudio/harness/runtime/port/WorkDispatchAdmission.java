package fun.fengwk.kkstudio.harness.runtime.port;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 宿主派发准入端口：Harness 在「可能开始一次新的对外执行」的<b>持久意图边界</b>上，把「宿主此刻是否允许」与「状态转换」放进同一个事务里。
 *
 * <p>Harness 只拥有执行机制，不拥有产品状态；只有模型调用与工具执行才是「对外部派发」，因此宿主按产品事实（例如 Issue 的控制暂停、业务阻塞、 归档、活动 Run
 * 坐标与正在进入下一阶段）决定是否放行。判定必须是强一致的：{@link #executeIfAdmitted} 只允许在 Harness 实际写入「已开始对外执行」的短事务内、并且在取任何
 * Harness 行锁之前调用。实现先取产品行锁（例如 Project SHARE -&gt; Issue UPDATE），再执行意图，因此「宿主许可」与「READY -&gt;
 * DISPATCHING」不可分割，也不存在 Thread -&gt; Issue 的反向锁序：任何阻塞都发生在执行外部调用之前，绝不会有事务持锁等待外部 HTTP。
 *
 * <p>返回 {@link Optional#empty()} 表示宿主拒绝。拒绝时调用方<b>不得</b>执行意图、不得改写 invocation 事实；唯一允许的反应是按 {@link
 * #DEFAULT_DEFERRAL}（或部署配置覆盖值）在所有权围栏内做 durable reschedule 后重试，既不丢弃 Work，也不形成热循环。 恢复放行后同一 Work 会被重新
 * claim。
 *
 * <p><b>只有会产生新对外执行的 claim 才会被询问</b>（READY 的 MODEL / TOOL 调用）。以下 claim 结构性不询问、始终放行，
 * 因为拒绝它们只会让已经存在的对外执行无法收尾，而不会阻止任何新的对外调用：
 *
 * <ul>
 *   <li>{@code DISPATCHING}/{@code RUNNING} 的模型与工具调用：只观察、恢复或收敛已可能存在的对外 handle；暂停一个 Issue 时，在途执行必须
 *       仍能被轮询、恢复与取消，否则暂停永远无法安全收尾。
 *   <li>{@code WAITING_INPUT}/{@code WAITING_APPROVAL} 与终态调用的 claim：只收敛 durable 事实，不触碰外部系统。
 *   <li>THREAD Work：只物化已有结果与规划 turn；新 turn 是否成立由产品侧 {@code TurnResolver} 判定，本条路径不会自行开始对外执行。
 * </ul>
 *
 * <p>默认实现放行一切，因此没有产品层的纯 Harness 部署不需要宿主策略。
 */
public interface WorkDispatchAdmission {

  /** 宿主拒绝后调用方默认采用的 durable 重排延迟；部署配置可以覆盖。 */
  Duration DEFAULT_DEFERRAL = Duration.ofSeconds(5);

  /**
   * 默认实现：不设产品门禁，放行一切新的对外执行。
   *
   * <p>唯一抽象方法是泛型方法，因此实现（含本常量与测试假件）只能写成匿名类，不能写成 lambda。
   */
  WorkDispatchAdmission ALLOW_ALL =
      new WorkDispatchAdmission() {
        @Override
        public <T> Optional<T> executeIfAdmitted(WorkDispatchRequest request, Supplier<T> intent) {
          return Optional.ofNullable(intent.get());
        }
      };

  /**
   * 在宿主许可下执行一次新的对外执行的持久意图；返回 {@link Optional#empty()} 表示宿主拒绝，此时意图未被执行。
   *
   * <p>调用契约：必须在 Harness 短事务内调用，且调用时不得持有该事务的任何 Harness 行锁（实现可能先取产品行锁再执行 {@code intent}）；{@code
   * intent} 内的 Harness 状态转换与产品锁同属一个物理事务。
   *
   * @param request 该次 claim 的宿主可见事实，绝不为 {@code null}
   * @param intent 唯一能开始新的对外执行的持久意图（例如 READY -&gt; DISPATCHING）；必须返回非 {@code null} 结果
   * @param <T> 意图结果类型
   */
  <T> Optional<T> executeIfAdmitted(WorkDispatchRequest request, Supplier<T> intent);
}
