package fun.fengwk.kkstudio.share.configsync;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * 单个配置条目的稳定身份：{@code kind + name}。
 *
 * <p>name 是现有业务名称：Provider/Agent/Environment/MCP 为资源名，Model 为 {@code providerName/modelName}，Skill
 * Package 为 packageName，Settings 固定为 {@code settings}。引用不携带任何配置值或凭据。
 */
@Data
public class ConfigSyncRef {

  private ConfigSyncKind kind;

  private String name;

  public ConfigSyncRef() {}

  public ConfigSyncRef(ConfigSyncKind kind, String name) {
    this.kind = kind;
    this.name = name;
  }

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown config sync ref field: " + fieldName);
  }
}
