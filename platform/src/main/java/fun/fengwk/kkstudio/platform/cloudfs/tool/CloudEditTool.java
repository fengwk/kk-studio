package fun.fengwk.kkstudio.platform.cloudfs.tool;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.builtin.CompletedToolExecutionHandle;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudTextRevision;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudEditAmbiguousException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudEditPatternNotFoundException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudFileSystemException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathForbiddenException;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudFileSystemService;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * {@code cloud_edit} 工具实现。
 *
 * <p>在 Cloud File System 中对已有文本文件执行精确文本替换（CAS 控制，返回带行号的有界上下文 diff）。
 */
public final class CloudEditTool implements Tool {

  public static final String NAME = "cloud_edit";
  public static final String VERSION = "1";

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          VERSION,
          CloudToolPrompts.prompt(NAME),
          NAME,
          CloudToolPrompts.schema(NAME),
          ToolSideEffect.NON_IDEMPOTENT,
          Duration.ofSeconds(30));

  private final CloudFileSystemService fileSystemService;

  public CloudEditTool(CloudFileSystemService fileSystemService) {
    this.fileSystemService = Objects.requireNonNull(fileSystemService, "fileSystemService");
  }

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");

    String oldString = null;
    try {
      JsonNode args = CloudToolArguments.parse(request.call().argumentsJson());
      String rawPath = CloudToolArguments.requireString(args, "path");
      oldString = CloudToolArguments.requireString(args, "old_string");
      if (oldString.isEmpty()) {
        throw new IllegalArgumentException("old_string must not be empty");
      }
      String newString = CloudToolArguments.requireString(args, "new_string");
      if (oldString.equals(newString)) {
        throw new IllegalArgumentException(
            "No changes to apply: old_string and new_string must differ");
      }
      long expectedRevision = CloudToolArguments.requirePositiveLong(args, "expected_revision");
      boolean replaceAll = CloudToolArguments.optionalBoolean(args, "replace_all", false);

      CloudPath path = CloudPath.of(rawPath);
      if (path.isArtifactPath()) {
        throw new CloudPathForbiddenException(
            path, "Modifications to artifact path are forbidden: " + path);
      }

      // 获取当前文本用于生成 diff
      CloudTextRevision current = fileSystemService.readCurrentText(path);
      String oldContent = current.getContent();

      CloudTextRevision newRevision =
          fileSystemService.editText(path, oldString, newString, expectedRevision, replaceAll);
      String diffReport =
          CloudEditDiff.formatDiff(
              path,
              newRevision.getRevision(),
              oldContent,
              newRevision.getContent(),
              oldString,
              newString);

      ToolResult result =
          new ToolResult(
              request.call().id(), List.of(new TextResultContent(diffReport)), false, "{}");
      listener.onComplete(ToolOutcome.withoutEffects(result));
    } catch (CloudFileSystemException | IllegalArgumentException e) {
      // 严格保证错误消息中绝不泄露 old_string
      String safeMessage = sanitizeErrorMessage(e, e.getMessage(), oldString);
      ToolResult errorResult =
          new ToolResult(
              request.call().id(), List.of(new TextResultContent(safeMessage)), true, "{}");
      listener.onComplete(ToolOutcome.withoutEffects(errorResult));
    } catch (Throwable t) {
      listener.onError(new RuntimeException("Unexpected edit failure"));
    }
    return CompletedToolExecutionHandle.INSTANCE;
  }

  private static String sanitizeErrorMessage(Throwable e, String message, String oldString) {
    if (message == null || message.isBlank()) {
      return "Edit failed";
    }
    if (e instanceof CloudEditAmbiguousException
        || e instanceof CloudEditPatternNotFoundException) {
      return message;
    }
    if (oldString != null && !oldString.isEmpty() && message.contains(oldString)) {
      message = message.replace(oldString, "[omitted]");
    }
    return message;
  }
}
