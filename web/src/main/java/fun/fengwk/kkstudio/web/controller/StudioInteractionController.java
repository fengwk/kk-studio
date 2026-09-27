package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.harness.runtime.ToolInputSubmissionCommand;
import fun.fengwk.kkstudio.platform.interaction.InteractionQueryService;
import fun.fengwk.kkstudio.platform.interaction.InteractionService;
import fun.fengwk.kkstudio.share.ai.interaction.HarnessToolInputDTO;
import fun.fengwk.kkstudio.share.ai.interaction.HarnessToolInputResultDTO;
import fun.fengwk.kkstudio.share.ai.interaction.InteractionPageDTO;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapper;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeResponseMapper;

import java.security.Principal;
import java.util.Objects;

/**
 * 统一人工交互 API：一个入口同时覆盖 Chat 与 Issue+Agent 的问卷回答。
 *
 * <p>客户端不需要预先知道 Interaction 属于哪种产品 owner：只提交 {@code interactionId} 与 {@code threadId}，由 platform 交互服务
 * 解析产品归属、按产品锁序串行化后再写入 Harness。操作者身份只来自服务端认证上下文，请求体永远不能指定 actor。
 */
@RestController
@RequestMapping("/api/interactions")
public class StudioInteractionController {

  /** 单用户部署边界下没有认证主体时使用的固定操作者，保证回执 actor 始终由服务端产生。 */
  private static final String LOCAL_OPERATOR = "local-user";

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

  /** 查询一页待处理 Interaction（问卷等待与审批等待合并，按 {@code (createTime, interactionId)} 稳定升序）。 */
  @GetMapping
  public Result<InteractionPageDTO> listInteractions(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit) {
    return Results.ok(
        StudioHarnessThreadController.withRuntimeTranslation(
            () -> interactionQueryService.listInteractions(cursor, parseLimit(limit))));
  }

  /**
   * 提交一次人工问卷回答（或明确拒答）。
   *
   * <p>{@code interactionId} 是待处理列表给出的 Tool invocation ID；{@code threadId}、{@code submissionId} 与 {@code
   * answers}/{@code declined} 由请求体给出并严格校验（缺失、形状非法 {@literal ->} 400）。目标不适用或提交身份/答案不匹配
   * {@literal ->} 409；目标不存在 {@literal ->} 404。
   */
  @PostMapping("/{interactionId}/input")
  public Result<HarnessToolInputResultDTO> submitToolInput(
      @PathVariable String interactionId,
      @RequestBody HarnessToolInputDTO request,
      HttpServletRequest httpRequest) {
    String operator = resolveOperator(httpRequest);
    return Results.ok(
        StudioHarnessThreadController.withRuntimeTranslation(
            () -> {
              ToolInputSubmissionCommand command =
                  HarnessRuntimeRequestMapper.toToolInputSubmissionCommand(
                      interactionId, request, operator);
              return HarnessRuntimeResponseMapper.toToolInputResultDto(
                  interactionService.submitInput(command));
            }));
  }

  /** 解析分页上限：缺省 {@value DEFAULT_LIMIT}，显式给出时必须是 {@code [1, }{@value MAX_LIMIT}{@code ]} 内的十进制整数。 */
  private static int parseLimit(String limit) {
    if (limit == null) {
      return DEFAULT_LIMIT;
    }
    int parsed;
    try {
      parsed = Integer.parseInt(limit);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException("limit must be a decimal integer", error);
    }
    if (parsed < 1 || parsed > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be within [1, " + MAX_LIMIT + "]");
    }
    return parsed;
  }

  /** 操作者只取自部署边界建立的认证主体；没有主体时回退到单用户边界的固定操作者，绝不信任客户端传入的身份。 */
  private static String resolveOperator(HttpServletRequest request) {
    Principal principal = request.getUserPrincipal();
    if (principal != null) {
      String name = principal.getName();
      if (name != null && !name.isBlank()) {
        return name.strip();
      }
    }
    return LOCAL_OPERATOR;
  }
}
