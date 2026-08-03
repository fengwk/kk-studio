package fun.fengwk.kkstudio.harness.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** A stable, name-keyed catalog of selectable tools plus internal platform tools. */
public final class ToolCatalog {

  private final List<ToolDescriptor> descriptors;
  private final Map<String, ToolDescriptor> selectableByName;
  private final Map<String, ToolDescriptor> internalByName;

  public ToolCatalog(List<ToolDescriptor> platformDescriptors, Set<String> internalToolNames) {
    Objects.requireNonNull(platformDescriptors, "platformDescriptors");
    Objects.requireNonNull(internalToolNames, "internalToolNames");
    Map<String, ToolDescriptor> platform = new LinkedHashMap<>();
    for (ToolDescriptor descriptor : platformDescriptors) {
      Objects.requireNonNull(descriptor, "platformDescriptors[]");
      if (descriptor.type() != ToolType.PLATFORM) {
        throw new IllegalArgumentException(
            "non-platform descriptor supplied as platform tool: " + descriptor.name());
      }
      if (platform.putIfAbsent(descriptor.name(), descriptor) != null) {
        throw new IllegalArgumentException("duplicate platform tool name: " + descriptor.name());
      }
    }
    Map<String, ToolDescriptor> internal = new LinkedHashMap<>();
    for (String name : internalToolNames) {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("internal tool names must not be blank");
      }
      if (EnvironmentToolCatalog.find(name).isPresent()) {
        throw new IllegalArgumentException("internal and Environment tool names collide: " + name);
      }
      ToolDescriptor descriptor = platform.remove(name);
      if (descriptor == null) {
        throw new IllegalArgumentException("internal tool is not registered: " + name);
      }
      internal.put(name, descriptor);
    }
    Map<String, ToolDescriptor> index = new LinkedHashMap<>(platform);
    for (ToolDescriptor descriptor : EnvironmentToolCatalog.descriptors()) {
      if (descriptor.type() != ToolType.ENVIRONMENT) {
        throw new IllegalArgumentException(
            "Environment catalog contains a non-environment tool: " + descriptor.name());
      }
      if (index.putIfAbsent(descriptor.name(), descriptor) != null) {
        throw new IllegalArgumentException(
            "platform and Environment tool names collide: " + descriptor.name());
      }
    }
    this.selectableByName = Map.copyOf(index);
    this.internalByName = Map.copyOf(internal);
    this.descriptors = List.copyOf(index.values());
  }

  public List<ToolDescriptor> descriptors() {
    return descriptors;
  }

  public Optional<ToolDescriptor> find(String name) {
    return findSelectable(name);
  }

  public ToolDescriptor require(String name) {
    return find(name)
        .orElseThrow(() -> new IllegalArgumentException("unknown selectable tool: " + name));
  }

  public Optional<ToolDescriptor> findSelectable(String name) {
    return Optional.ofNullable(selectableByName.get(name));
  }

  public Optional<ToolDescriptor> findInternal(String name) {
    return Optional.ofNullable(internalByName.get(name));
  }

  public Optional<ToolDescriptor> findAny(String name) {
    return findSelectable(name).or(() -> findInternal(name));
  }
}
