package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/** 更新单 MODEL 的请求体；{@code name} 是更新后的逻辑名，支持重命名。 */
@Data
public non-sealed class SingleModelUpdateDTO implements ModelUpdateDTO {

  /** 必填更新后的全局逻辑名；改名时同步 Agent 与组成员引用。 */
  private String name;

  /** 可空描述（text，无长度上限）；null/空白视为清除。 */
  private @Nullable String description;

  /** 必填 Provider 名。 */
  private String providerName;

  /** 必填发往上游 Provider 的真实模型标识。 */
  private String modelId;

  /** 必填结构化配置。 */
  private ModelConfigDTO config;

  /** 必填非负版本号；必须与当前模型版本一致。 */
  private Long expectedVersion;
}
