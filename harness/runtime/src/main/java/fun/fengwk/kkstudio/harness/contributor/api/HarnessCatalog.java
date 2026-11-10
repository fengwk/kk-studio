package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
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
 * <p>唯一性维度：contributor id（全局）；贡献 localName（仅 contributor 内，跨全部贡献类型）；模型可见 Tool name （全局唯一）。 允许空
 * contributor 列表。
 */
public final class HarnessCatalog {

  private final List<ContributorDescriptor> descriptors;
  private final List<ToolContribution> tools;
  private final List<ToolContribution> selectableTools;
  private final Map<String, ToolContribution> toolsByName;
  private final Map<ContributionId, ToolContribution> toolsById;

  private HarnessCatalog(List<ContributorDescriptor> descriptors, List<ToolContribution> tools) {
    this.descriptors = List.copyOf(descriptors);
    this.tools = List.copyOf(tools);
    List<ToolContribution> selectables = new ArrayList<>();
    Map<String, ToolContribution> byName = new LinkedHashMap<>();
    Map<ContributionId, ToolContribution> byId = new LinkedHashMap<>();
    for (ToolContribution tool : tools) {
      String name = tool.definition().descriptor().name();
      if (byName.putIfAbsent(name, tool) != null) {
        throw new IllegalStateException("duplicate frozen tool name: " + name);
      }
      byId.put(tool.id(), tool);
      if (tool.definition().visibility() == ToolVisibility.SELECTABLE) {
        selectables.add(tool);
      }
    }
    this.selectableTools = List.copyOf(selectables);
    this.toolsByName = Map.copyOf(byName);
    this.toolsById = Map.copyOf(byId);
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

  /** 按 model-visible Tool name 查找 Tool 贡献。 */
  public Optional<ToolContribution> findTool(String modelName) {
    Objects.requireNonNull(modelName, "modelName");
    return Optional.ofNullable(toolsByName.get(modelName));
  }

  /** 按冻结 scoped contribution identity 查找 Tool 贡献。 */
  public Optional<ToolContribution> findTool(ContributionId id) {
    return Optional.ofNullable(toolsById.get(Objects.requireNonNull(id, "id")));
  }

  /** 构建期收集器：先验证静态 descriptor，再实现 {@link HarnessRegistrar} 收集贡献。 */
  private static final class Collector implements HarnessRegistrar {

    private final List<ContributorRegistration> registrations = new ArrayList<>();
    private final List<ToolContribution> tools = new ArrayList<>();
    private final Set<ContributorId> contributorIds = new HashSet<>();
    private final Set<ContributionId> contributionIds = new HashSet<>();
    private final Map<String, ContributionId> toolOwners = new HashMap<>();
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

    /** 冻结不可变 Catalog：计算 requires 传递依赖偏序闭包，并将工具按 requires 偏序、priority 降序与 ContributionId 字典序排序冻结。 */
    HarnessCatalog freeze(List<ContributorRegistration> orderedContributors) {
      List<ContributorDescriptor> descriptors =
          orderedContributors.stream().map(ContributorRegistration::descriptor).toList();
      Map<ContributorId, Set<ContributorId>> transitiveRequirements =
          buildTransitiveRequirements(descriptors);
      return new HarnessCatalog(
          descriptors,
          sortContributions(
              tools, ToolContribution::id, ToolContribution::priority, transitiveRequirements));
    }

    @Override
    public void registerTool(String localName, Tool tool, ToolVisibility visibility, int priority) {
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
      AgentToolDefinition definition = new AgentToolDefinition(descriptor, visibility);
      tools.add(new ToolContribution(id, definition, tool, requirements, priority));
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
}
