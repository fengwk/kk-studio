package fun.fengwk.kkstudio.canvas.function;

import java.util.List;
import java.util.UUID;

/**
 * 一个构建期插件对外声明并执行的 Canvas Function 集合。
 *
 * <p>执行分两个阶段，Runtime 在两次调用之间持久化外部提交事实：
 *
 * <ol>
 *   <li>{@link #submit}：Runtime 已先把提交意图落库（{@code SUBMITTING}）后才调用。需要跨进程恢复外部任务身份或已产生的外部副产物的实现， 必须先
 *       {@link CanvasFunctionExecutionContext#checkpoint} 记录恢复所需事实，再发起外部提交；确认外部任务被接受后正常返回。
 *       无法确认提交是否生效且没有安全查询依据时抛 {@link CanvasFunctionUnknownException}。
 *   <li>{@link #execute}：提交事实已持久化。实现必须只查询并物化 {@code SUBMITTED} 的原任务，绝不能重新提交。
 * </ol>
 *
 * <p>{@code checkpoint} 只服务需要恢复外部事实的 adapter；本地、确定且可重复执行且不产生外部副作用的实现不得写 checkpoint， 提交事实本身由 Runtime
 * 的两阶段状态表达。
 *
 * <p>取消是 best effort：外部取消失败不能回滚已经写入的 CANCELLED 终态。实现不得直接修改节点、Run、pin 或自建任务表，只能通过 {@link
 * CanvasFunctionExecutionContext} 的能力表达进展。
 */
public interface CanvasFunctionAdapter {

  List<CanvasFunctionDefinition> functions();

  boolean enabled();

  String unavailableReason();

  void preflight(CanvasFunctionFrozenRun run);

  void submit(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run);

  List<UUID> execute(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run);

  default void cancel(CanvasFunctionFrozenRun run) {}
}
