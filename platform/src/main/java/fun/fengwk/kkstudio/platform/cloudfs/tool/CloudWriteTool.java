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
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudFileSystemException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathForbiddenException;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudFileSystemService;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * {@code cloud_write} 工具实现。
 *
 * <p>在 Cloud File System 中创建新文本文件或覆盖既有文本文件（受 CAS expected_revision 保护）。
 */
public final class CloudWriteTool implements Tool {

  public static final String NAME = "cloud_write";
  public static final String VERSION = "1";

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          VERSION,
          CloudToolPrompts.prompt(NAME),
          NAME,
          CloudToolPrompts.schema(NAME),
          ToolSideEffect.IDEMPOTENT,
          Duration.ofSeconds(30));

  private final CloudFileSystemService fileSystemService;

  public CloudWriteTool(CloudFileSystemService fileSystemService) {
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

    try {
      JsonNode args = CloudToolArguments.parse(request.call().argumentsJson());
      String rawPath = CloudToolArguments.requireString(args, "path");
      String content = CloudToolArguments.requireString(args, "content");
      long expectedRevision = CloudToolArguments.requireNonNegativeLong(args, "expected_revision");

      CloudPath path = CloudPath.of(rawPath);
      if (path.isArtifactPath()) {
        throw new CloudPathForbiddenException(
            path, "Modifications to artifact path are forbidden: " + path);
      }

      CloudTextRevision revision = fileSystemService.writeText(path, content, expectedRevision);
      String text = "path: " + path.value() + "\nrevision: " + revision.getRevision();
      ToolResult result =
          new ToolResult(request.call().id(), List.of(new TextResultContent(text)), false, "{}");
      listener.onComplete(ToolOutcome.withoutEffects(result));
    } catch (CloudFileSystemException | IllegalArgumentException e) {
      ToolResult errorResult =
          new ToolResult(
              request.call().id(), List.of(new TextResultContent(e.getMessage())), true, "{}");
      listener.onComplete(ToolOutcome.withoutEffects(errorResult));
    } catch (Exception e) {
      listener.onError(e);
    }
    return CompletedToolExecutionHandle.INSTANCE;
  }
}
