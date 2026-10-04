package fun.fengwk.kkstudio.share.configsync;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** {@code POST /api/settings/sync/import} 请求体：待导入的 YAML 文本；服务端不记录其内容。 */
@Data
public class ConfigSyncImportRequestDTO {

  private String yaml;

  /** 只有用户明确接受跳过项时才允许部分导入，缺省为 false。 */
  private boolean allowPartial;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown config sync import request field: " + fieldName);
  }
}
