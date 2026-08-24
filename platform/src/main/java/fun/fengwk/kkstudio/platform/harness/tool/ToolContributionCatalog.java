package fun.fengwk.kkstudio.platform.harness.tool;

import fun.fengwk.kkstudio.harness.plugin.api.ContributionId;
import fun.fengwk.kkstudio.harness.plugin.api.PluginCatalog;
import fun.fengwk.kkstudio.harness.plugin.api.PluginDescriptor;
import fun.fengwk.kkstudio.harness.plugin.api.PluginId;
import fun.fengwk.kkstudio.harness.plugin.api.ToolContribution;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactory;
import fun.fengwk.kkstudio.harness.tool.ToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Platform 唯一的 Tool contribution catalog：把普通 {@link ToolFactory} 与冻结插件 Tool 合并为一份不可变目录。
 *
 * <p>普通 Tool 是无依赖的 host entry，内部排序身份固定为 {@code core:name@version}；插件 Tool 保留 {@link ContributionId}
 * provenance。目录顺序同时满足插件 requires 偏序、priority 降序和稳定 identity 字典序，输入 bean 顺序不会改变结果。
 */
public final class ToolContributionCatalog {

  private final PluginCatalog pluginCatalog;
  private final List<Entry> entries;
  private final Map<Key, Entry> entriesByKey;
  private final Map<String, Entry> entriesByName;

  public ToolContributionCatalog(
      Collection<? extends ToolFactory> factories, PluginCatalog pluginCatalog) {
    Objects.requireNonNull(factories, "factories");
    this.pluginCatalog = Objects.requireNonNull(pluginCatalog, "pluginCatalog");

    List<Entry> collected = new ArrayList<>();
    Map<String, Entry> byName = new LinkedHashMap<>();
    for (ToolFactory factory : factories) {
      Objects.requireNonNull(factory, "factory");
      ToolDescriptor descriptor =
          Objects.requireNonNull(factory.descriptor(), "factory.descriptor");
      if (descriptor.type() != ToolType.PLATFORM) {
        throw new IllegalArgumentException(
            "local ToolFactory must declare a PLATFORM descriptor: " + descriptor.name());
      }
      Entry entry =
          Entry.local(
              descriptor,
              Objects.requireNonNull(factory.visibility(), "factory.visibility"),
              factory.priority(),
              factory);
      putUnique(byName, entry);
      collected.add(entry);
    }
    for (ToolContribution contribution : pluginCatalog.tools()) {
      Entry entry = Entry.plugin(contribution);
      putUnique(byName, entry);
      collected.add(entry);
    }

    List<Entry> sorted = sort(collected);
    Map<Key, Entry> byKey = new LinkedHashMap<>();
    for (Entry entry : sorted) {
      byKey.put(new Key(entry.descriptor()), entry);
    }
    this.entries = List.copyOf(sorted);
    this.entriesByKey = Map.copyOf(byKey);
    this.entriesByName = Map.copyOf(byName);
  }

  public static ToolContributionCatalog from(
      Collection<? extends ToolFactory> factories, PluginCatalog pluginCatalog) {
    return new ToolContributionCatalog(factories, pluginCatalog);
  }

  /** 所有 Platform Tool contribution，按冻结顺序返回。 */
  public List<Entry> entries() {
    return entries;
  }

  /** 按唯一工具名查找 contribution；版本恢复使用 {@link #find(String, String)}。 */
  public Optional<Entry> find(String name) {
    Objects.requireNonNull(name, "name");
    return Optional.ofNullable(entriesByName.get(name));
  }

  /** 按 durable binding 使用的 {@code (name, version)} 精确恢复 contribution。 */
  public Optional<Entry> find(String name, String version) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(version, "version");
    return Optional.ofNullable(entriesByKey.get(new Key(name, version)));
  }

  /** 按插件 contribution provenance 恢复唯一条目。 */
  public Optional<Entry> find(ContributionId id) {
    Objects.requireNonNull(id, "id");
    return entries.stream()
        .filter(entry -> entry.isPlugin() && id.equals(entry.contributionId()))
        .findFirst();
  }

  /** 以冻结普通 ToolFactory 创建 Tool，并校验 descriptor 未漂移。 */
  public Tool createLocalTool(Entry entry) {
    Objects.requireNonNull(entry, "entry");
    if (!entry.isLocal()) {
      throw new IllegalArgumentException("entry is not a local ToolFactory contribution");
    }
    Tool tool = Objects.requireNonNull(entry.localFactory().create(), "ToolFactory.create");
    ToolDescriptor actual = Objects.requireNonNull(tool.descriptor(), "created tool descriptor");
    if (!entry.descriptor().equals(actual)) {
      throw new IllegalArgumentException(
          "created local tool descriptor "
              + actual.name()
              + "@"
              + actual.version()
              + " does not match frozen "
              + entry.descriptor().name()
              + "@"
              + entry.descriptor().version());
    }
    return tool;
  }

  /** 从同一份 Platform contribution catalog 派生可选/内部目录，并追加固定 Environment 目录。 */
  public ToolCatalog toToolCatalog() {
    List<ToolDescriptor> descriptors = entries.stream().map(Entry::descriptor).toList();
    Set<String> internalNames =
        entries.stream()
            .filter(entry -> entry.visibility() == ToolVisibility.INTERNAL)
            .map(entry -> entry.descriptor().name())
            .collect(Collectors.toUnmodifiableSet());
    return new ToolCatalog(descriptors, internalNames);
  }

  public PluginCatalog pluginCatalog() {
    return pluginCatalog;
  }

  private static void putUnique(Map<String, Entry> byName, Entry entry) {
    Entry previous = byName.putIfAbsent(entry.descriptor().name(), entry);
    if (previous != null) {
      throw new IllegalArgumentException(
          "duplicate Platform tool name "
              + entry.descriptor().name()
              + " ("
              + previous.identity()
              + " and "
              + entry.identity()
              + ")");
    }
  }

  private List<Entry> sort(List<Entry> candidates) {
    List<Entry> remaining = new ArrayList<>(candidates);
    List<Entry> sorted = new ArrayList<>(candidates.size());
    Comparator<Entry> readyOrder =
        Comparator.comparingInt(Entry::priority).reversed().thenComparing(Entry::identity);
    Map<PluginId, Set<PluginId>> requirements = new HashMap<>();
    for (PluginDescriptor descriptor : pluginCatalog.descriptors()) {
      requirements.put(descriptor.id(), descriptor.requires());
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
      Entry next = ready.get(0);
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

  /** 单一 Tool contribution 的不可变冻结视图。 */
  public record Entry(
      ToolDescriptor descriptor,
      ToolVisibility visibility,
      int priority,
      String identity,
      Origin origin,
      ToolFactory localFactory,
      ToolContribution pluginContribution) {

    public Entry {
      descriptor = Objects.requireNonNull(descriptor, "descriptor");
      visibility = Objects.requireNonNull(visibility, "visibility");
      if (identity == null || identity.isBlank()) {
        throw new IllegalArgumentException("identity must not be blank");
      }
      origin = Objects.requireNonNull(origin, "origin");
      if ((origin == Origin.LOCAL) == (localFactory == null)) {
        throw new IllegalArgumentException("local entry must have exactly one local factory");
      }
      if ((origin == Origin.PLUGIN) == (pluginContribution == null)) {
        throw new IllegalArgumentException(
            "plugin entry must have exactly one plugin contribution");
      }
    }

    static Entry local(
        ToolDescriptor descriptor, ToolVisibility visibility, int priority, ToolFactory factory) {
      return new Entry(
          descriptor,
          visibility,
          priority,
          "core:" + descriptor.name() + "@" + descriptor.version(),
          Origin.LOCAL,
          factory,
          null);
    }

    static Entry plugin(ToolContribution contribution) {
      return new Entry(
          contribution.descriptor(),
          contribution.visibility(),
          contribution.priority(),
          "plugin:" + contribution.id(),
          Origin.PLUGIN,
          null,
          contribution);
    }

    public boolean isLocal() {
      return origin == Origin.LOCAL;
    }

    public boolean isPlugin() {
      return origin == Origin.PLUGIN;
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

  public enum Origin {
    LOCAL,
    PLUGIN
  }

  private record Key(String name, String version) {

    private Key {
      name = Objects.requireNonNull(name, "name");
      version = Objects.requireNonNull(version, "version");
    }

    private Key(ToolDescriptor descriptor) {
      this(descriptor.name(), descriptor.version());
    }
  }
}
