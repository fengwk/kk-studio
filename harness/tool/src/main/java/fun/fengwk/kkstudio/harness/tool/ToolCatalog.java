package fun.fengwk.kkstudio.harness.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A stable, name-keyed catalog composed by the application from local selectable tools and the
 * fixed Environment tools.
 */
public final class ToolCatalog {

  private final List<ToolDescriptor> descriptors;
  private final Map<String, ToolDescriptor> byName;
  private final Map<String, ToolDescriptor> localByName;
  private final Map<String, ToolDescriptor> runtimeManagedByName;

  public ToolCatalog(List<ToolDescriptor> localDescriptors, Set<String> runtimeManagedToolNames) {
    Objects.requireNonNull(localDescriptors, "localDescriptors");
    Objects.requireNonNull(runtimeManagedToolNames, "runtimeManagedToolNames");
    Map<String, ToolDescriptor> local = new LinkedHashMap<>();
    for (ToolDescriptor descriptor : localDescriptors) {
      Objects.requireNonNull(descriptor, "localDescriptors[]");
      if (local.putIfAbsent(descriptor.name(), descriptor) != null) {
        throw new IllegalArgumentException("duplicate local tool name: " + descriptor.name());
      }
    }
    Map<String, ToolDescriptor> runtimeManaged = new LinkedHashMap<>();
    for (String name : runtimeManagedToolNames) {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("runtime-managed tool names must not be blank");
      }
      if (EnvironmentToolCatalog.find(name).isPresent()) {
        throw new IllegalArgumentException(
            "runtime-managed and Environment tool names collide: " + name);
      }
      ToolDescriptor descriptor = local.remove(name);
      if (descriptor != null) {
        runtimeManaged.put(name, descriptor);
      }
    }
    Map<String, ToolDescriptor> index = new LinkedHashMap<>(local);
    for (ToolDescriptor descriptor : EnvironmentToolCatalog.descriptors()) {
      if (index.putIfAbsent(descriptor.name(), descriptor) != null) {
        throw new IllegalArgumentException(
            "local and Environment tool names collide: " + descriptor.name());
      }
    }
    this.byName = Map.copyOf(index);
    this.localByName = Map.copyOf(local);
    this.runtimeManagedByName = Map.copyOf(runtimeManaged);
    this.descriptors = List.copyOf(index.values());
  }

  public List<ToolDescriptor> descriptors() {
    return descriptors;
  }

  public Optional<ToolDescriptor> find(String name) {
    return Optional.ofNullable(byName.get(name));
  }

  public ToolDescriptor require(String name) {
    return find(name)
        .orElseThrow(() -> new IllegalArgumentException("unknown selectable tool: " + name));
  }

  public Optional<ToolDescriptor> findLocal(String name) {
    return Optional.ofNullable(localByName.get(name));
  }

  public Optional<ToolDescriptor> findEnvironment(String name) {
    return EnvironmentToolCatalog.find(name);
  }

  public Optional<ToolDescriptor> findRuntimeManaged(String name) {
    return Optional.ofNullable(runtimeManagedByName.get(name));
  }
}
