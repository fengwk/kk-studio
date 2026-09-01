package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.util.List;
import java.util.Objects;

/**
 * {@link ModelResponsePlanner} 的纯规划结果：Thread 的 terminal Model apply 只消费该形状，不重新决策。
 *
 * <p>变体语义：{@link Completed} 关闭 turn（COMPLETE 无 calls）；{@link Failed} 以稳定 reason 关闭 failed turn
 * （LENGTH 无 calls -&gt; OUTPUT_TRUNCATED，FILTERED -&gt; CONTENT_FILTERED）；{@link ToolBatch} 为每个
 * observed call 携带一个 callIndex 槽位（仅 READY 请求 TOOL Work，全部 immediate terminal 时由 Thread 自唤醒）。
 */
public sealed interface ModelResponsePlan {

  /** COMPLETE 且无 tool calls：0 ToolInvocation，直接 completed TURN_END。 */
  record Completed() implements ModelResponsePlan {}

  /** LENGTH 无 calls / FILTERED：0 ToolInvocation，failed TURN_END（reason 为稳定截断/过滤原因）。 */
  record Failed(TurnEndReason reason) implements ModelResponsePlan {

    public Failed {
      Objects.requireNonNull(reason, "reason");
      if (reason != TurnEndReason.OUTPUT_TRUNCATED && reason != TurnEndReason.CONTENT_FILTERED) {
        throw new IllegalArgumentException(
            "planner may only close failed turns with OUTPUT_TRUNCATED or CONTENT_FILTERED");
      }
    }
  }

  /** 每个 observed call 一个槽位（保持 callIndex）；Thread 按槽位 materialize ToolInvocation。 */
  record ToolBatch(List<ToolSlot> tools) implements ModelResponsePlan {

    public ToolBatch {
      tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
    }
  }

  /**
   * 单个 observed call 的规划槽位。
   *
   * <p>READY：binding 必须非空、无 error；FAILED：必须携带 error，binding 仅对 unknown tool 可空。LENGTH 截断的 FAILED 槽位
   * binding 尽力查找（已知工具非空、unknown 为 null）。
   */
  record ToolSlot(
      ToolCall call, ToolBinding binding, ToolInvocationStatus status, ToolInvocationError error) {

    public ToolSlot {
      call = Objects.requireNonNull(call, "call");
      status = Objects.requireNonNull(status, "status");
      if (status == ToolInvocationStatus.READY) {
        if (binding == null) {
          throw new IllegalArgumentException("READY slot requires a binding");
        }
        if (error != null) {
          throw new IllegalArgumentException("READY slot must not carry an error");
        }
      } else if (status == ToolInvocationStatus.FAILED) {
        if (error == null) {
          throw new IllegalArgumentException("FAILED slot requires an error");
        }
      } else {
        throw new IllegalArgumentException("planner slots may only be READY or FAILED");
      }
    }
  }
}
