package fun.fengwk.kkstudio.share.configsync;

import lombok.Data;

import java.util.List;

/** {@code GET /api/settings/sync} 的响应：当前所有可同步条目及其依赖闭包；不含配置值和凭据。 */
@Data
public class ConfigSyncInventoryDTO {

  private List<ConfigSyncItem> items;

  public ConfigSyncInventoryDTO() {}

  public ConfigSyncInventoryDTO(List<ConfigSyncItem> items) {
    this.items = items;
  }
}
