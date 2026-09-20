package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

/** 通过 LSP bridge 反编译或反汇编 Java class（可用时），否则对可解析的目标回退到 {@code javap}。 */
public final class LspJavaDecompileCapability extends AbstractCodingCapability {

  private final LspBridge bridge;

  public LspJavaDecompileCapability(CodingToolsConfig config, ExecutorService executor) {
    super(
        config,
        executor,
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE));
    this.bridge = new LspBridge(config);
  }

  @Override
  EnvironmentCapabilityResult run(
      EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    Path workdir = EnvironmentPaths.workdir(string(args, "workdir"));
    Path path = EnvironmentPaths.existing(string(args, "path"), workdir);
    String target = string(args, "target");
    if (target.isBlank()) {
      throw new IllegalArgumentException("target must not be blank");
    }
    if (execution.isCancelled()) {
      throw new InterruptedException();
    }
    return success(
        request.call().id(),
        bridge.javaDecompile(workdir, path, target, request.timeout(), execution::isCancelled));
  }
}
