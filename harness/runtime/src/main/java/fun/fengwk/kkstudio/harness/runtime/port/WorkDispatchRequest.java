package fun.fengwk.kkstudio.harness.runtime.port;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次「可能首次对外执行」的 Work claim 的宿主可见事实。
 *
 * <p>Harness 只拥有执行机制，不拥有产品事实；宿主（Platform）需要按当前产品状态决定能否开始新的模型/工具执行。与本记录强相关的只 有「尚未对外执行的 READY
 * 调用」，因为其余 claim 都不会产生新的对外调用：{@code DISPATCHING}/{@code RUNNING} 的 claim 只观察或收敛已经 可能存在的对外执行，等待态与终态的
 * claim 只收敛 durable 事实，THREAD claim 只物化历史并规划 turn。后三类一律不询问宿主（见 {@link
 * WorkDispatchAdmission}），否则暂停/阻塞会让在途执行的收尾本身被卡住。
 *
 * <p>本记录只在「READY -&gt; DISPATCHING」的持久意图事务内构造，因此其中的坐标与绑定就是宿主要复验的那一次执行的事实；宿主据此取产品行锁并 与状态转换同事务决策 （见
 * {@link WorkDispatchAdmission#executeIfAdmitted}）。
 *
 * <p>坐标口径：{@code threadId}/{@code sessionId} 是该调用所属 Thread 与其 Session，宿主据此校验该 Thread 是否仍是当前活动 Run 的
 * Agent 坐标；{@code toolBinding} 是 TOOL 调用冻结的完整绑定（含 {@code descriptor().sideEffect()}），宿主据此判断工具是否只读，
 * 不需要再查目录，也不会因目录变化而改变已冻结调用的能力判定。
 */
public record WorkDispatchRequest(
    WorkTargetType type,
    UUID invocationId,
    UUID threadId,
    UUID sessionId,
    ToolBinding toolBinding) {

  public WorkDispatchRequest {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(sessionId, "sessionId");
    if (toolBinding != null && type != WorkTargetType.TOOL) {
      throw new IllegalArgumentException("toolBinding is only available for TOOL work");
    }
    if (type == WorkTargetType.TOOL && toolBinding == null) {
      throw new IllegalArgumentException("TOOL work must carry its frozen tool binding");
    }
  }
}
