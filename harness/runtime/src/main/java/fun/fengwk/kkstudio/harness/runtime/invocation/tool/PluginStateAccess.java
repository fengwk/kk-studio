package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import java.util.Objects;

/** 插件 Tool 对所属插件某一 customType 的冻结访问声明。 */
public record PluginStateAccess(String customType, PluginStateAccessMode mode) {

  public PluginStateAccess {
    customType = ToolBindingIdentifiers.requireCanonical(customType, "customType");
    mode = Objects.requireNonNull(mode, "mode");
  }
}
