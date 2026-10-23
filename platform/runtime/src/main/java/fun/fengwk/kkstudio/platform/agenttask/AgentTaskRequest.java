package fun.fengwk.kkstudio.platform.agenttask;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.hash.Sha256;
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

  /**
   * 规范计算外部任务身份的 canonical JSON 文本：严格覆盖 {@code ticketId}、{@code agent}、{@code prompt} 与 nullable 的
   * {@code maxTurns}。与内部 settings 或运行时配置无关。
   */
  public static String canonicalJson(UUID ticketId, String agent, String prompt, Integer maxTurns) {
    Objects.requireNonNull(ticketId, "ticketId");
    if (agent == null || agent.isBlank()) {
      throw new IllegalArgumentException("agent must not be blank");
    }
    if (prompt == null || prompt.isBlank()) {
      throw new IllegalArgumentException("prompt must not be blank");
    }
    if (maxTurns != null && maxTurns <= 0) {
      throw new IllegalArgumentException("maxTurns must be positive");
    }
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("ticketId", ticketId.toString());
    node.put("agent", agent);
    node.put("prompt", prompt);
    if (maxTurns == null) {
      node.putNull("maxTurns");
    } else {
      node.put("maxTurns", maxTurns);
    }
    return node.toString();
  }

  /** 规范计算外部任务身份的请求指纹：SHA-256（64 位小写十六进制）。 */
  public static String requestHash(UUID ticketId, String agent, String prompt, Integer maxTurns) {
    return Sha256.hex(canonicalJson(ticketId, agent, prompt, maxTurns));
  }
}
