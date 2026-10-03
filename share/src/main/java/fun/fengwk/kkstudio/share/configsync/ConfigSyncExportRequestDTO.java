package fun.fengwk.kkstudio.share.configsync;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.List;

/** {@code POST /api/settings/sync/export} 请求体：待导出的条目选择；后端负责补齐依赖闭包。 */
@Data
public class ConfigSyncExportRequestDTO {

  private List<ConfigSyncRef> items;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown config sync export request field: " + fieldName);
  }
}
