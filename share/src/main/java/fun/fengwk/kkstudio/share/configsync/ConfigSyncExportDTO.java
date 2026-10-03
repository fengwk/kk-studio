package fun.fengwk.kkstudio.share.configsync;

import lombok.Data;

/** {@code POST /api/settings/sync/export} 响应：完整 YAML 文本，可能内嵌凭据，调用方必须禁止缓存。 */
@Data
public class ConfigSyncExportDTO {

  private String yaml;

  public ConfigSyncExportDTO() {}

  public ConfigSyncExportDTO(String yaml) {
    this.yaml = yaml;
  }
}
