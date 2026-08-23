package fun.fengwk.kkstudio.harness.plugin.api;

import java.util.Objects;

/** 插件的静态元数据：canonical id、展示名与版本。 */
public record PluginDescriptor(PluginId id, String name, String version) {

  public PluginDescriptor {
    id = Objects.requireNonNull(id, "id");
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (version == null || version.isBlank()) {
      throw new IllegalArgumentException("version must not be blank");
    }
  }
}
