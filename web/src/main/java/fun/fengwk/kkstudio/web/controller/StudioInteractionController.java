package fun.fengwk.kkstudio.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.harness.api.ToolInputSubmissionCommand;
import fun.fengwk.kkstudio.platform.http.ApiResponse;
import fun.fengwk.kkstudio.platform.interaction.InteractionQueryService;
import fun.fengwk.kkstudio.platform.interaction.InteractionService;
import fun.fengwk.kkstudio.share.ai.interaction.HarnessToolInputDTO;
import fun.fengwk.kkstudio.share.ai.interaction.HarnessToolInputResultDTO;
import fun.fengwk.kkstudio.share.ai.interaction.InteractionPageDTO;
import fun.fengwk.kkstudio.web.api.OperatorResolver;
import fun.fengwk.kkstudio.web.api.RequestParameters;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapper;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeResponseMapper;

import java.util.Objects;
import java.util.UUID;

/**
 * 统一人工交互 API：一个入口同时覆盖 Chat 与 Issue+Agent 的问卷回答。
 *
 * <p>客户端不需要预先知道 Interaction 属于哪种产品 owner：只提交 {@code interactionId} 与 {@code threadId}，由 platform
 * 交互服务 解析产品归属、按产品锁序串行化后再写入 Harness。操作者身份只来自服务端认证上下文，请求体永远不能指定 actor。
 */
@RestController
@RequestMapping("/api/interactions")
public class StudioInteractionController {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 100;

  private final InteractionQueryService interactionQueryService;
  private final InteractionService interactionService;

  /** 创建统一人工交互 API Controller。 */
  public StudioInteractionController(
      InteractionQueryService interactionQueryService, InteractionService interactionService) {
    this.interactionQueryService =
        Objects.requireNonNull(interactionQueryService, "interactionQueryService");
    this.interactionService = Objects.requireNonNull(interactionService, "interactionService");
  }

  /**
   * 查询一页待处理 Interaction（问卷等待与审批等待合并，按 {@code (createTime, interactionId)} 稳定升序）。
   *
   * <p>{@code rootThreadId} 可选：给出时只返回该执行根（含其全部后代来源）的待办，且必须是已存在的执行根（不存在 {@literal ->} 404、非根或非
   * canonical {@literal ->} 400）。响应项保留原始 {@code threadId/sessionId}，另给 {@code rootThreadId}。
   */
  @GetMapping
  public ApiResponse<InteractionPageDTO> listInteractions(
      @RequestParam(name = "rootThreadId", required = false) String rootThreadId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit) {
    return ApiResponse.ok(
        RuntimeErrors.translate(
            () -> {
              UUID root =
                  rootThreadId == null ? null : Uuids.parseCanonical(rootThreadId, "rootThreadId");
              return interactionQueryService.listInteractions(
                  root, cursor, RequestParameters.limit(limit, DEFAULT_LIMIT, MAX_LIMIT));
            }));
  }

  /**
   * 提交一次人工问卷回答（或明确拒答）。
   *
   * <p>{@code interactionId} 是待处理列表给出的 Tool invocation ID；{@code threadId}、{@code submissionId} 与
   * {@code answers}/{@code declined} 由请求体给出并严格校验（缺失、形状非法 {@literal ->} 400）。目标不适用或提交身份/答案不匹配
   * {@literal ->} 409；目标不存在 {@literal ->} 404。
   */
  @PostMapping("/{interactionId}/input")
  public ApiResponse<HarnessToolInputResultDTO> submitToolInput(
      @PathVariable String interactionId,
      @RequestBody HarnessToolInputDTO request,
      HttpServletRequest httpRequest) {
    String operator = OperatorResolver.resolve(httpRequest);
    return ApiResponse.ok(
        RuntimeErrors.translate(
            () -> {
              ToolInputSubmissionCommand command =
                  HarnessRuntimeRequestMapper.toToolInputSubmissionCommand(
                      interactionId, request, operator);
              return HarnessRuntimeResponseMapper.toToolInputResultDto(
                  interactionService.submitInput(command));
            }));
  }
}
