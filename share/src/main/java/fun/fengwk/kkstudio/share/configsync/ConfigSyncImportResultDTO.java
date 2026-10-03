package fun.fengwk.kkstudio.share.configsync;

import lombok.Data;

import java.util.List;

/** {@code POST /api/settings/sync/import} 响应：实际写入的条目与明确跳过的条目。 */
@Data
public class ConfigSyncImportResultDTO {

  private List<ConfigSyncRef> imported;

  private List<ConfigSyncSkipped> skipped;

  public ConfigSyncImportResultDTO() {}

  public ConfigSyncImportResultDTO(
      List<ConfigSyncRef> imported, List<ConfigSyncSkipped> skipped) {
    this.imported = imported;
    this.skipped = skipped;
  }
}
