package fun.fengwk.kkstudio.platform.harness.read;

import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.common.json.ToolArguments;
import fun.fengwk.kkstudio.common.resource.ResourceUri;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.spi.ResourceReadGateway;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.storage.ReadDeadline;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Platform reader for durable {@code resource://} references. */
public final class PlatformResourceReadGateway implements ResourceReadGateway {

  private final PlatformResourceContentReader reader;

  public PlatformResourceReadGateway(PlatformResourceContentReader reader) {
    this.reader = Objects.requireNonNull(reader, "reader");
  }

  @Override
  public ToolResult read(ToolExecutionRequest request, Consumer<ToolResult> progress) {
    String callId = request.call().id();
    try {
      JsonNode arguments = ToolArguments.parse(request.call().argumentsJson());
      String uri = ToolArguments.text(arguments, "path");
      ResourceUri.Parsed resource = ResourceUri.parse(uri);
      if (resource.namespace() == ResourceUri.Namespace.SKILLS) {
        return ToolResult.error(callId, "skill resources are not available yet");
      }
      ToolExecutionContext context = request.context();
      if (context == null) {
        return ToolResult.error(callId, "resource URI requires a tool execution context");
      }
      var result =
          reader.readResource(
              context.rootSessionId(),
              context.mediaCapabilities(),
              resource.fileId(),
              positiveInt(arguments, "offset"),
              positiveInt(arguments, "limit"),
              positiveInt(arguments, "column_offset"),
              uri,
              ReadDeadline.after(request.timeout()));
      return switch (result) {
        case PlatformResourceContentReader.ResourceRead.Text text -> new ToolResult(
            callId, List.of(new TextResultContent(text.text())), false, "{}");
        case PlatformResourceContentReader.ResourceRead.Media media -> new ToolResult(
            callId,
            List.of(new ResourceResultContent(media.resource(), media.preview())),
            false,
            "{}");
      };
    } catch (PlatformReadException | IllegalArgumentException failure) {
      return ToolResult.error(callId, failure.getMessage());
    }
  }

  private static Integer positiveInt(JsonNode arguments, String field) {
    if (arguments == null) {
      return null;
    }
    JsonNode value = arguments.get(field);
    if (value == null || !value.isInt()) {
      return null;
    }
    int parsed = value.intValue();
    return parsed > 0 ? parsed : null;
  }
}
