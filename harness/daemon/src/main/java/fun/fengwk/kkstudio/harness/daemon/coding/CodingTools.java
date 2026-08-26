package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.daemon.DaemonToolRegistry;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/** 按稳定的能力顺序注册生产环境 Environment coding-tool 集合。 */
public final class CodingTools {

  private CodingTools() {}

  /** 注册 read、write、edit、apply_patch、bash、grep、find 以及三个 LSP 基线工具。 */
  public static void registerAll(
      DaemonToolRegistry registry,
      CodingToolsConfig config,
      ExecutorService executor,
      ScheduledExecutorService scheduler) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(executor, "executor");
    Objects.requireNonNull(scheduler, "scheduler");
    registry.register(new ReadTool(config, executor));
    registry.register(new WriteTool(config, executor));
    registry.register(new EditTool(config, executor));
    registry.register(new ApplyPatchTool(config, executor));
    registry.register(new BashTool(config, executor, scheduler));
    registry.register(new GrepTool(config, executor));
    registry.register(new FindTool(config, executor));
    registry.register(new LspGotoDefinitionTool(config, executor));
    registry.register(new LspWorkspaceSymbolsTool(config, executor));
    registry.register(new LspJavaDecompileTool(config, executor));
  }
}
