package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapper;

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

  /** 提交任务；202 仅表示任务已 durable 接受，结果经票据异步观察。 */
  @PostMapping
  public Result<AgentTaskDTO> submit(@RequestBody AgentTaskSubmitDTO request) {
    return Results.accepted(
        StudioHarnessThreadController.withRuntimeTranslation(
            () -> {
              String agent = requireText(request.getAgent(), "agent");
              String prompt = requireText(request.getPrompt(), "prompt");
              BranchSettings settings = settingsMaterializer.materialize(agent);
              NewThreadCommand command =
                  new NewThreadCommand(
                      new CustomMessageCommandPayload(AgentMessage.user(prompt)),
                      UUID.randomUUID());
              UUID ticketId = UUID.randomUUID();
              UUID threadId = UUID.randomUUID();
              agentTasks.submit(
                  new AgentTaskRequest(
                      ticketId,
                      threadId,
                      UUID.randomUUID(),
                      settings,
                      List.of(command),
                      AcceptancePreflight.IDENTITY,
                      SpawnTrigger.CONTROLLER,
                      null,
                      null,
                      null,
                      false,
                      new SpawnRequest.Budget(request.getMaxTurns(), 1, 1, Integer.MAX_VALUE),
                      command.requestHash()));
              AgentTaskDTO accepted = new AgentTaskDTO();
              accepted.setTicketId(ticketId.toString());
              accepted.setThreadId(threadId.toString());
              accepted.setAgent(settings.agentName());
              accepted.setState(AgentTaskStatus.State.RUNNING.name());
              return accepted;
            }));
  }

  /** 读取任务当前状态；结束后同时带冻结结果。 */
  @GetMapping("/{ticketId}")
  public Result<AgentTaskDTO> get(@PathVariable String ticketId) {
    return Results.ok(
        StudioHarnessThreadController.withRuntimeTranslation(() -> status(parse(ticketId))));
  }

  /** 停止任务（含其子树）并返回停止后的状态；已结束的任务按原样返回。 */
  @PostMapping("/{ticketId}/stop")
  public Result<AgentTaskDTO> stop(@PathVariable String ticketId) {
    return Results.ok(
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

  private static UUID parse(String ticketId) {
    return HarnessRuntimeRequestMapper.parseUuid(ticketId, "ticketId");
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
