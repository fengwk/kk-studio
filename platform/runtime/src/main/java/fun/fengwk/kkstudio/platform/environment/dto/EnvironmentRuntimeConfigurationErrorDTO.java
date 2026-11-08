package fun.fengwk.kkstudio.platform.environment.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/** 配置应用失败的稳定规则结果；{@code code} 与 {@code field} 不包含输入值、命令参数或 token。 */
@Data
public class EnvironmentRuntimeConfigurationErrorDTO {
  private Long version;
  private String code;
  private @Nullable String field;
}
