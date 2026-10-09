package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

/**
 * 通过 Daemon 管理的 LSP 客户端获取 Java class 源码（jdtls 反编译），不做 {@code javap} 字节码回退。
 *
 * <p>绝对 {@code path} 不需要 workdir；相对 {@code path} 必须显式给出绝对 workdir。{@code target} 推荐 {@code jdt://}
 * URI 或绝对 class 路径，这两种形态不依赖 workdir；相对 class 路径只在显式 workdir 下解析。
 */
public final class LspJavaDecompileCapability extends AbstractCodingCapability {

  private final LspService lsp;

  public LspJavaDecompileCapability(
      CodingToolsConfig config, LspService lsp, ExecutorService executor) {
    super(
        config,
        executor,
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE));
    this.lsp = lsp;
  }

  @Override
  EnvironmentCapabilityResult run(
      EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    Path path = EnvironmentPaths.existing(string(args, "path"));
    String target = string(args, "target");
    if (target.isBlank()) {
      throw new ToolInputRejectedException("target must not be blank");
    }
    if (execution.isCancelled()) {
      throw new InterruptedException();
    }
    return success(request.call().id(), lsp.javaDecompile(path, target, request.timeout()));
  }
}
