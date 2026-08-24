package fun.fengwk.kkstudio.harness.plugin.api;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Function;

/**
 * 冻结的不可变插件目录：从一个插件集合构建，先收集全部贡献再做唯一性校验（fail-fast）。
 *
 * <p>唯一性维度：plugin id（全局）；贡献 localName（仅插件内，跨三种贡献类型）；Tool name（全局，model-visible 调用只携带名称）；custom
 * entry type ownership（结构化键 {@code (pluginId, customType)}，不同插件可各自拥有同名 customType）。允许空插件列表。catalog
 * 构建完成后不可变，插件后续变更不产生任何影响。
 */
public final class PluginCatalog {

  private final List<PluginDescriptor> descriptors;
  private final Map<PluginId, PluginDescriptor> descriptorsById;
  private final List<ToolContribution> tools;
  private final Map<String, ToolContribution> toolsByName;
  private final Map<ContributionId, ToolContribution> toolsById;
  private final List<CustomEntryTypeContribution> customEntryTypes;
  private final List<ContextProjectorContribution> contextProjectors;
  private final Map<CustomTypeKey, CustomEntryTypeContribution> customEntryTypesByKey;

  private PluginCatalog(
      List<PluginDescriptor> descriptors,
      List<ToolContribution> tools,
      List<CustomEntryTypeContribution> customEntryTypes,
      List<ContextProjectorContribution> contextProjectors) {
    this.descriptors = List.copyOf(descriptors);
    Map<PluginId, PluginDescriptor> descriptorIndex = new LinkedHashMap<>();
    for (PluginDescriptor descriptor : descriptors) {
      descriptorIndex.put(descriptor.id(), descriptor);
    }
    this.descriptorsById = Map.copyOf(descriptorIndex);
    this.tools = List.copyOf(tools);
    Map<String, ToolContribution> byName = new LinkedHashMap<>();
    Map<ContributionId, ToolContribution> byId = new LinkedHashMap<>();
    for (ToolContribution tool : tools) {
      if (byName.putIfAbsent(tool.descriptor().name(), tool) != null) {
        throw new IllegalStateException("duplicate frozen tool name: " + tool.descriptor().name());
      }
      byId.put(tool.id(), tool);
    }
    this.toolsByName = Map.copyOf(byName);
    this.toolsById = Map.copyOf(byId);
    this.customEntryTypes = List.copyOf(customEntryTypes);
    Map<CustomTypeKey, CustomEntryTypeContribution> customEntryTypeIndex = new LinkedHashMap<>();
    for (CustomEntryTypeContribution contribution : customEntryTypes) {
      customEntryTypeIndex.put(
          new CustomTypeKey(contribution.id().pluginId(), contribution.customType()), contribution);
    }
    this.customEntryTypesByKey = Map.copyOf(customEntryTypeIndex);
    this.contextProjectors = List.copyOf(contextProjectors);
  }

  /** 从插件集合构建冻结 catalog；输入顺序不会影响最终顺序。 */
  public static PluginCatalog from(Collection<? extends HarnessPlugin> plugins) {
    Objects.requireNonNull(plugins, "plugins");
    Collector collector = new Collector();
    for (HarnessPlugin plugin : plugins) {
      collector.begin(Objects.requireNonNull(plugin, "plugin"));
    }
    return collector.freeze();
  }

  /** 按 requires 拓扑和 PluginId 字典序冻结的插件 descriptor 列表。 */
  public List<PluginDescriptor> descriptors() {
    return descriptors;
  }

  /** 按 requires 偏序、priority 降序和 ContributionId 字典序冻结的 Tool 列表。 */
  public List<ToolContribution> tools() {
    return tools;
  }

  /** 按 plugin id 查找冻结 descriptor。 */
  public Optional<PluginDescriptor> findDescriptor(PluginId pluginId) {
    return Optional.ofNullable(descriptorsById.get(Objects.requireNonNull(pluginId, "pluginId")));
  }

  /** 按 model-visible Tool name 查找插件贡献。 */
  public Optional<ToolContribution> findTool(String name) {
    Objects.requireNonNull(name, "name");
    return Optional.ofNullable(toolsByName.get(name));
  }

  /** 按冻结 scoped contribution identity 查找插件 Tool。 */
  public Optional<ToolContribution> findTool(ContributionId id) {
    return Optional.ofNullable(toolsById.get(Objects.requireNonNull(id, "id")));
  }

  /** 按 requires 偏序、priority 降序和 ContributionId 字典序冻结的 custom entry 列表。 */
  public List<CustomEntryTypeContribution> customEntryTypes() {
    return customEntryTypes;
  }

  /** 按 requires 偏序、priority 降序和 ContributionId 字典序冻结的 projector 列表。 */
  public List<ContextProjectorContribution> contextProjectors() {
    return contextProjectors;
  }

  /** 按结构化 ownership 键 {@code (pluginId, customType)} 查找贡献；未注册返回 empty。 */
  public Optional<CustomEntryTypeContribution> findCustomEntryType(
      PluginId pluginId, String customType) {
    Objects.requireNonNull(pluginId, "pluginId");
    Identifiers.requireCanonical(customType, "customType");
    return Optional.ofNullable(customEntryTypesByKey.get(new CustomTypeKey(pluginId, customType)));
  }

  /** 构建期收集器：实现 {@link PluginRegistrar}，边收集边校验唯一性。 */
  private static final class Collector implements PluginRegistrar {

    private final List<PluginDescriptor> descriptors = new ArrayList<>();
    private final List<ToolContribution> tools = new ArrayList<>();
    private final Map<CustomTypeKey, CustomEntryTypeContribution> customEntryTypes =
        new LinkedHashMap<>();
    private final List<ContextProjectorContribution> contextProjectors = new ArrayList<>();
    private final Set<PluginId> pluginIds = new HashSet<>();
    private final Set<ContributionId> contributionIds = new HashSet<>();
    private final Map<String, ContributionId> toolOwners = new HashMap<>();
    private PluginId currentPluginId;

    void begin(HarnessPlugin plugin) {
      PluginDescriptor descriptor =
          Objects.requireNonNull(plugin.descriptor(), "plugin.descriptor");
      if (!pluginIds.add(descriptor.id())) {
        throw new IllegalArgumentException("duplicate plugin id: " + descriptor.id());
      }
      descriptors.add(descriptor);
      currentPluginId = descriptor.id();
      try {
        plugin.contribute(this);
      } finally {
        currentPluginId = null;
      }
    }

    PluginCatalog freeze() {
      validateRequirements();
      for (ToolContribution tool : tools) {
        for (PluginStateDeclaration access : tool.stateAccesses()) {
          CustomTypeKey key = new CustomTypeKey(tool.id().pluginId(), access.customType());
          if (!customEntryTypes.containsKey(key)) {
            throw new IllegalArgumentException(
                "plugin tool " + tool.id() + " accesses unregistered custom entry type " + key);
          }
        }
      }
      Map<PluginId, Set<PluginId>> requirements = new HashMap<>();
      for (PluginDescriptor descriptor : descriptors) {
        requirements.put(descriptor.id(), descriptor.requires());
      }
      return new PluginCatalog(
          sortDescriptors(descriptors),
          sortContributions(tools, ToolContribution::id, ToolContribution::priority, requirements),
          sortContributions(
              new ArrayList<>(customEntryTypes.values()),
              CustomEntryTypeContribution::id,
              CustomEntryTypeContribution::priority,
              requirements),
          sortContributions(
              contextProjectors,
              ContextProjectorContribution::id,
              ContextProjectorContribution::priority,
              requirements));
    }

    @Override
    public void registerTool(
        String localName, PluginTool tool, ToolVisibility visibility, int priority) {
      Objects.requireNonNull(tool, "tool");
      Objects.requireNonNull(visibility, "visibility");
      ContributionId id = requireNewContributionId(localName);
      ToolDescriptor descriptor = Objects.requireNonNull(tool.descriptor(), "tool.descriptor");
      if (descriptor.type() != ToolType.PLATFORM) {
        throw new IllegalArgumentException("plugin tool must be PLATFORM: " + descriptor.name());
      }
      List<PluginStateDeclaration> stateAccesses =
          List.copyOf(Objects.requireNonNull(tool.stateAccesses(), "tool.stateAccesses"));
      Set<String> stateAccessTypes = new HashSet<>();
      for (PluginStateDeclaration access : stateAccesses) {
        Objects.requireNonNull(access, "tool.stateAccesses[]");
        if (!stateAccessTypes.add(access.customType())) {
          throw new IllegalArgumentException(
              "duplicate state access " + access.customType() + " on plugin tool " + id);
        }
      }
      String name = descriptor.name();
      if (toolOwners.putIfAbsent(name, id) != null) {
        throw new IllegalArgumentException(
            "duplicate tool name " + name + " (already owned by " + toolOwners.get(name) + ")");
      }
      tools.add(new ToolContribution(id, tool, descriptor, stateAccesses, visibility, priority));
    }

    @Override
    public void registerCustomEntryType(String localName, String customType, int priority) {
      ContributionId id = requireNewContributionId(localName);
      Identifiers.requireCanonical(customType, "customType");
      CustomTypeKey key = new CustomTypeKey(id.pluginId(), customType);
      CustomEntryTypeContribution contribution =
          new CustomEntryTypeContribution(id, customType, priority);
      if (customEntryTypes.putIfAbsent(key, contribution) != null) {
        throw new IllegalArgumentException(
            "duplicate custom entry type " + key + " in plugin " + id.pluginId());
      }
    }

    @Override
    public void registerContextProjector(
        String localName, ContextProjector projector, int priority) {
      Objects.requireNonNull(projector, "projector");
      ContributionId id = requireNewContributionId(localName);
      contextProjectors.add(new ContextProjectorContribution(id, projector, priority));
    }

    private void validateRequirements() {
      Map<PluginId, PluginDescriptor> byId = new HashMap<>();
      for (PluginDescriptor descriptor : descriptors) {
        byId.put(descriptor.id(), descriptor);
      }
      for (PluginDescriptor descriptor : descriptors) {
        for (PluginId required : descriptor.requires()) {
          if (!byId.containsKey(required)) {
            throw new IllegalArgumentException(
                "plugin " + descriptor.id() + " requires missing plugin " + required);
          }
        }
      }
    }

    private ContributionId requireNewContributionId(String localName) {
      if (currentPluginId == null) {
        throw new IllegalStateException("contribution is only allowed inside contribute");
      }
      ContributionId id = new ContributionId(currentPluginId, localName);
      if (!contributionIds.add(id)) {
        throw new IllegalArgumentException(
            "duplicate contribution local name " + localName + " in plugin " + currentPluginId);
      }
      return id;
    }
  }

  private static List<PluginDescriptor> sortDescriptors(List<PluginDescriptor> descriptors) {
    Map<PluginId, PluginDescriptor> byId = new LinkedHashMap<>();
    Map<PluginId, Integer> remainingRequirements = new HashMap<>();
    Map<PluginId, List<PluginId>> dependents = new HashMap<>();
    for (PluginDescriptor descriptor : descriptors) {
      byId.put(descriptor.id(), descriptor);
      remainingRequirements.put(descriptor.id(), descriptor.requires().size());
      for (PluginId required : descriptor.requires()) {
        dependents.computeIfAbsent(required, ignored -> new ArrayList<>()).add(descriptor.id());
      }
    }
    PriorityQueue<PluginId> ready = new PriorityQueue<>(Comparator.comparing(PluginId::value));
    for (PluginId pluginId : byId.keySet()) {
      if (remainingRequirements.get(pluginId) == 0) {
        ready.add(pluginId);
      }
    }
    List<PluginDescriptor> sorted = new ArrayList<>(descriptors.size());
    while (!ready.isEmpty()) {
      PluginId pluginId = ready.remove();
      sorted.add(byId.get(pluginId));
      for (PluginId dependent : dependents.getOrDefault(pluginId, List.of())) {
        int remaining = remainingRequirements.merge(dependent, -1, Integer::sum);
        if (remaining == 0) {
          ready.add(dependent);
        }
      }
    }
    if (sorted.size() != descriptors.size()) {
      throw new IllegalArgumentException("plugin requires graph contains a cycle");
    }
    return sorted;
  }

  private static <T> List<T> sortContributions(
      List<T> contributions,
      Function<T, ContributionId> idFunction,
      Function<T, Integer> priorityFunction,
      Map<PluginId, Set<PluginId>> requirements) {
    List<T> remaining = new ArrayList<>(contributions);
    List<T> sorted = new ArrayList<>(contributions.size());
    Comparator<T> readyOrder =
        Comparator.comparingInt(priorityFunction::apply)
            .reversed()
            .thenComparing(idFunction::apply);
    while (!remaining.isEmpty()) {
      List<T> ready = new ArrayList<>();
      for (T candidate : remaining) {
        if (!hasUnemittedRequiredPlugin(candidate, remaining, idFunction, requirements)) {
          ready.add(candidate);
        }
      }
      if (ready.isEmpty()) {
        throw new IllegalArgumentException("plugin contribution requires graph contains a cycle");
      }
      ready.sort(readyOrder);
      T next = ready.get(0);
      remaining.remove(next);
      sorted.add(next);
    }
    return sorted;
  }

  private static <T> boolean hasUnemittedRequiredPlugin(
      T candidate,
      List<T> remaining,
      Function<T, ContributionId> idFunction,
      Map<PluginId, Set<PluginId>> requirements) {
    ContributionId candidateId = idFunction.apply(candidate);
    Set<PluginId> required = requirements.get(candidateId.pluginId());
    if (required == null || required.isEmpty()) {
      return false;
    }
    for (T other : remaining) {
      if (other != candidate && required.contains(idFunction.apply(other).pluginId())) {
        return true;
      }
    }
    return false;
  }

  /** Custom entry type ownership 的结构化主键 {@code (pluginId, customType)}。 */
  private record CustomTypeKey(PluginId pluginId, String customType) {

    private CustomTypeKey {
      pluginId = Objects.requireNonNull(pluginId, "pluginId");
      customType = Objects.requireNonNull(customType, "customType");
    }

    @Override
    public String toString() {
      return pluginId + ":" + customType;
    }
  }
}
