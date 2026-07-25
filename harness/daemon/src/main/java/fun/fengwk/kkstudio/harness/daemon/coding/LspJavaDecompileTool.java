package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Decompiles or disassembles a Java class via LSP bridge when available, otherwise {@code javap}
 * for resolvable targets.
 */
public final class LspJavaDecompileTool extends AbstractCodingTool {

  private final LspBridge bridge;

  public LspJavaDecompileTool(CodingToolsConfig config) {
    super(
        config,
        new ToolDescriptor(
            "lsp_java_decompile",
            "1",
            CodingToolPrompts.load("lsp_java_decompile"),
            null,
            new ToolParamsSchema(
                "LspJavaDecompile parameters",
                Map.of(
                    "path",
                        new ToolStringSchema(
                            "Any local `.java` file in the target workspace. This path is used to infer the workspace root and locate JDTLS."),
                    "workdir",
                        new ToolStringSchema(
                            "Working directory for resolving relative paths. Defaults to the agent's current working directory. If provided, relative paths resolve from that directory."),
                    "target",
                        new ToolStringSchema(
                            "A raw `jdt://` URI, a workspace symbol output line, or a `file://` / `.class` path.")),
                Set.of("path", "target"),
                false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(2)));
    this.bridge = new LspBridge(config);
  }

  @Override
  ToolResult run(ToolExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    Path path =
        boundary.existing(string(args, "path"), boundary.workdir(optionalString(args, "workdir")));
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
