package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;

import java.nio.file.Path;

/** 通过可选的本机 LSP bridge 搜索 workspace symbols。 */
public final class LspWorkspaceSymbolsTool extends AbstractCodingTool {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 500;

  private final LspBridge bridge;

  public LspWorkspaceSymbolsTool(CodingToolsConfig config) {
    super(config, EnvironmentToolCatalog.require("lsp_workspace_symbols"));
    this.bridge = new LspBridge(config);
  }

  @Override
  ToolResult run(ToolExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    Path path =
        boundary.existing(
            string(args, "path"),
            boundary.workdir(optionalString(args, "workdir"), request.workdir()));
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
