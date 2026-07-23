package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolIntegerSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** Resolves a symbol definition through the optional local LSP bridge. */
public final class LspGotoDefinitionTool extends AbstractCodingTool {

  private final LspBridge bridge;

  public LspGotoDefinitionTool(CodingToolsConfig config) {
    super(
        config,
        new ToolDescriptor(
            "lsp_goto_definition",
            "1",
            CodingToolPrompts.load("lsp_goto_definition"),
            null,
            new ToolParamsSchema(
                "LspGotoDefinition parameters",
                Map.of(
                    "path",
                        new ToolStringSchema(
                            "Existing source file path supported by an LSP server. This path is also used to infer the workspace root."),
                    "workdir",
                        new ToolStringSchema(
                            "Working directory for resolving relative paths. Defaults to the agent's current working directory. If provided, relative paths resolve from that directory."),
                    "line", new ToolIntegerSchema("1-based line number for the target position."),
                    "character",
                        new ToolIntegerSchema(
                            "0-based character offset at the target position. Default: 0.")),
                Set.of("path", "line"),
                false),
            ToolExecutionLocation.ENVIRONMENT,
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(2)));
    this.bridge = new LspBridge(config);
  }

  @Override
  ToolResult run(ToolExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    Path path =
        boundary.existing(string(args, "path"), boundary.workdir(optionalString(args, "workdir")));
    if (Files.isDirectory(path)) {
      throw new IllegalArgumentException("path must be a file: " + path);
    }
    int line = requiredPositiveInt(args, "line");
    int character = optionalNonNegativeInt(args, "character", 0);
    if (execution.isCancelled()) {
      throw new InterruptedException();
    }
    if (!bridge.bridgeAvailable()) {
      throw new IllegalStateException(LspBridge.UNAVAILABLE_MESSAGE);
    }
    return success(request.call().id(), bridge.gotoDefinition(path, line, character));
  }
}
