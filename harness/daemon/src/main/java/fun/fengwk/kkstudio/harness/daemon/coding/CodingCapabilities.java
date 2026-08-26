package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.daemon.DaemonCapabilityRegistry;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/** 按稳定的能力顺序注册生产环境 Environment capability 集合。 */
public final class CodingCapabilities {

  private CodingCapabilities() {}

  /** 注册文件、进程和三个 LSP 基线 capability。 */
  public static void registerAll(
      DaemonCapabilityRegistry registry,
      CodingToolsConfig config,
      ExecutorService executor,
      ScheduledExecutorService scheduler) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(executor, "executor");
    Objects.requireNonNull(scheduler, "scheduler");
    registry.register(new ReadCapability(config, executor));
    registry.register(new WriteCapability(config, executor));
    registry.register(new EditCapability(config, executor));
    registry.register(new ApplyPatchCapability(config, executor));
    registry.register(new BashCapability(config, executor, scheduler));
    registry.register(new GrepCapability(config, executor));
    registry.register(new FindCapability(config, executor));
    registry.register(new LspGotoDefinitionCapability(config, executor));
    registry.register(new LspWorkspaceSymbolsCapability(config, executor));
    registry.register(new LspJavaDecompileCapability(config, executor));
  }
}
