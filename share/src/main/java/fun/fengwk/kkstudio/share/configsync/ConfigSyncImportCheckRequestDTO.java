package fun.fengwk.kkstudio.share.configsync;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** 导入预检查请求；服务端不记录 YAML 内容。 */
@Data
public class ConfigSyncImportCheckRequestDTO {

  private String yaml;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown config sync check request field: " + fieldName);
  }
}
