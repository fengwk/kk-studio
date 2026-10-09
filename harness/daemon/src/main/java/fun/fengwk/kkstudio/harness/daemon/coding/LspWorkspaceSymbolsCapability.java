package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

/**
 * 通过 Daemon 管理的 LSP 客户端搜索 workspace symbols。 绝对 {@code path} 不需要 workdir；只有相对 {@code path}
 * 必须由本次调用显式给出绝对 workdir。
 */
public final class LspWorkspaceSymbolsCapability extends AbstractCodingCapability {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 500;

  private final LspService lsp;

  public LspWorkspaceSymbolsCapability(
      CodingToolsConfig config, LspService lsp, ExecutorService executor) {
    super(
        config,
        executor,
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS));
    this.lsp = lsp;
  }

  @Override
  EnvironmentCapabilityResult run(
      EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    Path path = EnvironmentPaths.existing(string(args, "path"));
    String query = string(args, "query");
    if (query.isBlank()) {
      throw new ToolInputRejectedException("query must not be blank");
    }
    int limit = optionalNonNegativeInt(args, "limit", DEFAULT_LIMIT);
    if (limit > MAX_LIMIT) {
      throw new ToolInputRejectedException("limit must be <= " + MAX_LIMIT);
    }
    if (execution.isCancelled()) {
      throw new InterruptedException();
    }
    return success(
        request.call().id(), lsp.workspaceSymbols(path, query, limit, request.timeout()));
  }
}
