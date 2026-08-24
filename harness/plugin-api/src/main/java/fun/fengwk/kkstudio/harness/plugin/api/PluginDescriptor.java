package fun.fengwk.kkstudio.harness.plugin.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 插件的静态元数据：canonical id、展示名与版本。 */
public record PluginDescriptor(PluginId id, String name, String version, Set<PluginId> requires) {

  public PluginDescriptor {
    id = Objects.requireNonNull(id, "id");
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (version == null || version.isBlank()) {
      throw new IllegalArgumentException("version must not be blank");
    }
    Objects.requireNonNull(requires, "requires");
    List<PluginId> sortedRequires = new ArrayList<>(requires.size());
    for (PluginId required : requires) {
      sortedRequires.add(Objects.requireNonNull(required, "requires[]"));
    }
    if (sortedRequires.contains(id)) {
      throw new IllegalArgumentException("plugin cannot require itself: " + id);
    }
    sortedRequires.sort(Comparator.comparing(PluginId::value));
    requires = Collections.unmodifiableSet(new LinkedHashSet<>(sortedRequires));
  }
}
