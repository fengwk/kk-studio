package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/** 更新 MODEL GROUP 的请求体；{@code name} 是更新后的逻辑名，支持重命名。 */
@Data
public non-sealed class ModelGroupUpdateDTO implements ModelUpdateDTO {

  /** 必填更新后的全局逻辑名；改名时同步 Agent 引用。 */
  private String name;

  /** 可空描述（text，无长度上限）；null/空白视为清除。 */
  private @Nullable String description;

  /** 必填组配置。 */
  private ModelGroupConfigDTO group;

  /** 必填非负十进制字符串；必须与当前模型版本一致。 */
  private String expectedVersion;
}
