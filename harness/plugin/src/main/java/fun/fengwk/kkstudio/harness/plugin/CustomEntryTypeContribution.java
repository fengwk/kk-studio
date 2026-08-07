package fun.fengwk.kkstudio.harness.plugin;

import java.util.Objects;

/**
 * 冻结后的自定义 Entry type ownership：scoped 身份与 ownership 键 customType；ownership 实际是 {@code
 * (id.pluginId(), customType)}，不同插件可以各自拥有同名 customType。
 */
public record CustomEntryTypeContribution(ContributionId id, String customType) {

  public CustomEntryTypeContribution {
    id = Objects.requireNonNull(id, "id");
    customType = Objects.requireNonNull(customType, "customType");
  }
}
