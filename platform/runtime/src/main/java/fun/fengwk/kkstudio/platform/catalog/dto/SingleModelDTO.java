package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/** 单 MODEL 资源。 */
@Data
public non-sealed class SingleModelDTO implements ModelDTO {

  /** 全局唯一逻辑名。 */
  private String name;

  /** 可空描述。 */
  private @Nullable String description;

  /** 所属 Provider 名。 */
  private String providerName;

  /** 发往上游的真实模型标识。 */
  private String modelId;

  /** 结构化可执行配置。 */
  private ModelConfigDTO config;

  /** 非负十进制字符串版本号。 */
  private String version;

  /** 创建时间。 */
  private Instant createdAt;

  /** 更新时间。 */
  private Instant updatedAt;
}
