package fun.fengwk.kkstudio.harness.plugin.api;

import java.util.Objects;

/** 插件 Tool 对所属插件某一 customType 的访问声明。 */
public record PluginStateDeclaration(String customType, PluginStateMode mode) {

  public PluginStateDeclaration {
    customType = Identifiers.requireCanonical(customType, "customType");
    mode = Objects.requireNonNull(mode, "mode");
  }
}
