package fun.fengwk.kkstudio.platform.configsync;

import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncKind;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncSkipped;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 已累积 skip 中某一类别的条目名：该集合内的同名条目不再进入可用依赖池。 */
final class ConfigSyncSkippedNames {

  private ConfigSyncSkippedNames() {}

  static Set<String> of(List<ConfigSyncSkipped> skipped, ConfigSyncKind kind) {
    Set<String> names = new LinkedHashSet<>();
    for (ConfigSyncSkipped entry : skipped) {
      if (kind == entry.getKind() && entry.getName() != null && !entry.getName().isEmpty()) {
        names.add(entry.getName());
      }
    }
    return names;
  }
}
