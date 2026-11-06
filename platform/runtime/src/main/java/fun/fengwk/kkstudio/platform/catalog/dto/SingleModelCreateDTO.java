package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/** 创建单 MODEL 的请求体。 */
@Data
public non-sealed class SingleModelCreateDTO implements ModelCreateDTO {

  /** 必填全局逻辑名：trim 后非空白、无环绕空白、≤128 字符。 */
  private String name;

  /** 可空描述（text，无长度上限）；null/空白视为清除。 */
  private @Nullable String description;

  /** 必填 Provider 名。 */
  private String providerName;

  /** 必填发往上游 Provider 的真实模型标识。 */
  private String modelId;

  /** 必填结构化配置。 */
  private ModelConfigDTO config;
}
