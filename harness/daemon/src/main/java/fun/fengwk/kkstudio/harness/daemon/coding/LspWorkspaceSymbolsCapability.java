package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

/** 通过可选的本机 LSP bridge 搜索 workspace symbols。 */
public final class LspWorkspaceSymbolsCapability extends AbstractCodingCapability {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 500;

  private final LspBridge bridge;

  public LspWorkspaceSymbolsCapability(CodingToolsConfig config, ExecutorService executor) {
    super(
        config,
        executor,
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS));
    this.bridge = new LspBridge(config);
  }

  @Override
  EnvironmentCapabilityResult run(
      EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    Path workdir = EnvironmentPaths.workdir(string(args, "workdir"));
    Path path = EnvironmentPaths.existing(string(args, "path"), workdir);
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
    return success(request.call().id(), bridge.workspaceSymbols(workdir, path, query, limit));
  }
}
