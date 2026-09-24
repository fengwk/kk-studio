package fun.fengwk.kkstudio.platform.project.tool;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.builtin.CompletedToolExecutionHandle;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderer;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** 参数化 Project/Issue 角色工具统一实现。 */
public final class ProjectRoleTool implements Tool {

  private final ProjectRoleToolType type;
  private final ProjectThreadOwnerResolver ownerResolver;
  private final ProjectRoleToolService toolService;

  public ProjectRoleTool(
      ProjectRoleToolType type,
      ProjectThreadOwnerResolver ownerResolver,
      ProjectRoleToolService toolService) {
    this.type = Objects.requireNonNull(type, "type");
    this.ownerResolver = Objects.requireNonNull(ownerResolver, "ownerResolver");
    this.toolService = Objects.requireNonNull(toolService, "toolService");
  }

  public ProjectRoleToolType type() {
    return type;
  }

  @Override
  public ToolDescriptor descriptor() {
    return type.descriptor();
  }

  /** 历史动作由 {@link ProjectHistoryRenderers} 按工具类型生成：只保留动作与相关 issue/status/dependency 身份。 */
  @Override
  public Optional<ToolHistoryRenderer> historyRenderer() {
    return Optional.of(ProjectHistoryRenderers.of(type));
  }

  @Override
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");

    AtomicBoolean handled = new AtomicBoolean(false);
    try {
      ToolExecutionContext context = request.context();
      if (context == null || context.threadId() == null) {
        handleError(request.call().id(), "Missing invocation context", listener, handled);
        return CompletedToolExecutionHandle.INSTANCE;
      }

      Optional<ProjectThreadOwnerContext> ownerOpt = ownerResolver.resolve(context.threadId());
      if (ownerOpt.isEmpty() || !type.isAllowedFor(ownerOpt.get().role())) {
        handleError(
            request.call().id(),
            "Tool not permitted for current role or unowned session",
            listener,
            handled);
        return CompletedToolExecutionHandle.INSTANCE;
      }

      ProjectThreadOwnerContext owner = ownerOpt.get();
      JsonNode args = ProjectToolExecutionSupport.parseJson(request.call().argumentsJson());
      Map<String, Object> outcome = dispatch(owner, context, args);
      String json = ProjectToolExecutionSupport.toJson(outcome);
      ToolResult result =
          new ToolResult(request.call().id(), List.of(new TextResultContent(json)), false, "{}");

      if (handled.compareAndSet(false, true)) {
        listener.onComplete(ToolOutcome.withoutEffects(result));
      }
    } catch (AiValidationException
        | AiResourceNotFoundException
        | AiVersionConflictException
        | IllegalArgumentException
        | IllegalStateException e) {
      handleError(
          request.call().id(),
          ProjectToolExecutionSupport.sanitizeErrorMessage(e),
          listener,
          handled);
    } catch (Exception t) {
      if (handled.compareAndSet(false, true)) {
        listener.onError(new IllegalStateException("Project role tool execution failed"));
      }
    }
    return CompletedToolExecutionHandle.INSTANCE;
  }

  private Map<String, Object> dispatch(
      ProjectThreadOwnerContext owner, ToolExecutionContext context, JsonNode args) {
    return switch (type) {
      case ISSUE_READ -> toolService.issueRead(
          owner,
          ProjectToolExecutionSupport.optionalUuid(args, "issue_id"),
          ProjectToolExecutionSupport.optionalLong(args, "activity_after_sequence"),
          ProjectToolExecutionSupport.optionalInt(args, "activity_limit"));
      case ISSUE_REQUEST_INPUT -> {
        String question = ProjectToolExecutionSupport.requireNonBlankString(args, "question");
        String requestContext = ProjectToolExecutionSupport.optionalText(args, "context");
        yield toolService.issueRequestInput(owner, question, requestContext);
      }
      case ISSUE_REVIEW -> {
        String decisionText = ProjectToolExecutionSupport.requireNonBlankString(args, "decision");
        ReviewDecision decision;
        try {
          decision = ReviewDecision.valueOf(decisionText);
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException(
              "Invalid decision: only APPROVE or REQUEST_CHANGES is allowed");
        }
        String reason = ProjectToolExecutionSupport.requireNonBlankString(args, "reason");
        // 决定幂等键绑定本次工具调用，业务效果先落地；ToolResult 失败也不重复决定。
        yield toolService.issueReview(owner, "tool:" + context.invocationId(), decision, reason);
      }
    };
  }

  private void handleError(
      String callId, String message, ToolExecutionListener listener, AtomicBoolean handled) {
    if (handled.compareAndSet(false, true)) {
      ToolResult errorResult =
          new ToolResult(callId, List.of(new TextResultContent(message)), true, "{}");
      listener.onComplete(ToolOutcome.withoutEffects(errorResult));
    }
  }
}
