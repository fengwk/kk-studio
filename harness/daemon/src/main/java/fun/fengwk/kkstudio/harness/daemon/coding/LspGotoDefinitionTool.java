package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

/** 通过可选的本机 LSP bridge 解析符号定义。 */
public final class LspGotoDefinitionTool extends AbstractCodingTool {

  private final LspBridge bridge;

  public LspGotoDefinitionTool(CodingToolsConfig config, ExecutorService executor) {
    super(config, executor, EnvironmentToolCatalog.require("lsp_goto_definition"));
    this.bridge = new LspBridge(config);
  }

  @Override
  ToolResult run(ToolExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    Path path =
        boundary.existing(
            string(args, "path"),
            boundary.workdir(optionalString(args, "workdir"), request.workdir()));
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
