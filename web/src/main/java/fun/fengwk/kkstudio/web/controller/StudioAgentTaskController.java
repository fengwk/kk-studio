package fun.fengwk.kkstudio.web.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.platform.http.ApiResponse;
import fun.fengwk.kkstudio.share.runtime.AgentTaskDTO;
import fun.fengwk.kkstudio.share.runtime.AgentTaskSubmitDTO;
import fun.fengwk.kkstudio.web.harness.AgentTaskSubmissionService;

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

  private final AgentTaskSubmissionService agentTaskService;

  public StudioAgentTaskController(AgentTaskSubmissionService agentTaskService) {
    this.agentTaskService = Objects.requireNonNull(agentTaskService, "agentTaskService");
  }

  /**
   * 提交任务；202 仅表示任务已 durable 接受，结果经票据异步观察。客户端给出的 {@code ticketId} 是幂等键：执行 Thread、Session 与首条命令的 id
   * 都由它确定性派生，因此同一请求重放会精确命中原任务并返回其当前状态，同一 ticketId 换了内容则被运行时拒绝。
   */
  @PostMapping
  public ApiResponse<AgentTaskDTO> submit(@RequestBody AgentTaskSubmitDTO request) {
    return ApiResponse.accepted(
        RuntimeErrors.translate(
            () -> {
              UUID ticketId = Uuids.parseCanonical(request.getTicketId(), "ticketId");
              String agent = requireText(request.getAgent(), "agent");
              String prompt = requireText(request.getPrompt(), "prompt");
              Integer maxTurns = request.getMaxTurns();
              if (maxTurns != null && maxTurns <= 0) {
                throw new IllegalArgumentException("maxTurns must be positive");
              }
              return agentTaskService.submit(ticketId, agent, prompt, maxTurns);
            }));
  }

  /** 读取任务当前状态；结束后同时带冻结结果。 */
  @GetMapping("/{ticketId}")
  public ApiResponse<AgentTaskDTO> get(@PathVariable String ticketId) {
    return ApiResponse.ok(
        RuntimeErrors.translate(
            () -> agentTaskService.get(Uuids.parseCanonical(ticketId, "ticketId"))));
  }

  /** 停止任务（含其子树）并返回停止后的状态；已结束的任务按原样返回。 */
  @PostMapping("/{ticketId}/stop")
  public ApiResponse<AgentTaskDTO> stop(@PathVariable String ticketId) {
    return ApiResponse.ok(
        RuntimeErrors.translate(
            () -> agentTaskService.stop(Uuids.parseCanonical(ticketId, "ticketId"))));
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
