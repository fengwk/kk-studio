package fun.fengwk.kkstudio.harness.plugin;

import java.util.Objects;

/**
 * 插件内贡献的 owner-qualified 稳定身份：{@code (pluginId, localName)}。
 *
 * <p>{@code localName} 是 canonical 小写 dotted/dashed 标识符，只在所属插件内唯一；不同插件可以使用相同 localName（如 {@code
 * state}）。贡献身份由 scoped registrar 在注册时构造，插件无法伪造其它插件的身份。
 */
public record ContributionId(PluginId pluginId, String localName) {

  public ContributionId {
    pluginId = Objects.requireNonNull(pluginId, "pluginId");
    Identifiers.requireCanonical(localName, "contributionId.localName");
  }

  @Override
  public String toString() {
    return pluginId + ":" + localName;
  }
}
