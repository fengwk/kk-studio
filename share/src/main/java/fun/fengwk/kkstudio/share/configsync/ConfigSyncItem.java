package fun.fengwk.kkstudio.share.configsync;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * inventory 中的一个配置条目：{@link ConfigSyncRef} 加上导出该条目时需要一并带上的依赖闭包。
 *
 * <p>dependencies 已经过传递闭包与去重，互相引用的 Agent 会终止；UI 可直接按它展示导出的完整范围。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ConfigSyncItem extends ConfigSyncRef {

  private List<ConfigSyncRef> dependencies;

  public ConfigSyncItem() {}

  public ConfigSyncItem(ConfigSyncKind kind, String name, List<ConfigSyncRef> dependencies) {
    super(kind, name);
    this.dependencies = dependencies;
  }
}
