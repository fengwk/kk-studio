package fun.fengwk.kkstudio.platform.configsync.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/** 导入时被明确跳过的条目：kind 只对被识别的配置集合有值（未知顶层类别为 null），reason 不含任何配置值或凭据。 */
@Data
public class ConfigSyncSkipped {

  private @Nullable ConfigSyncKind kind;

  private String name;

  private String reason;

  public ConfigSyncSkipped() {}

  public ConfigSyncSkipped(@Nullable ConfigSyncKind kind, String name, String reason) {
    this.kind = kind;
    this.name = name;
    this.reason = reason;
  }
}
