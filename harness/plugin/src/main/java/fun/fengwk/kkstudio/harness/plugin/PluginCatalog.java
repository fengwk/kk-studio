package fun.fengwk.kkstudio.harness.plugin;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 冻结的不可变插件目录：从一个插件集合构建，先收集全部贡献再做唯一性校验（fail-fast）。
 *
 * <p>唯一性维度：plugin id（全局）；贡献 localName（仅插件内，跨三种贡献类型）；Tool name（全局，model-visible 调用只携带名称）；custom
 * entry type ownership（结构化键 {@code (pluginId, customType)}，不同插件可各自拥有同名 customType）。允许空插件列表。catalog
 * 构建完成后不可变，插件后续变更不产生任何影响。
 */
public final class PluginCatalog {

  private final List<PluginDescriptor> descriptors;
  private final List<ToolContribution> tools;
  private final Map<String, ToolContribution> toolsByName;
  private final Map<ContributionId, ToolContribution> toolsById;
  private final List<CustomEntryTypeContribution> customEntryTypes;
  private final List<ContextProjectorContribution> contextProjectors;
  private final Map<CustomTypeKey, CustomEntryTypeContribution> customEntryTypesByKey;

  private PluginCatalog(
      List<PluginDescriptor> descriptors,
      List<ToolContribution> tools,
      Map<CustomTypeKey, CustomEntryTypeContribution> customEntryTypes,
      List<ContextProjectorContribution> contextProjectors) {
    this.descriptors = List.copyOf(descriptors);
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
    this.customEntryTypes = List.copyOf(customEntryTypes.values());
    this.customEntryTypesByKey = Map.copyOf(customEntryTypes);
    this.contextProjectors = List.copyOf(contextProjectors);
  }

  /** 从插件集合构建冻结 catalog；插件顺序保持注册顺序。 */
  public static PluginCatalog from(Collection<? extends HarnessPlugin> plugins) {
    Objects.requireNonNull(plugins, "plugins");
    Collector collector = new Collector();
    for (HarnessPlugin plugin : plugins) {
      collector.begin(Objects.requireNonNull(plugin, "plugin"));
    }
    return collector.freeze();
  }

  /** 注册顺序的插件 descriptor 列表。 */
  public List<PluginDescriptor> descriptors() {
    return descriptors;
  }

  /** 注册顺序的冻结 Tool 贡献列表。 */
  public List<ToolContribution> tools() {
    return tools;
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

  /** 注册顺序的冻结 custom entry type ownership 列表。 */
  public List<CustomEntryTypeContribution> customEntryTypes() {
    return customEntryTypes;
  }

  /** 注册顺序的冻结 context projector 贡献列表。 */
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
      plugin.contribute(this);
      currentPluginId = null;
    }

    PluginCatalog freeze() {
      for (ToolContribution tool : tools) {
        for (PluginStateDeclaration access : tool.stateAccesses()) {
          CustomTypeKey key = new CustomTypeKey(tool.id().pluginId(), access.customType());
          if (!customEntryTypes.containsKey(key)) {
            throw new IllegalArgumentException(
                "plugin tool " + tool.id() + " accesses unregistered custom entry type " + key);
          }
        }
      }
      return new PluginCatalog(descriptors, tools, customEntryTypes, contextProjectors);
    }

    @Override
    public void registerTool(String localName, PluginTool tool, ToolVisibility visibility) {
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
      tools.add(new ToolContribution(id, tool, descriptor, stateAccesses, visibility));
    }

    @Override
    public void registerCustomEntryType(String localName, String customType) {
      ContributionId id = requireNewContributionId(localName);
      Identifiers.requireCanonical(customType, "customType");
      CustomTypeKey key = new CustomTypeKey(id.pluginId(), customType);
      CustomEntryTypeContribution contribution = new CustomEntryTypeContribution(id, customType);
      if (customEntryTypes.putIfAbsent(key, contribution) != null) {
        throw new IllegalArgumentException(
            "duplicate custom entry type " + key + " in plugin " + id.pluginId());
      }
    }

    @Override
    public void registerContextProjector(String localName, ContextProjector projector) {
      Objects.requireNonNull(projector, "projector");
      ContributionId id = requireNewContributionId(localName);
      contextProjectors.add(new ContextProjectorContribution(id, projector));
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
