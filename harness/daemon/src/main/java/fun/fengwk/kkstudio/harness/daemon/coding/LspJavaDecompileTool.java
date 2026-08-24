package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

/** 通过 LSP bridge 反编译或反汇编 Java class（可用时），否则对可解析的目标回退到 {@code javap}。 */
public final class LspJavaDecompileTool extends AbstractCodingTool {

  private final LspBridge bridge;

  public LspJavaDecompileTool(CodingToolsConfig config, ExecutorService executor) {
    super(config, executor, EnvironmentToolCatalog.require("lsp_java_decompile"));
    this.bridge = new LspBridge(config);
  }

  @Override
  ToolResult run(ToolExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    Path path =
        boundary.existing(
            string(args, "path"),
            boundary.workdir(optionalString(args, "workdir"), request.workdir()));
    String target = string(args, "target");
    if (target.isBlank()) {
      throw new IllegalArgumentException("target must not be blank");
    }
    if (execution.isCancelled()) {
      throw new InterruptedException();
    }
    return success(request.call().id(), bridge.javaDecompile(path, target));
  }
}
