package fun.fengwk.kkstudio.harness.runtime.run;

import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import java.time.Instant;
import java.util.List;

/**
 * Assistant -> Tool 的原子 barrier。T05 实现只保证 Assistant 持久化后进入 WAITING_TOOLS；T06 在同一事务中补充 Invocation
 * 创建，端口返回前禁止任何工具副作用。
 */
@FunctionalInterface
public interface ToolPreparationPort {
  boolean prepare(
      AgentRun claimedRun, MessageEntryPayload assistant, List<ToolCall> toolCalls, Instant now);
}
