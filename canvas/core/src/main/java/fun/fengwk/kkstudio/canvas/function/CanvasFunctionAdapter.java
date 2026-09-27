package fun.fengwk.kkstudio.canvas.function;

import java.util.List;
import java.util.UUID;

/**
 * 一个构建期插件对外声明并执行的 Canvas Function 集合。
 *
 * <p>执行分两个阶段，Runtime 在两次调用之间持久化外部提交事实：
 *
 * <ol>
 *   <li>{@link #submit}：Runtime 已先把提交意图落库（{@code SUBMITTING}）后才调用。实现必须先 {@link
 *       CanvasFunctionExecutionContext#checkpoint} 记录恢复所需的外部任务身份，再发起外部提交；确认外部任务被接受后正常返回。
 *       无法确认提交是否生效且没有安全查询依据时抛 {@link CanvasFunctionUnknownException}。
 *   <li>{@link #execute}：提交事实已持久化。实现必须只查询并物化 {@code SUBMITTED} 的原任务，绝不能重新提交。
 * </ol>
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
