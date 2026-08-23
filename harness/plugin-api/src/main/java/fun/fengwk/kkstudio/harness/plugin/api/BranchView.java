package fun.fengwk.kkstudio.harness.plugin.api;

import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 不可变分支视图：基于 root-to-head {@link EntryPath} 的只读投影，供插件读取自定义状态。
 *
 * <p>视图不暴露 HarnessStore / gateway / transaction；插件只能按结构化键 {@code (pluginId, customType)} 匹配读取
 * CUSTOM Entry 的 payload 列表（root-to-head 顺序）。路径由调用方保证已通过 EntryPath 构造校验。
 */
public record BranchView(EntryPath path) {

  public BranchView {
    path = Objects.requireNonNull(path, "path");
  }

  /** 返回路径上 {@code (pluginId, customType)} 匹配的 CUSTOM Entry payload 列表（root-to-head 顺序）。 */
  public List<CustomEntryPayload> customEntries(PluginId pluginId, String customType) {
    Objects.requireNonNull(pluginId, "pluginId");
    Identifiers.requireCanonical(customType, "customType");
    List<CustomEntryPayload> matches = new ArrayList<>();
    for (Entry entry : path.entries()) {
      if (entry.payload() instanceof CustomEntryPayload custom
          && custom.pluginId().equals(pluginId.value())
          && custom.customType().equals(customType)) {
        matches.add(custom);
      }
    }
    return List.copyOf(matches);
  }

  /** 返回路径上 {@code (pluginId, customType)} 匹配的最新（head 最近）CUSTOM Entry payload；没有匹配返回 empty。 */
  public Optional<CustomEntryPayload> latestCustomEntry(PluginId pluginId, String customType) {
    Objects.requireNonNull(pluginId, "pluginId");
    Identifiers.requireCanonical(customType, "customType");
    for (int i = path.entries().size() - 1; i >= 0; i--) {
      if (path.entries().get(i).payload() instanceof CustomEntryPayload custom
          && custom.pluginId().equals(pluginId.value())
          && custom.customType().equals(customType)) {
        return Optional.of(custom);
      }
    }
    return Optional.empty();
  }
}
