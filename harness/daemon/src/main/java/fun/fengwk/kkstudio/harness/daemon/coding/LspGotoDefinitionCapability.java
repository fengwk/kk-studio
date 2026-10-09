package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

/**
 * 通过 Daemon 管理的 LSP 客户端解析符号定义。 绝对 {@code path} 不需要 workdir；只有相对 {@code path} 必须由本次调用显式给出绝对 workdir。
 */
public final class LspGotoDefinitionCapability extends AbstractCodingCapability {

  private final LspService lsp;

  public LspGotoDefinitionCapability(
      CodingToolsConfig config, LspService lsp, ExecutorService executor) {
    super(
        config,
        executor,
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.LSP_GOTO_DEFINITION));
    this.lsp = lsp;
  }

  @Override
  EnvironmentCapabilityResult run(
      EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    Path path = EnvironmentPaths.existing(string(args, "path"));
    if (Files.isDirectory(path)) {
      throw new ToolInputRejectedException("path must be a file: " + path);
    }
    int line = requiredPositiveInt(args, "line");
    int character = optionalNonNegativeInt(args, "character", 0);
    if (execution.isCancelled()) {
      throw new InterruptedException();
    }
    return success(
        request.call().id(), lsp.gotoDefinition(path, line, character, request.timeout()));
  }
}
