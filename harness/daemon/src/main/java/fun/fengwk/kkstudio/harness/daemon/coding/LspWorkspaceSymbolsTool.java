package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolIntegerSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** Searches workspace symbols through the optional local LSP bridge. */
public final class LspWorkspaceSymbolsTool extends AbstractCodingTool {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 500;

  private final LspBridge bridge;

  public LspWorkspaceSymbolsTool(CodingToolsConfig config) {
    super(
        config,
        new ToolDescriptor(
            "lsp_workspace_symbols",
            "1",
            CodingToolPrompts.load("lsp_workspace_symbols"),
            null,
            new ToolParamsSchema(
                "LspWorkspaceSymbols parameters",
                Map.of(
                    "path",
                        new ToolStringSchema(
                            "Existing source file path used to resolve the workspace root."),
                    "workdir",
                        new ToolStringSchema(
                            "Working directory for resolving relative paths. Defaults to the agent's current working directory. If provided, relative paths resolve from that directory."),
                    "query", new ToolStringSchema("Symbol search query."),
                    "limit",
                        new ToolIntegerSchema(
                            "Maximum number of results to display locally. Default: 50.")),
                Set.of("path", "query"),
                false),
            ToolExecutionMode.ENVIRONMENT,
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(2)));
    this.bridge = new LspBridge(config);
  }

  @Override
  ToolResult run(ToolExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    Path path =
        boundary.existing(string(args, "path"), boundary.workdir(optionalString(args, "workdir")));
    String query = string(args, "query");
    if (query.isBlank()) {
      throw new IllegalArgumentException("query must not be blank");
    }
    int limit = optionalNonNegativeInt(args, "limit", DEFAULT_LIMIT);
    if (limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be <= " + MAX_LIMIT);
    }
    if (execution.isCancelled()) {
      throw new InterruptedException();
    }
    if (!bridge.bridgeAvailable()) {
      throw new IllegalStateException(LspBridge.UNAVAILABLE_MESSAGE);
    }
    return success(request.call().id(), bridge.workspaceSymbols(path, query, limit));
  }
}
