package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.daemon.DaemonToolRegistry;

import java.util.Objects;

/** Registers the production Environment coding-tool set in stable capability order. */
public final class CodingTools {

  private CodingTools() {}

  /** Registers read, write, edit, bash, grep, and find. */
  public static void registerAll(DaemonToolRegistry registry, CodingToolsConfig config) {
    Objects.requireNonNull(registry, "registry");
    registry.register(new ReadTool(config));
    registry.register(new WriteTool(config));
    registry.register(new EditTool(config));
    registry.register(new BashTool(config));
    registry.register(new GrepTool(config));
    registry.register(new FindTool(config));
  }
}
