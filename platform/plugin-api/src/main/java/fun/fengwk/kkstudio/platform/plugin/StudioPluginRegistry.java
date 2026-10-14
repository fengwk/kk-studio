package fun.fengwk.kkstudio.platform.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 当前 classpath 的安装目录：启动期一次性冻结的全部 {@link StudioPlugin}。
 *
 * <p>目录只收集 Spring 容器中的 bean，因此「是否安装」由依赖决定；相同 {@code pluginId} 出现两次说明两个 JAR 声明了同一身份，启动即失败。
 * 目录构造完成后不可变，没有运行时安装、classloader 热更新或刷新入口。
 */
public final class StudioPluginRegistry {

  private final Map<String, StudioPlugin> pluginsById;

  public StudioPluginRegistry(List<StudioPlugin> plugins) {
    Objects.requireNonNull(plugins, "plugins");
    List<StudioPlugin> ordered = new ArrayList<>(plugins);
    ordered.sort(Comparator.comparing(plugin -> plugin.descriptor().pluginId()));
    Map<String, StudioPlugin> indexed = new LinkedHashMap<>();
    for (StudioPlugin plugin : ordered) {
      Objects.requireNonNull(plugin, "plugins[]");
      PluginDescriptor descriptor = Objects.requireNonNull(plugin.descriptor(), "descriptor");
      StudioPlugin previous = indexed.putIfAbsent(descriptor.pluginId(), plugin);
      if (previous != null) {
        throw new IllegalStateException(
            "duplicate StudioPlugin pluginId: "
                + descriptor.pluginId()
                + " ("
                + previous.getClass().getName()
                + " and "
                + plugin.getClass().getName()
                + ")");
      }
    }
    // 必须保留插入顺序：安装目录的遍历顺序（升序 pluginId）是管理面与 dispatcher 的稳定契约。
    this.pluginsById = Collections.unmodifiableMap(indexed);
  }

  /** 已安装 Plugin 的 descriptor，按 {@code pluginId} 升序冻结。 */
  public List<PluginDescriptor> descriptors() {
    return pluginsById.values().stream().map(StudioPlugin::descriptor).toList();
  }

  /** 已安装 Plugin 的不可变快照，按 {@code pluginId} 升序。 */
  public List<StudioPlugin> plugins() {
    return List.copyOf(pluginsById.values());
  }

  /** 已安装 Plugin 的 {@code pluginId} 快照，供刷新 dispatcher 限定领取范围。 */
  public List<String> pluginIds() {
    return List.copyOf(pluginsById.keySet());
  }

  /** 按安装身份查找；未安装的 id 返回空，绝不加载或合成 Plugin。 */
  public Optional<StudioPlugin> find(String pluginId) {
    return pluginId == null ? Optional.empty() : Optional.ofNullable(pluginsById.get(pluginId));
  }

  public int size() {
    return pluginsById.size();
  }
}
