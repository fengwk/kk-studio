package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

/** 通过可选的本机 LSP bridge 解析符号定义。 */
public final class LspGotoDefinitionCapability extends AbstractCodingCapability {

  private final LspBridge bridge;

  public LspGotoDefinitionCapability(CodingToolsConfig config, ExecutorService executor) {
    super(
        config,
        executor,
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.LSP_GOTO_DEFINITION));
    this.bridge = new LspBridge(config);
  }

  @Override
  EnvironmentCapabilityResult run(
      EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    Path path =
        EnvironmentPaths.existing(
            string(args, "path"),
            EnvironmentPaths.workdir(optionalString(args, "workdir"), request.workdir()));
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
