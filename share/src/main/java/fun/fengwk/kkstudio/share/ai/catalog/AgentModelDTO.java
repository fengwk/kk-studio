package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

import java.time.Instant;

/** Agent 模型的公开表示，携带一份结构化可执行 {@link #config}。 */
@Data
public class AgentModelDTO {

  /** 所属 Provider 名（资源身份的一部分）。 */
  private String providerName;

  /** 模型逻辑名（与 providerName 共同构成资源身份；支持重命名）。 */
  private String name;

  /** 发往上游 Provider 的真实模型标识；与 {@link #name} 独立且不唯一。 */
  private String modelId;

  /** 可空描述（text，无长度上限）。 */
  private String description;

  /** 完整结构化可执行配置（limit/abilities/pricing/defaultVariant/variants）。 */
  private AgentModelConfigDTO config;

  /** 非负十进制字符串版本号；客户端每次更新时必须回传。 */
  private String version;

  /** 创建时间（UTC Instant）。 */
  private Instant createTime;

  /** 更新时间（UTC Instant）。 */
  private Instant updateTime;
}
