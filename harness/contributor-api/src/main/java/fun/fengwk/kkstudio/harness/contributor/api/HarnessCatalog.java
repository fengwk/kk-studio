package fun.fengwk.kkstudio.harness.contributor.api;

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
 * 冻结的不可变 Contributor 目录：从一个 Contributor 集合构建，先冻结并验证全部 descriptor，再按确定性拓扑顺序收集贡献。
 *
 * <p>唯一性维度：contributor id（全局）；贡献 localName（仅 contributor 内，跨全部贡献类型）；AgentToolId 与 Tool
 * name（均全局，model-visible 调用只携带 Tool name）；custom entry type ownership（结构化键 {@code (contributorId,
 * customType)}，不同 contributor 可各自拥有同名 customType）。允许空 contributor 列表。
 */
public final class HarnessCatalog {

  private final List<ContributorDescriptor> descriptors;
  private final Map<ContributorId, ContributorDescriptor> descriptorsById;
  private final List<ToolContribution> tools;
  private final List<ToolContribution> selectableTools;
  private final Map<String, ToolContribution> toolsByName;
  private final Map<AgentToolId, ToolContribution> toolsByAgentToolId;
  private final Map<ContributionId, ToolContribution> toolsById;
  private final List<CustomEntryTypeContribution> customEntryTypes;
  private final List<ContextProjectorContribution> contextProjectors;
  private final Map<CustomTypeKey, CustomEntryTypeContribution> customEntryTypesByKey;
  private final Map<ContributionId, ContextProjectorContribution> contextProjectorsById;
  private final Map<ContributorId, Set<ContributorId>> transitiveRequirementsById;

  private HarnessCatalog(
      List<ContributorDescriptor> descriptors,
      List<ToolContribution> tools,
      List<CustomEntryTypeContribution> customEntryTypes,
      List<ContextProjectorContribution> contextProjectors,
      Map<ContributorId, Set<ContributorId>> transitiveRequirementsById) {
    this.descriptors = List.copyOf(descriptors);
    Map<ContributorId, ContributorDescriptor> descriptorIndex = new LinkedHashMap<>();
    for (ContributorDescriptor descriptor : descriptors) {
      descriptorIndex.put(descriptor.id(), descriptor);
    }
    this.descriptorsById = Map.copyOf(descriptorIndex);
    this.tools = List.copyOf(tools);
    List<ToolContribution> selectables = new ArrayList<>();
    Map<String, ToolContribution> byName = new LinkedHashMap<>();
    Map<AgentToolId, ToolContribution> byAgentToolId = new LinkedHashMap<>();
    Map<ContributionId, ToolContribution> byId = new LinkedHashMap<>();
    for (ToolContribution tool : tools) {
      String name = tool.definition().descriptor().name();
      if (byName.putIfAbsent(name, tool) != null) {
        throw new IllegalStateException("duplicate frozen tool name: " + name);
      }
      if (byAgentToolId.putIfAbsent(tool.definition().id(), tool) != null) {
        throw new IllegalStateException("duplicate frozen AgentToolId: " + tool.definition().id());
      }
      byId.put(tool.id(), tool);
      if (tool.definition().visibility() == ToolVisibility.SELECTABLE) {
        selectables.add(tool);
      }
    }
    this.selectableTools = List.copyOf(selectables);
    this.toolsByName = Map.copyOf(byName);
    this.toolsByAgentToolId = Map.copyOf(byAgentToolId);
    this.toolsById = Map.copyOf(byId);
    this.customEntryTypes = List.copyOf(customEntryTypes);
    Map<CustomTypeKey, CustomEntryTypeContribution> customEntryTypeIndex = new LinkedHashMap<>();
    for (CustomEntryTypeContribution contribution : customEntryTypes) {
      customEntryTypeIndex.put(
          new CustomTypeKey(contribution.id().contributorId(), contribution.customType()),
          contribution);
    }
    this.customEntryTypesByKey = Map.copyOf(customEntryTypeIndex);
    this.contextProjectors = List.copyOf(contextProjectors);
    Map<ContributionId, ContextProjectorContribution> projectorIndex = new LinkedHashMap<>();
    for (ContextProjectorContribution contribution : contextProjectors) {
      projectorIndex.put(contribution.id(), contribution);
    }
    this.contextProjectorsById = Map.copyOf(projectorIndex);
    Map<ContributorId, Set<ContributorId>> immutableRequirements = new LinkedHashMap<>();
    transitiveRequirementsById.forEach(
        (contributorId, requirements) ->
            immutableRequirements.put(
                contributorId, Collections.unmodifiableSet(new LinkedHashSet<>(requirements))));
    this.transitiveRequirementsById = Map.copyOf(immutableRequirements);
  }

  /** 从 contributor 集合构建冻结 catalog；输入顺序不会影响最终顺序。 */
  public static HarnessCatalog from(Collection<? extends HarnessContributor> contributors) {
    Objects.requireNonNull(contributors, "contributors");
    Collector collector = new Collector();
    for (HarnessContributor contributor : contributors) {
      collector.collect(Objects.requireNonNull(contributor, "contributor"));
    }
    List<ContributorRegistration> orderedContributors = collector.validateAndOrderContributors();
    collector.contributeInOrder(orderedContributors);
    return collector.freeze(orderedContributors);
  }

  /** 按 requires 拓扑和 ContributorId 字典序冻结的 contributor descriptor 列表。 */
  public List<ContributorDescriptor> descriptors() {
    return descriptors;
  }

  /** 按 requires 传递闭包偏序、priority 降序和 ContributionId 字典序冻结的 Tool 列表。 */
  public List<ToolContribution> tools() {
    return tools;
  }

  /** 返回冻结的 SELECTABLE Tool 列表。 */
  public List<ToolContribution> selectableTools() {
    return selectableTools;
  }

  /** 按 contributor id 查找冻结 descriptor。 */
  public Optional<ContributorDescriptor> findDescriptor(ContributorId contributorId) {
    return Optional.ofNullable(
        descriptorsById.get(Objects.requireNonNull(contributorId, "contributorId")));
  }

  /** 按 contributor id 返回已冻结的全部传递 requires；未知 contributor 返回空集合。 */
  public Set<ContributorId> transitiveRequires(ContributorId contributorId) {
    return transitiveRequirementsById.getOrDefault(
        Objects.requireNonNull(contributorId, "contributorId"), Set.of());
  }

  /** 按 model-visible Tool name 查找 Tool 贡献。 */
  public Optional<ToolContribution> findTool(String modelName) {
    Objects.requireNonNull(modelName, "modelName");
    return Optional.ofNullable(toolsByName.get(modelName));
  }

  /** 按 AgentToolId 查找 Tool 贡献。 */
  public Optional<ToolContribution> findTool(AgentToolId id) {
    Objects.requireNonNull(id, "id");
    return Optional.ofNullable(toolsByAgentToolId.get(id));
  }

  /** 按冻结 scoped contribution identity 查找 Tool 贡献。 */
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

  /** 按结构化 ownership 键 {@code (contributorId, customType)} 查找贡献；未注册返回 empty。 */
  public Optional<CustomEntryTypeContribution> findCustomEntryType(
      ContributorId contributorId, String customType) {
    Objects.requireNonNull(contributorId, "contributorId");
    Identifiers.requireCanonical(customType, "customType");
    return Optional.ofNullable(
        customEntryTypesByKey.get(new CustomTypeKey(contributorId, customType)));
  }

  /** 按 ContributionId 查找 context projector 贡献；未注册返回 empty。 */
  public Optional<ContextProjectorContribution> findContextProjector(ContributionId id) {
    return Optional.ofNullable(contextProjectorsById.get(Objects.requireNonNull(id, "id")));
  }

  /** 构建期收集器：先验证静态 descriptor，再实现 {@link HarnessRegistrar} 收集贡献。 */
  private static final class Collector implements HarnessRegistrar {

    private final List<ContributorRegistration> registrations = new ArrayList<>();
    private final List<ToolContribution> tools = new ArrayList<>();
    private final Map<CustomTypeKey, CustomEntryTypeContribution> customEntryTypes =
        new LinkedHashMap<>();
    private final List<ContextProjectorContribution> contextProjectors = new ArrayList<>();
    private final Set<ContributorId> contributorIds = new HashSet<>();
    private final Set<ContributionId> contributionIds = new HashSet<>();
    private final Map<String, ContributionId> toolOwners = new HashMap<>();
    private final Map<AgentToolId, ContributionId> agentToolOwners = new HashMap<>();
    private ContributorId currentContributorId;

    void collect(HarnessContributor contributor) {
      ContributorDescriptor descriptor =
          Objects.requireNonNull(contributor.descriptor(), "contributor.descriptor");
      if (!contributorIds.add(descriptor.id())) {
        throw new IllegalArgumentException("duplicate contributor id: " + descriptor.id());
      }
      registrations.add(new ContributorRegistration(contributor, descriptor));
    }

    List<ContributorRegistration> validateAndOrderContributors() {
      validateRequirements();
      return sortRegistrations(registrations);
    }

    void contributeInOrder(List<ContributorRegistration> orderedContributors) {
      for (ContributorRegistration registration : orderedContributors) {
        currentContributorId = registration.descriptor().id();
        try {
          registration.contributor().contribute(this);
        } finally {
          currentContributorId = null;
        }
      }
    }

    HarnessCatalog freeze(List<ContributorRegistration> orderedContributors) {
      List<ContributorDescriptor> descriptors =
          orderedContributors.stream().map(ContributorRegistration::descriptor).toList();
      for (ToolContribution tool : tools) {
        for (StateDeclaration access : tool.requirements().stateAccesses()) {
          CustomTypeKey key = new CustomTypeKey(tool.id().contributorId(), access.customType());
          if (!customEntryTypes.containsKey(key)) {
            throw new IllegalArgumentException(
                "tool " + tool.id() + " accesses unregistered custom entry type " + key);
          }
        }
      }
      Map<ContributorId, Set<ContributorId>> transitiveRequirements =
          buildTransitiveRequirements(descriptors);
      return new HarnessCatalog(
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
        Tool tool,
        ToolVisibility visibility,
        int priority) {
      Objects.requireNonNull(agentToolId, "agentToolId");
      Objects.requireNonNull(tool, "tool");
      Objects.requireNonNull(visibility, "visibility");
      ContributionId id = requireNewContributionId(localName);
      ToolDescriptor descriptor = Objects.requireNonNull(tool.descriptor(), "tool.descriptor");
      ToolRequirements requirements =
          Objects.requireNonNull(tool.requirements(), "tool.requirements");
      String name = descriptor.name();
      ContributionId previousTool = toolOwners.putIfAbsent(name, id);
      if (previousTool != null) {
        throw new IllegalArgumentException(
            "duplicate tool name " + name + " (already owned by " + previousTool + ")");
      }
      ContributionId previousAgentTool = agentToolOwners.putIfAbsent(agentToolId, id);
      if (previousAgentTool != null) {
        throw new IllegalArgumentException(
            "duplicate AgentToolId "
                + agentToolId
                + " (already owned by "
                + previousAgentTool
                + ")");
      }
      AgentToolDefinition definition = new AgentToolDefinition(agentToolId, descriptor, visibility);
      tools.add(new ToolContribution(id, definition, tool, requirements, priority));
    }

    @Override
    public void registerCustomEntryType(String localName, String customType, int priority) {
      ContributionId id = requireNewContributionId(localName);
      Identifiers.requireCanonical(customType, "customType");
      CustomTypeKey key = new CustomTypeKey(id.contributorId(), customType);
      CustomEntryTypeContribution contribution =
          new CustomEntryTypeContribution(id, customType, priority);
      if (customEntryTypes.putIfAbsent(key, contribution) != null) {
        throw new IllegalArgumentException(
            "duplicate custom entry type " + key + " in contributor " + id.contributorId());
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
      Map<ContributorId, ContributorDescriptor> byId = new HashMap<>();
      for (ContributorRegistration registration : registrations) {
        byId.put(registration.descriptor().id(), registration.descriptor());
      }
      for (ContributorRegistration registration : registrations) {
        ContributorDescriptor descriptor = registration.descriptor();
        for (ContributorId required : descriptor.requires()) {
          if (!byId.containsKey(required)) {
            throw new IllegalArgumentException(
                "contributor " + descriptor.id() + " requires missing contributor " + required);
          }
        }
      }
    }

    private static List<ContributorRegistration> sortRegistrations(
        List<ContributorRegistration> registrations) {
      List<ContributorDescriptor> descriptors =
          registrations.stream().map(ContributorRegistration::descriptor).toList();
      List<ContributorDescriptor> sortedDescriptors = sortDescriptors(descriptors);
      Map<ContributorId, ContributorRegistration> registrationsById = new HashMap<>();
      for (ContributorRegistration registration : registrations) {
        registrationsById.put(registration.descriptor().id(), registration);
      }
      return sortedDescriptors.stream()
          .map(descriptor -> registrationsById.get(descriptor.id()))
          .toList();
    }

    private ContributionId requireNewContributionId(String localName) {
      if (currentContributorId == null) {
        throw new IllegalStateException("contribution is only allowed inside contribute");
      }
      ContributionId id = new ContributionId(currentContributorId, localName);
      if (!contributionIds.add(id)) {
        throw new IllegalArgumentException(
            "duplicate contribution local name "
                + localName
                + " in contributor "
                + currentContributorId);
      }
      return id;
    }
  }

  private record ContributorRegistration(
      HarnessContributor contributor, ContributorDescriptor descriptor) {
    private ContributorRegistration {
      contributor = Objects.requireNonNull(contributor, "contributor");
      descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }
  }

  private static List<ContributorDescriptor> sortDescriptors(
      List<ContributorDescriptor> descriptors) {
    Map<ContributorId, ContributorDescriptor> byId = new LinkedHashMap<>();
    Map<ContributorId, Integer> remainingRequirements = new HashMap<>();
    Map<ContributorId, List<ContributorId>> dependents = new HashMap<>();
    for (ContributorDescriptor descriptor : descriptors) {
      byId.put(descriptor.id(), descriptor);
      remainingRequirements.put(descriptor.id(), descriptor.requires().size());
      for (ContributorId required : descriptor.requires()) {
        dependents.computeIfAbsent(required, ignored -> new ArrayList<>()).add(descriptor.id());
      }
    }
    PriorityQueue<ContributorId> ready =
        new PriorityQueue<>(Comparator.comparing(ContributorId::value));
    for (ContributorId contributorId : byId.keySet()) {
      if (remainingRequirements.get(contributorId) == 0) {
        ready.add(contributorId);
      }
    }
    List<ContributorDescriptor> sorted = new ArrayList<>(descriptors.size());
    while (!ready.isEmpty()) {
      ContributorId contributorId = ready.remove();
      sorted.add(byId.get(contributorId));
      for (ContributorId dependent : dependents.getOrDefault(contributorId, List.of())) {
        int remaining = remainingRequirements.merge(dependent, -1, Integer::sum);
        if (remaining == 0) {
          ready.add(dependent);
        }
      }
    }
    if (sorted.size() != descriptors.size()) {
      throw new IllegalArgumentException("contributor requires graph contains a cycle");
    }
    return sorted;
  }

  private static <T> List<T> sortContributions(
      List<T> contributions,
      Function<T, ContributionId> idFunction,
      Function<T, Integer> priorityFunction,
      Map<ContributorId, Set<ContributorId>> requirements) {
    List<T> remaining = new ArrayList<>(contributions);
    List<T> sorted = new ArrayList<>(contributions.size());
    Comparator<T> readyOrder =
        Comparator.comparingInt(priorityFunction::apply)
            .reversed()
            .thenComparing(idFunction::apply);
    while (!remaining.isEmpty()) {
      List<T> ready = new ArrayList<>();
      for (T candidate : remaining) {
        if (!hasUnemittedRequiredContributor(candidate, remaining, idFunction, requirements)) {
          ready.add(candidate);
        }
      }
      if (ready.isEmpty()) {
        throw new IllegalArgumentException("contributor requires graph contains a cycle");
      }
      ready.sort(readyOrder);
      T next = ready.get(0);
      remaining.remove(next);
      sorted.add(next);
    }
    return sorted;
  }

  private static <T> boolean hasUnemittedRequiredContributor(
      T candidate,
      List<T> remaining,
      Function<T, ContributionId> idFunction,
      Map<ContributorId, Set<ContributorId>> requirements) {
    ContributionId candidateId = idFunction.apply(candidate);
    Set<ContributorId> required = requirements.get(candidateId.contributorId());
    if (required == null || required.isEmpty()) {
      return false;
    }
    for (T other : remaining) {
      if (other != candidate && required.contains(idFunction.apply(other).contributorId())) {
        return true;
      }
    }
    return false;
  }

  private static Map<ContributorId, Set<ContributorId>> buildTransitiveRequirements(
      List<ContributorDescriptor> descriptors) {
    Map<ContributorId, Set<ContributorId>> directRequirements = new HashMap<>();
    for (ContributorDescriptor descriptor : descriptors) {
      directRequirements.put(descriptor.id(), descriptor.requires());
    }
    Map<ContributorId, Set<ContributorId>> transitiveRequirements = new LinkedHashMap<>();
    for (ContributorDescriptor descriptor : descriptors) {
      Set<ContributorId> closure = new LinkedHashSet<>();
      collectRequirements(descriptor.id(), directRequirements, closure);
      transitiveRequirements.put(
          descriptor.id(), Collections.unmodifiableSet(new LinkedHashSet<>(closure)));
    }
    return Map.copyOf(transitiveRequirements);
  }

  private static void collectRequirements(
      ContributorId contributorId,
      Map<ContributorId, Set<ContributorId>> directRequirements,
      Set<ContributorId> closure) {
    for (ContributorId required : directRequirements.getOrDefault(contributorId, Set.of())) {
      if (closure.add(required)) {
        collectRequirements(required, directRequirements, closure);
      }
    }
  }

  /** Custom entry type ownership 的结构化主键 {@code (contributorId, customType)}。 */
  private record CustomTypeKey(ContributorId contributorId, String customType) {

    private CustomTypeKey {
      contributorId = Objects.requireNonNull(contributorId, "contributorId");
      customType = Objects.requireNonNull(customType, "customType");
    }

    @Override
    public String toString() {
      return contributorId + ":" + customType;
    }
  }
}
