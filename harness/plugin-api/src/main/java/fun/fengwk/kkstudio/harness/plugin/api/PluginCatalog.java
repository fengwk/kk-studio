package fun.fengwk.kkstudio.harness.plugin.api;

import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Function;

/**
 * 冻结的不可变插件目录：从一个插件集合构建，先冻结并验证全部 descriptor，再按确定性拓扑顺序收集贡献。
 *
 * <p>唯一性维度：plugin id（全局）；贡献 localName（仅插件内，跨三种贡献类型）；AgentToolId 与 Tool name（均全局，model-visible 调用只携带
 * Tool name）；custom entry type ownership（结构化键 {@code (pluginId, customType)}，不同插件可各自拥有同名
 * customType）。允许空插件列表。 catalog 构建完成后不可变，插件后续变更不产生任何影响。
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
  private final Map<PluginId, Set<PluginId>> transitiveRequirementsById;

  private PluginCatalog(
      List<PluginDescriptor> descriptors,
      List<ToolContribution> tools,
      List<CustomEntryTypeContribution> customEntryTypes,
      List<ContextProjectorContribution> contextProjectors,
      Map<PluginId, Set<PluginId>> transitiveRequirementsById) {
    this.descriptors = List.copyOf(descriptors);
    Map<PluginId, PluginDescriptor> descriptorIndex = new LinkedHashMap<>();
    for (PluginDescriptor descriptor : descriptors) {
      descriptorIndex.put(descriptor.id(), descriptor);
    }
    this.descriptorsById = Map.copyOf(descriptorIndex);
    this.tools = List.copyOf(tools);
    Map<String, ToolContribution> byName = new LinkedHashMap<>();
    Map<ContributionId, ToolContribution> byId = new LinkedHashMap<>();
    Set<AgentToolId> agentToolIds = new HashSet<>();
    for (ToolContribution tool : tools) {
      String name = tool.definition().descriptor().name();
      if (byName.putIfAbsent(name, tool) != null) {
        throw new IllegalStateException("duplicate frozen tool name: " + name);
      }
      if (!agentToolIds.add(tool.definition().id())) {
        throw new IllegalStateException("duplicate frozen AgentToolId: " + tool.definition().id());
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
    Map<PluginId, Set<PluginId>> immutableRequirements = new LinkedHashMap<>();
    transitiveRequirementsById.forEach(
        (pluginId, requirements) ->
            immutableRequirements.put(
                pluginId, Collections.unmodifiableSet(new LinkedHashSet<>(requirements))));
    this.transitiveRequirementsById = Map.copyOf(immutableRequirements);
  }

  /** 从插件集合构建冻结 catalog；输入顺序不会影响最终顺序。 */
  public static PluginCatalog from(Collection<? extends HarnessPlugin> plugins) {
    Objects.requireNonNull(plugins, "plugins");
    Collector collector = new Collector();
    for (HarnessPlugin plugin : plugins) {
      collector.collect(Objects.requireNonNull(plugin, "plugin"));
    }
    List<PluginRegistration> orderedPlugins = collector.validateAndOrderPlugins();
    collector.contributeInOrder(orderedPlugins);
    return collector.freeze(orderedPlugins);
  }

  /** 按 requires 拓扑和 PluginId 字典序冻结的插件 descriptor 列表。 */
  public List<PluginDescriptor> descriptors() {
    return descriptors;
  }

  /** 按 requires 传递闭包偏序、priority 降序和 ContributionId 字典序冻结的 Tool 列表。 */
  public List<ToolContribution> tools() {
    return tools;
  }

  /** 按 plugin id 查找冻结 descriptor。 */
  public Optional<PluginDescriptor> findDescriptor(PluginId pluginId) {
    return Optional.ofNullable(descriptorsById.get(Objects.requireNonNull(pluginId, "pluginId")));
  }

  /** 按 plugin id 返回已冻结的全部传递 requires；未知 plugin 返回空集合。 */
  public Set<PluginId> transitiveRequires(PluginId pluginId) {
    return transitiveRequirementsById.getOrDefault(
        Objects.requireNonNull(pluginId, "pluginId"), Set.of());
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

  /** 按 requires 传递闭包偏序、priority 降序和 ContributionId 字典序冻结的 custom entry 列表。 */
  public List<CustomEntryTypeContribution> customEntryTypes() {
    return customEntryTypes;
  }

  /** 按 requires 传递闭包偏序、priority 降序和 ContributionId 字典序冻结的 projector 列表。 */
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

  /** 构建期收集器：先验证静态 descriptor，再实现 {@link PluginRegistrar} 收集贡献。 */
  private static final class Collector implements PluginRegistrar {

    private final List<PluginRegistration> registrations = new ArrayList<>();
    private final List<ToolContribution> tools = new ArrayList<>();
    private final Map<CustomTypeKey, CustomEntryTypeContribution> customEntryTypes =
        new LinkedHashMap<>();
    private final List<ContextProjectorContribution> contextProjectors = new ArrayList<>();
    private final Set<PluginId> pluginIds = new HashSet<>();
    private final Set<ContributionId> contributionIds = new HashSet<>();
    private final Map<String, ContributionId> toolOwners = new HashMap<>();
    private final Map<AgentToolId, ContributionId> agentToolOwners = new HashMap<>();
    private PluginId currentPluginId;

    void collect(HarnessPlugin plugin) {
      PluginDescriptor descriptor =
          Objects.requireNonNull(plugin.descriptor(), "plugin.descriptor");
      if (!pluginIds.add(descriptor.id())) {
        throw new IllegalArgumentException("duplicate plugin id: " + descriptor.id());
      }
      registrations.add(new PluginRegistration(plugin, descriptor));
    }

    List<PluginRegistration> validateAndOrderPlugins() {
      validateRequirements();
      return sortRegistrations(registrations);
    }

    void contributeInOrder(List<PluginRegistration> orderedPlugins) {
      for (PluginRegistration registration : orderedPlugins) {
        currentPluginId = registration.descriptor().id();
        try {
          registration.plugin().contribute(this);
        } finally {
          currentPluginId = null;
        }
      }
    }

    PluginCatalog freeze(List<PluginRegistration> orderedPlugins) {
      List<PluginDescriptor> descriptors =
          orderedPlugins.stream().map(PluginRegistration::descriptor).toList();
      for (ToolContribution tool : tools) {
        for (PluginStateDeclaration access : tool.stateAccesses()) {
          CustomTypeKey key = new CustomTypeKey(tool.id().pluginId(), access.customType());
          if (!customEntryTypes.containsKey(key)) {
            throw new IllegalArgumentException(
                "plugin tool " + tool.id() + " accesses unregistered custom entry type " + key);
          }
        }
      }
      Map<PluginId, Set<PluginId>> transitiveRequirements =
          buildTransitiveRequirements(descriptors);
      return new PluginCatalog(
          descriptors,
          sortContributions(
              tools, ToolContribution::id, ToolContribution::priority, transitiveRequirements),
          sortContributions(
              new ArrayList<>(customEntryTypes.values()),
              CustomEntryTypeContribution::id,
              CustomEntryTypeContribution::priority,
              transitiveRequirements),
          sortContributions(
              contextProjectors,
              ContextProjectorContribution::id,
              ContextProjectorContribution::priority,
              transitiveRequirements),
          transitiveRequirements);
    }

    @Override
    public void registerTool(
        String localName,
        AgentToolId agentToolId,
        PluginTool tool,
        ToolVisibility visibility,
        int priority) {
      Objects.requireNonNull(agentToolId, "agentToolId");
      Objects.requireNonNull(tool, "tool");
      Objects.requireNonNull(visibility, "visibility");
      ContributionId id = requireNewContributionId(localName);
      ToolDescriptor descriptor = Objects.requireNonNull(tool.descriptor(), "tool.descriptor");
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
      ContributionId previous = agentToolOwners.putIfAbsent(agentToolId, id);
      if (previous != null) {
        throw new IllegalArgumentException(
            "duplicate AgentToolId " + agentToolId + " (already owned by " + previous + ")");
      }
      AgentToolDefinition definition =
          new AgentToolDefinition(agentToolId, descriptor, visibility, AgentToolBackend.PLUGIN);
      tools.add(new ToolContribution(id, tool, definition, stateAccesses, priority));
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
      for (PluginRegistration registration : registrations) {
        byId.put(registration.descriptor().id(), registration.descriptor());
      }
      for (PluginRegistration registration : registrations) {
        PluginDescriptor descriptor = registration.descriptor();
        for (PluginId required : descriptor.requires()) {
          if (!byId.containsKey(required)) {
            throw new IllegalArgumentException(
                "plugin " + descriptor.id() + " requires missing plugin " + required);
          }
        }
      }
    }

    private static List<PluginRegistration> sortRegistrations(
        List<PluginRegistration> registrations) {
      List<PluginDescriptor> descriptors =
          registrations.stream().map(PluginRegistration::descriptor).toList();
      List<PluginDescriptor> sortedDescriptors = sortDescriptors(descriptors);
      Map<PluginId, PluginRegistration> registrationsById = new HashMap<>();
      for (PluginRegistration registration : registrations) {
        registrationsById.put(registration.descriptor().id(), registration);
      }
      return sortedDescriptors.stream()
          .map(descriptor -> registrationsById.get(descriptor.id()))
          .toList();
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

  private record PluginRegistration(HarnessPlugin plugin, PluginDescriptor descriptor) {
    private PluginRegistration {
      plugin = Objects.requireNonNull(plugin, "plugin");
      descriptor = Objects.requireNonNull(descriptor, "descriptor");
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

  private static Map<PluginId, Set<PluginId>> buildTransitiveRequirements(
      List<PluginDescriptor> descriptors) {
    Map<PluginId, Set<PluginId>> directRequirements = new HashMap<>();
    for (PluginDescriptor descriptor : descriptors) {
      directRequirements.put(descriptor.id(), descriptor.requires());
    }
    Map<PluginId, Set<PluginId>> transitiveRequirements = new LinkedHashMap<>();
    for (PluginDescriptor descriptor : descriptors) {
      Set<PluginId> closure = new LinkedHashSet<>();
      collectRequirements(descriptor.id(), directRequirements, closure);
      transitiveRequirements.put(
          descriptor.id(), Collections.unmodifiableSet(new LinkedHashSet<>(closure)));
    }
    return Map.copyOf(transitiveRequirements);
  }

  private static void collectRequirements(
      PluginId pluginId, Map<PluginId, Set<PluginId>> directRequirements, Set<PluginId> closure) {
    for (PluginId required : directRequirements.getOrDefault(pluginId, Set.of())) {
      if (closure.add(required)) {
        collectRequirements(required, directRequirements, closure);
      }
    }
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
