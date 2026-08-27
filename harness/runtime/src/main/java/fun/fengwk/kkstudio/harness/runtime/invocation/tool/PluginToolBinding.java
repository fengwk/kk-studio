package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 插件 PLUGIN Tool 的冻结 provenance 与 branch state 访问声明。
 *
 * <p>{@code pluginId + contributionLocalName} 在重启后精确恢复贡献 owner；state accesses 用于在执行前拒绝同一 Assistant
 * 内必然读取陈旧快照的 sibling 组合。
 */
public record PluginToolBinding(
    String pluginId, String contributionLocalName, List<PluginStateAccess> stateAccesses) {

  public PluginToolBinding {
    pluginId = ToolBindingIdentifiers.requireCanonical(pluginId, "pluginId");
    contributionLocalName =
        ToolBindingIdentifiers.requireCanonical(contributionLocalName, "contributionLocalName");
    stateAccesses = List.copyOf(Objects.requireNonNull(stateAccesses, "stateAccesses"));
    Set<String> customTypes = new HashSet<>();
    for (PluginStateAccess access : stateAccesses) {
      Objects.requireNonNull(access, "stateAccesses[]");
      if (!customTypes.add(access.customType())) {
        throw new IllegalArgumentException(
            "duplicate plugin state access customType: " + access.customType());
      }
    }
  }
}
