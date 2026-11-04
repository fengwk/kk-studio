package fun.fengwk.kkstudio.web.harness;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.api.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.api.SpawnRequest;
import fun.fengwk.kkstudio.harness.api.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.spi.AcceptancePreflight;
import fun.fengwk.kkstudio.platform.agenttask.AgentTaskRequest;
import fun.fengwk.kkstudio.platform.agenttask.AgentTaskService;
import fun.fengwk.kkstudio.platform.agenttask.AgentTaskStatus;
import fun.fengwk.kkstudio.platform.harness.task.AgentBranchSettingsMaterializer;
import fun.fengwk.kkstudio.share.ai.runtime.AgentTaskDTO;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Agent 任务提交的应用编排：票据是幂等键，Thread / Session / 首条命令 id 与请求指纹都由票据确定性派生。
 *
 * <p>物化 settings 与构造任务请求放在 {@link AgentTaskService#submitExternal} 的惰性 supplier 中：重放已存在票据时不会重新物化配置。
 */
@Service
@AllArgsConstructor
public class AgentTaskSubmissionService {

  private final AgentTaskService agentTasks;
  private final AgentBranchSettingsMaterializer settingsMaterializer;

  /** 提交一段提示词并返回该票据的当前状态；相同票据重放命中原任务。 */
  public AgentTaskDTO submit(UUID ticketId, String agent, String prompt, Integer maxTurns) {
    String requestHash = AgentTaskRequest.requestHash(ticketId, agent, prompt, maxTurns);
    agentTasks.submitExternal(
        ticketId,
        requestHash,
        () -> {
          BranchSettings settings = settingsMaterializer.materialize(agent);
          NewThreadCommand command =
              new NewThreadCommand(
                  new CustomMessageCommandPayload(AgentMessage.user(prompt)),
                  derivedId(ticketId, "command"));
          return new AgentTaskRequest(
              ticketId,
              derivedId(ticketId, "thread"),
              derivedId(ticketId, "session"),
              settings,
              List.of(command),
              AcceptancePreflight.IDENTITY,
              SpawnTrigger.CONTROLLER,
              null,
              null,
              null,
              false,
              new SpawnRequest.Budget(maxTurns, 1, 1, Integer.MAX_VALUE),
              requestHash);
        });
    return status(ticketId);
  }

  /** 读取任务当前状态；结束后同时带冻结结果。 */
  public AgentTaskDTO get(UUID ticketId) {
    return status(ticketId);
  }

  /** 停止任务（含其子树）并返回停止后的状态；已结束的任务按原样返回。 */
  public AgentTaskDTO stop(UUID ticketId) {
    agentTasks.stop(ticketId);
    return status(ticketId);
  }

  private AgentTaskDTO status(UUID ticketId) {
    AgentTaskStatus status =
        agentTasks
            .observe(ticketId)
            .orElseThrow(
                () ->
                    new HarnessRuntimeNotFoundException(
                        "agent task " + ticketId + " does not exist"));
    AgentTaskDTO dto = new AgentTaskDTO();
    dto.setTicketId(status.ticketId().toString());
    dto.setThreadId(status.threadId().toString());
    dto.setAgent(status.agent());
    dto.setState(status.state().name());
    ThreadJoinReceipt receipt = status.receipt();
    if (receipt != null) {
      dto.setReport(receipt.report());
      dto.setPartialResult(receipt.partialResult());
      dto.setError(receipt.error());
    }
    return dto;
  }

  /** 由票据确定性派生的 id：同一票据的重放得到同一组 Thread / Session / 命令 id。 */
  private static UUID derivedId(UUID ticketId, String role) {
    return UUID.nameUUIDFromBytes(
        ("agent-task:" + role + ":" + ticketId).getBytes(StandardCharsets.UTF_8));
  }
}
