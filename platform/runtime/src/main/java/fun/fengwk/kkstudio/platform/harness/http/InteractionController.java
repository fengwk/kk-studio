package fun.fengwk.kkstudio.platform.harness.http;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.harness.api.ToolInputAcceptance;
import fun.fengwk.kkstudio.harness.api.ToolInputSubmissionCommand;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessToolInputDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessToolInputResultDTO;
import fun.fengwk.kkstudio.platform.harness.dto.InteractionPageDTO;
import fun.fengwk.kkstudio.platform.interaction.InteractionQueryService;
import fun.fengwk.kkstudio.platform.interaction.InteractionService;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 待处理人工交互的只读列表与唯一人工写入口。
 *
 * <p>查询把 Harness 两种人工等待与环境等待投影为同一分页列表；写入把问卷回答或拒答提交给 owning Thread 的 Tool invocation。目标 Thread 与
 * invocation 都在路径中，操作者身份只来自服务端认证上下文，请求体永不能指定 actor。
 */
@RestController
@RequestMapping("/api/harness")
public class InteractionController {

  private final InteractionQueryService interactionQueryService;
  private final InteractionService interactionService;

  public InteractionController(
      InteractionQueryService interactionQueryService, InteractionService interactionService) {
    this.interactionQueryService =
        Objects.requireNonNull(interactionQueryService, "interactionQueryService");
    this.interactionService = Objects.requireNonNull(interactionService, "interactionService");
  }

  /**
   * 查询一页待处理 Interaction（问卷等待、审批等待与环境等待合并，按 {@code (createdAt, toolInvocationId)} 稳定升序）。
   *
   * <p>{@code rootThreadId} 可选：给出时只返回该执行根（含其全部后代来源）的待办，且必须是已存在的执行根（不存在 {@literal ->} 404、非根或非
   * canonical {@literal ->} 400）。
   */
  @GetMapping("/interactions")
  public InteractionPageDTO listInteractions(
      @RequestParam(required = false) UUID rootThreadId,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false, defaultValue = "50") int limit) {
    return interactionQueryService.listInteractions(rootThreadId, cursor, limit);
  }

  /**
   * 提交一次问卷回答（或明确拒答）。
   *
   * <p>{@code toolInvocationId} 是待处理列表给出的调用主键。目标不适用或提交身份/答案与已物化结果不匹配 {@literal ->} 409；目标不存在
   * {@literal ->} 404。
   */
  @PostMapping("/threads/{threadId}/tool-invocations/{toolInvocationId}/input")
  public HarnessToolInputResultDTO submitToolInput(
      @PathVariable UUID threadId,
      @PathVariable UUID toolInvocationId,
      @RequestBody HarnessToolInputDTO request,
      HttpServletRequest httpRequest) {
    String actor = OperatorResolver.resolve(httpRequest);
    ToolInputAcceptance acceptance =
        interactionService.submitInput(toCommand(request, threadId, toolInvocationId, actor));
    return toResultDto(acceptance);
  }

  /** 问卷回答与拒答的唯一差别在于命令中的 declined 与答案集合。 */
  private static ToolInputSubmissionCommand toCommand(
      HarnessToolInputDTO request, UUID threadId, UUID toolInvocationId, String actor) {
    // 必填字段由 HTTP 边界（RequestBodyRequiredValidationAdvice）拒绝；这里只为客户端值构造做单点窄范围转换，
    // 只翻译构造器抛出的 IllegalArgumentException，不捕获 NullPointerException。
    return HarnessRuntimeRequestMapper.validation(
        "toolInput",
        () ->
            switch (request) {
              case HarnessToolInputDTO.Answer answer -> new ToolInputSubmissionCommand(
                  threadId,
                  toolInvocationId,
                  answer.submissionId(),
                  actor,
                  false,
                  answer.answers());
              case HarnessToolInputDTO.Decline decline -> new ToolInputSubmissionCommand(
                  threadId, toolInvocationId, decline.submissionId(), actor, true, List.of());
            });
  }

  private static HarnessToolInputResultDTO toResultDto(ToolInputAcceptance acceptance) {
    return new HarnessToolInputResultDTO(
        acceptance.threadId().toString(),
        acceptance.toolInvocationId().toString(),
        acceptance.receipt().submissionId().toString(),
        acceptance.receipt().actor(),
        acceptance.receipt().acceptedAt(),
        acceptance.materialized());
  }
}
