package fun.fengwk.kkstudio.web.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.SpawnRequest;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.platform.agenttask.AgentTaskRequest;
import fun.fengwk.kkstudio.platform.agenttask.AgentTaskService;
import fun.fengwk.kkstudio.platform.agenttask.AgentTaskStatus;
import fun.fengwk.kkstudio.platform.harness.task.AgentBranchSettingsMaterializer;
import fun.fengwk.kkstudio.share.ai.runtime.AgentTaskDTO;
import fun.fengwk.kkstudio.share.ai.runtime.AgentTaskSubmitDTO;
import fun.fengwk.kkstudio.web.api.ApiResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Agent 任务 HTTP 入口：提交一段提示词交给指定 Agent 独立执行，并用返回的票据观察状态、读取结果或停止。
 *
 * <p>任务是控制器发起的独立执行根：提交只返回票据与执行 Thread，结果在执行终止边界冻结后经观察读取；本端点不持有任何自己的状态。
 */
@RestController
@RequestMapping("/api/agent-tasks")
public class StudioAgentTaskController {

  private final AgentTaskService agentTasks;
  private final AgentBranchSettingsMaterializer settingsMaterializer;

  public StudioAgentTaskController(
      AgentTaskService agentTasks, AgentBranchSettingsMaterializer settingsMaterializer) {
    this.agentTasks = Objects.requireNonNull(agentTasks, "agentTasks");
    this.settingsMaterializer =
        Objects.requireNonNull(settingsMaterializer, "settingsMaterializer");
  }

  /**
   * 提交任务；202 仅表示任务已 durable 接受，结果经票据异步观察。客户端给出的 {@code ticketId} 是幂等键：执行 Thread、Session 与首条命令的 id
   * 都由它确定性派生，因此同一请求重放会精确命中原任务并返回其当前状态，同一 ticketId 换了内容则被运行时拒绝。
   */
  @PostMapping
  public ApiResponse<AgentTaskDTO> submit(@RequestBody AgentTaskSubmitDTO request) {
    return ApiResponse.accepted(
        StudioHarnessThreadController.withRuntimeTranslation(
            () -> {
              UUID ticketId = parse(request.getTicketId());
              String agent = requireText(request.getAgent(), "agent");
              String prompt = requireText(request.getPrompt(), "prompt");
              Integer maxTurns = request.getMaxTurns();
              if (maxTurns != null && maxTurns <= 0) {
                throw new IllegalArgumentException("maxTurns must be positive");
              }
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
            }));
  }

  /** 读取任务当前状态；结束后同时带冻结结果。 */
  @GetMapping("/{ticketId}")
  public ApiResponse<AgentTaskDTO> get(@PathVariable String ticketId) {
    return ApiResponse.ok(
        StudioHarnessThreadController.withRuntimeTranslation(() -> status(parse(ticketId))));
  }

  /** 停止任务（含其子树）并返回停止后的状态；已结束的任务按原样返回。 */
  @PostMapping("/{ticketId}/stop")
  public ApiResponse<AgentTaskDTO> stop(@PathVariable String ticketId) {
    return ApiResponse.ok(
        StudioHarnessThreadController.withRuntimeTranslation(
            () -> {
              UUID id = parse(ticketId);
              agentTasks.stop(id);
              return status(id);
            }));
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

  private static UUID parse(String ticketId) {
    return Uuids.parseCanonical(ticketId, "ticketId");
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
