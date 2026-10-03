package fun.fengwk.kkstudio.share.configsync;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 导入时被明确跳过的条目：kind 为字符串，reason 不含任何配置值或凭据。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfigSyncSkipped {

  private String kind;

  private String name;

  private String reason;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown config sync skipped field: " + fieldName);
  }
}
