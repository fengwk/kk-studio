package fun.fengwk.kkstudio.platform.project.tool;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.builtin.CompletedToolExecutionHandle;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
      if (ownerOpt.isEmpty() || ownerOpt.get().role() != type.requiredRole()) {
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
      case PROJECT_READ -> toolService.projectRead(owner);
      case ISSUE_READ -> {
        UUID issueId =
            ProjectToolExecutionSupport.parseUuid(
                ProjectToolExecutionSupport.requireNonBlankString(args, "issue_id"), "issue_id");
        yield toolService.issueRead(owner, issueId);
      }
      case ISSUE_LIST -> {
        boolean includeArchived =
            args.has("include_archived") && args.get("include_archived").asBoolean();
        IssueStatus status = null;
        if (args.has("status") && !args.get("status").isNull()) {
          String statusStr = args.get("status").asText();
          try {
            status = IssueStatus.valueOf(statusStr);
          } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid status");
          }
        }
        yield toolService.issueList(owner, status, includeArchived);
      }
      case ISSUE_CREATE -> {
        String title = ProjectToolExecutionSupport.requireNonBlankString(args, "title");
        String description =
            args.has("description") && !args.get("description").isNull()
                ? args.get("description").asText()
                : "";
        String assignee =
            args.has("assignee_agent_name") && !args.get("assignee_agent_name").isNull()
                ? args.get("assignee_agent_name").asText()
                : null;
        String reviewer =
            args.has("reviewer_agent_name") && !args.get("reviewer_agent_name").isNull()
                ? args.get("reviewer_agent_name").asText()
                : null;
        IssueStatus initialStatus = null;
        if (args.has("initial_status") && !args.get("initial_status").isNull()) {
          String statusStr = args.get("initial_status").asText();
          try {
            initialStatus = IssueStatus.valueOf(statusStr);
          } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Invalid initial_status: only BACKLOG or TODO is allowed");
          }
        }
        yield toolService.issueCreate(owner, title, description, assignee, reviewer, initialStatus);
      }
      case ISSUE_UPDATE -> {
        UUID issueId =
            ProjectToolExecutionSupport.parseUuid(
                ProjectToolExecutionSupport.requireNonBlankString(args, "issue_id"), "issue_id");
        long expectedVersion =
            ProjectToolExecutionSupport.requireNonNegativeLong(args, "expected_version");
        boolean hasTitle = args.has("title");
        String title = hasTitle ? args.get("title").asText() : null;
        boolean hasDescription = args.has("description");
        String description = hasDescription ? args.get("description").asText() : null;
        boolean hasAssignee = args.has("assignee_agent_name");
        String assignee = hasAssignee ? args.get("assignee_agent_name").asText() : null;
        boolean hasReviewer = args.has("reviewer_agent_name");
        String reviewer = hasReviewer ? args.get("reviewer_agent_name").asText() : null;
        ProjectRoleToolService.UpdateIssueCommand cmd =
            new ProjectRoleToolService.UpdateIssueCommand(
                hasTitle,
                title,
                hasDescription,
                description,
                hasAssignee,
                assignee,
                hasReviewer,
                reviewer);
        yield toolService.issueUpdate(owner, issueId, expectedVersion, cmd);
      }
      case ISSUE_ADD_DEPENDENCY -> {
        UUID issueId =
            ProjectToolExecutionSupport.parseUuid(
                ProjectToolExecutionSupport.requireNonBlankString(args, "issue_id"), "issue_id");
        UUID dependsOnIssueId =
            ProjectToolExecutionSupport.parseUuid(
                ProjectToolExecutionSupport.requireNonBlankString(args, "depends_on_issue_id"),
                "depends_on_issue_id");
        long expectedVersion =
            ProjectToolExecutionSupport.requireNonNegativeLong(args, "expected_version");
        yield toolService.issueAddDependency(owner, issueId, dependsOnIssueId, expectedVersion);
      }
      case ISSUE_REMOVE_DEPENDENCY -> {
        UUID issueId =
            ProjectToolExecutionSupport.parseUuid(
                ProjectToolExecutionSupport.requireNonBlankString(args, "issue_id"), "issue_id");
        UUID dependsOnIssueId =
            ProjectToolExecutionSupport.parseUuid(
                ProjectToolExecutionSupport.requireNonBlankString(args, "depends_on_issue_id"),
                "depends_on_issue_id");
        long expectedVersion =
            ProjectToolExecutionSupport.requireNonNegativeLong(args, "expected_version");
        yield toolService.issueRemoveDependency(owner, issueId, dependsOnIssueId, expectedVersion);
      }
      case ISSUE_SET_STATUS -> {
        UUID issueId =
            ProjectToolExecutionSupport.parseUuid(
                ProjectToolExecutionSupport.requireNonBlankString(args, "issue_id"), "issue_id");
        String statusStr = ProjectToolExecutionSupport.requireNonBlankString(args, "status");
        IssueStatus status;
        try {
          status = IssueStatus.valueOf(statusStr);
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException("Invalid status: only BACKLOG or TODO is allowed");
        }
        long expectedVersion =
            ProjectToolExecutionSupport.requireNonNegativeLong(args, "expected_version");
        yield toolService.issueSetStatus(owner, issueId, status, expectedVersion);
      }
      case ISSUE_CANCEL -> {
        UUID issueId =
            ProjectToolExecutionSupport.parseUuid(
                ProjectToolExecutionSupport.requireNonBlankString(args, "issue_id"), "issue_id");
        long expectedVersion =
            ProjectToolExecutionSupport.requireNonNegativeLong(args, "expected_version");
        String reason =
            args.has("reason") && !args.get("reason").isNull() ? args.get("reason").asText() : null;
        yield toolService.issueCancel(owner, issueId, expectedVersion, reason);
      }
      case ISSUE_SUBMIT -> {
        long observedSpecRevision =
            ProjectToolExecutionSupport.requireNonNegativeLong(args, "observed_spec_revision");
        long observedInputSequence =
            ProjectToolExecutionSupport.requireNonNegativeLong(args, "observed_input_sequence");
        String summary = ProjectToolExecutionSupport.requireNonBlankString(args, "summary");
        String verification =
            args.has("verification") && !args.get("verification").isNull()
                ? args.get("verification").asText()
                : null;
        String terminalActionId = "tool:" + context.invocationId().toString();
        yield toolService.issueSubmit(
            owner,
            terminalActionId,
            observedSpecRevision,
            observedInputSequence,
            summary,
            verification);
      }
      case ISSUE_REQUEST_INPUT -> {
        long observedSpecRevision =
            ProjectToolExecutionSupport.requireNonNegativeLong(args, "observed_spec_revision");
        long observedInputSequence =
            ProjectToolExecutionSupport.requireNonNegativeLong(args, "observed_input_sequence");
        String question = ProjectToolExecutionSupport.requireNonBlankString(args, "question");
        String ctx =
            args.has("context") && !args.get("context").isNull()
                ? args.get("context").asText()
                : null;
        yield toolService.issueRequestInput(
            owner, observedSpecRevision, observedInputSequence, question, ctx);
      }
      case ISSUE_REVIEW -> {
        long observedSpecRevision =
            ProjectToolExecutionSupport.requireNonNegativeLong(args, "observed_spec_revision");
        long observedInputSequence =
            ProjectToolExecutionSupport.requireNonNegativeLong(args, "observed_input_sequence");
        String decisionStr = ProjectToolExecutionSupport.requireNonBlankString(args, "decision");
        ReviewDecision decision;
        try {
          decision = ReviewDecision.valueOf(decisionStr);
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException(
              "Invalid decision: only APPROVE or REQUEST_CHANGES is allowed");
        }
        String summary = ProjectToolExecutionSupport.requireNonBlankString(args, "summary");
        String verification =
            args.has("verification") && !args.get("verification").isNull()
                ? args.get("verification").asText()
                : null;
        String terminalActionId = "tool:" + context.invocationId().toString();
        yield toolService.issueReview(
            owner,
            terminalActionId,
            observedSpecRevision,
            observedInputSequence,
            decision,
            summary,
            verification);
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
