package fun.fengwk.kkstudio.platform.agenttask;

import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.SpawnRequest;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次 Agent 任务的提交请求：创建（或续用）Thread、接受首批输入并登记结果票据。
 *
 * <p>身份由调用方预分配以保证幂等：新建需要 {@code sessionId} 与 {@code threadId}；续用既有 Thread 时 {@code sessionId} 为
 * null、{@code continuation} 给出精确 cursor。{@code ticketId} 是票据身份，{@code requestHash} 是其请求指纹。有父 Thread
 * 的任务（子代理）通知父 Thread 并计入并发额度，必须给出父 head 作为准入 cursor；无父任务只冻结回执供调用方观察。
 *
 * @param trigger 创建来源，不能是 {@link SpawnTrigger#USER}（用户会话经命令接受创建）
 * @param yoloEnabled 新执行根的初始 YOLO 开关；子代理与续用恒为 false
 */
public record AgentTaskRequest(
    UUID ticketId,
    UUID threadId,
    UUID sessionId,
    BranchSettings settings,
    List<NewThreadCommand> commands,
    AcceptancePreflight preflight,
    SpawnTrigger trigger,
    UUID parentThreadId,
    UUID expectedParentHeadEntryId,
    SpawnRequest.Continuation continuation,
    boolean yoloEnabled,
    SpawnRequest.Budget budget,
    String requestHash) {

  public AgentTaskRequest {
    Objects.requireNonNull(ticketId, "ticketId");
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(commands, "commands");
    Objects.requireNonNull(preflight, "preflight");
    Objects.requireNonNull(trigger, "trigger");
    Objects.requireNonNull(budget, "budget");
    Objects.requireNonNull(requestHash, "requestHash");
  }
}
