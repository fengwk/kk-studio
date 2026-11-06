package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/** 创建 MODEL GROUP 的请求体。 */
@Data
public non-sealed class ModelGroupCreateDTO implements ModelCreateDTO {

  /** 必填全局逻辑名：trim 后非空白、无环绕空白、≤128 字符。 */
  private String name;

  /** 可空描述（text，无长度上限）；null/空白视为清除。 */
  private @Nullable String description;

  /** 必填组配置。 */
  private ModelGroupConfigDTO group;
}
