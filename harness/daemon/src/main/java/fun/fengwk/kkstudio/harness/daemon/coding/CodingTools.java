package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.daemon.DaemonToolRegistry;

import java.util.Objects;

/** 按稳定的能力顺序注册生产环境 Environment coding-tool 集合。 */
public final class CodingTools {

  private CodingTools() {}

  /** 注册 read、write、edit、apply_patch、bash、grep、find 以及三个 LSP 基线工具。 */
  public static void registerAll(DaemonToolRegistry registry, CodingToolsConfig config) {
    Objects.requireNonNull(registry, "registry");
    registry.register(new ReadTool(config));
    registry.register(new WriteTool(config));
    registry.register(new EditTool(config));
    registry.register(new ApplyPatchTool(config));
    registry.register(new BashTool(config));
    registry.register(new GrepTool(config));
    registry.register(new FindTool(config));
    registry.register(new LspGotoDefinitionTool(config));
    registry.register(new LspWorkspaceSymbolsTool(config));
    registry.register(new LspJavaDecompileTool(config));
  }
}
