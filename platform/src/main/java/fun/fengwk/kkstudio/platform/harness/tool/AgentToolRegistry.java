package fun.fengwk.kkstudio.platform.harness.tool;

import fun.fengwk.kkstudio.harness.plugin.api.ContributionId;
import fun.fengwk.kkstudio.harness.plugin.api.PluginCatalog;
import fun.fengwk.kkstudio.harness.plugin.api.PluginDescriptor;
import fun.fengwk.kkstudio.harness.plugin.api.PluginId;
import fun.fengwk.kkstudio.harness.plugin.api.ToolContribution;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactory;
import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Platform-side frozen registry for every model-visible Agent Tool.
 *
 * <p>Host factories and plugin contributions keep their dependency, priority and identity order.
 * Fixed Environment capabilities are appended in the order supplied by {@link
 * EnvironmentToolCatalog#entries()}. AgentToolId and model-visible descriptor names are unique
 * across all three backends.
 */
public final class AgentToolRegistry {

  private final List<Entry> entries;
  private final List<Entry> selectableEntries;
  private final Map<AgentToolId, Entry> entriesById;

  public AgentToolRegistry(
      Collection<? extends ToolFactory> factories,
      PluginCatalog pluginCatalog,
      Collection<? extends EnvironmentToolCatalog.Entry> environmentEntries) {
    Objects.requireNonNull(factories, "factories");
    Objects.requireNonNull(pluginCatalog, "pluginCatalog");
    Objects.requireNonNull(environmentEntries, "environmentEntries");

    List<Entry> hostAndPlugin = new ArrayList<>();
    Map<AgentToolId, Entry> byId = new LinkedHashMap<>();
    Map<String, Entry> byName = new LinkedHashMap<>();
    for (ToolFactory factory : factories) {
      Objects.requireNonNull(factory, "factory");
      AgentToolDefinition definition =
          Objects.requireNonNull(factory.definition(), "factory.definition");
      if (definition.backend() != AgentToolBackend.HOST) {
        throw new IllegalArgumentException(
            "host ToolFactory must declare a HOST definition: " + definition.id());
      }
      Entry entry = Entry.host(definition, factory.priority(), factory);
      putUniqueId(byId, entry);
      putUniqueName(byName, entry);
      hostAndPlugin.add(entry);
    }
    for (ToolContribution contribution : pluginCatalog.tools()) {
      Entry entry = Entry.plugin(contribution);
      putUniqueId(byId, entry);
      putUniqueName(byName, entry);
      hostAndPlugin.add(entry);
    }

    List<Entry> merged = sort(hostAndPlugin, pluginCatalog);
    for (EnvironmentToolCatalog.Entry environmentEntry : environmentEntries) {
      Objects.requireNonNull(environmentEntry, "environmentEntries[]");
      Entry entry = Entry.environment(environmentEntry);
      putUniqueId(byId, entry);
      putUniqueName(byName, entry);
      merged.add(entry);
    }

    this.entries = List.copyOf(merged);
    this.selectableEntries =
        this.entries.stream()
            .filter(entry -> entry.definition().visibility() == ToolVisibility.SELECTABLE)
            .toList();
    this.entriesById = Map.copyOf(byId);
  }

  /** 返回按冻结顺序排列的全部 Agent Tool 条目。 */
  public List<Entry> entries() {
    return entries;
  }

  /** 返回按冻结顺序排列且可由 Agent 选择的条目。 */
  public List<Entry> selectableEntries() {
    return selectableEntries;
  }

  /** 按稳定 AgentToolId 查找冻结条目。 */
  public Optional<Entry> find(AgentToolId id) {
    return Optional.ofNullable(entriesById.get(Objects.requireNonNull(id, "id")));
  }

  /** 以冻结 Host factory 创建 Tool，并校验 descriptor 未漂移。 */
  public Tool createHostTool(Entry entry) {
    Objects.requireNonNull(entry, "entry");
    if (entry.definition().backend() != AgentToolBackend.HOST || entry.hostFactory() == null) {
      throw new IllegalArgumentException("entry is not a host ToolFactory contribution");
    }
    Tool tool = Objects.requireNonNull(entry.hostFactory().create(), "ToolFactory.create");
    ToolDescriptor actual = Objects.requireNonNull(tool.descriptor(), "created tool descriptor");
    if (!entry.definition().descriptor().equals(actual)) {
      throw new IllegalArgumentException(
          "created host tool descriptor "
              + actual.name()
              + "@"
              + actual.version()
              + " does not match frozen "
              + entry.definition().descriptor().name()
              + "@"
              + entry.definition().descriptor().version());
    }
    return tool;
  }

  private static void putUniqueId(Map<AgentToolId, Entry> byId, Entry entry) {
    Entry previous = byId.putIfAbsent(entry.id(), entry);
    if (previous != null) {
      throw new IllegalArgumentException(
          "duplicate AgentToolId "
              + entry.id()
              + " ("
              + previous.identity()
              + " and "
              + entry.identity()
              + ")");
    }
  }

  private static void putUniqueName(Map<String, Entry> byName, Entry entry) {
    String name = entry.definition().descriptor().name();
    Entry previous = byName.putIfAbsent(name, entry);
    if (previous != null) {
      throw new IllegalArgumentException(
          "duplicate Agent tool name "
              + name
              + " ("
              + previous.identity()
              + " and "
              + entry.identity()
              + ")");
    }
  }

  private static List<Entry> sort(List<Entry> candidates, PluginCatalog pluginCatalog) {
    List<Entry> remaining = new ArrayList<>(candidates);
    List<Entry> sorted = new ArrayList<>(candidates.size());
    Comparator<Entry> readyOrder =
        Comparator.comparingInt(Entry::priority).reversed().thenComparing(Entry::identity);
    Map<PluginId, Set<PluginId>> requirements = new LinkedHashMap<>();
    for (PluginDescriptor descriptor : pluginCatalog.descriptors()) {
      requirements.put(descriptor.id(), pluginCatalog.transitiveRequires(descriptor.id()));
    }
    while (!remaining.isEmpty()) {
      List<Entry> ready = new ArrayList<>();
      for (Entry candidate : remaining) {
        if (!hasUnemittedRequiredPlugin(candidate, remaining, requirements)) {
          ready.add(candidate);
        }
      }
      if (ready.isEmpty()) {
        throw new IllegalArgumentException("tool contribution requires graph contains a cycle");
      }
      ready.sort(readyOrder);
      Entry next = ready.getFirst();
      remaining.remove(next);
      sorted.add(next);
    }
    return sorted;
  }

  private static boolean hasUnemittedRequiredPlugin(
      Entry candidate, List<Entry> remaining, Map<PluginId, Set<PluginId>> requirements) {
    PluginId pluginId = candidate.pluginId();
    if (pluginId == null) {
      return false;
    }
    Set<PluginId> required = requirements.getOrDefault(pluginId, Set.of());
    for (Entry other : remaining) {
      if (other != candidate && required.contains(other.pluginId())) {
        return true;
      }
    }
    return false;
  }

  /** 一个 Agent Tool 的不可变冻结视图；backend 与三个 payload 严格一一对应。 */
  public record Entry(
      AgentToolDefinition definition,
      int priority,
      String identity,
      ToolFactory hostFactory,
      ToolContribution pluginContribution,
      EnvironmentCapabilityDescriptor capability) {

    public Entry {
      definition = Objects.requireNonNull(definition, "definition");
      if (identity == null || identity.isBlank()) {
        throw new IllegalArgumentException("identity must not be blank");
      }
      int payloads =
          (hostFactory == null ? 0 : 1)
              + (pluginContribution == null ? 0 : 1)
              + (capability == null ? 0 : 1);
      if (payloads != 1) {
        throw new IllegalArgumentException(
            "entry must have exactly one host factory, plugin contribution, or capability");
      }
      switch (definition.backend()) {
        case HOST -> {
          if (hostFactory == null || pluginContribution != null || capability != null) {
            throw new IllegalArgumentException("HOST entry payload does not match definition");
          }
          if (!definition.equals(
              Objects.requireNonNull(hostFactory.definition(), "hostFactory.definition"))) {
            throw new IllegalArgumentException("host factory definition does not match entry");
          }
        }
        case PLUGIN -> {
          if (hostFactory != null || pluginContribution == null || capability != null) {
            throw new IllegalArgumentException("PLUGIN entry payload does not match definition");
          }
          if (!definition.equals(pluginContribution.definition())) {
            throw new IllegalArgumentException(
                "plugin contribution definition does not match entry");
          }
        }
        case ENVIRONMENT_CAPABILITY -> {
          if (hostFactory != null
              || pluginContribution != null
              || capability == null
              || definition.visibility() != ToolVisibility.SELECTABLE) {
            throw new IllegalArgumentException(
                "ENVIRONMENT_CAPABILITY entry payload does not match definition");
          }
          if (!definition.descriptor().inputSchema().equals(capability.inputSchema())
              || !definition.descriptor().timeout().equals(capability.timeout())) {
            throw new IllegalArgumentException(
                "ENVIRONMENT_CAPABILITY descriptor does not match definition");
          }
        }
      }
    }

    private static Entry host(
        AgentToolDefinition definition, int priority, ToolFactory hostFactory) {
      return new Entry(
          definition,
          priority,
          "core:" + definition.descriptor().name() + "@" + definition.descriptor().version(),
          hostFactory,
          null,
          null);
    }

    private static Entry plugin(ToolContribution pluginContribution) {
      return new Entry(
          pluginContribution.definition(),
          pluginContribution.priority(),
          "plugin:" + pluginContribution.id(),
          null,
          pluginContribution,
          null);
    }

    private static Entry environment(EnvironmentToolCatalog.Entry environmentEntry) {
      return new Entry(
          environmentEntry.definition(),
          0,
          "environment:" + environmentEntry.definition().id(),
          null,
          null,
          environmentEntry.capability());
    }

    public AgentToolId id() {
      return definition.id();
    }

    public String stableIdentity() {
      return identity;
    }

    public ContributionId contributionId() {
      return pluginContribution == null ? null : pluginContribution.id();
    }

    public PluginId pluginId() {
      return contributionId() == null ? null : contributionId().pluginId();
    }
  }
}
