package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/** MODEL GROUP 资源；{@code config} 是从成员派生的只读能力摘要，不承载组级价格或 Variant。 */
@Data
public non-sealed class ModelGroupDTO implements ModelDTO {

  /** 全局唯一逻辑名。 */
  private String name;

  /** 可空描述。 */
  private @Nullable String description;

  /** 有序成员、立即降级状态码与固定冷却秒数。 */
  private ModelGroupConfigDTO group;

  /** 从成员派生的只读能力摘要。 */
  private ModelConfigDTO config;

  /** 非负十进制字符串版本号。 */
  private String version;

  /** 创建时间。 */
  private Instant createdAt;

  /** 更新时间。 */
  private Instant updatedAt;
}
