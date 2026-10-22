package fun.fengwk.kkstudio.platform.catalog.model.repo.impl.model;

import lombok.Data;

import java.time.Instant;

/** {@code agent_model} 行映射：单 Model 与 Model Group 共享全局 name 主键。 */
@Data
public class AgentModelDO {

  /** 全局唯一逻辑名称（主键）。 */
  private String name;

  /** MODEL / GROUP。 */
  private String kind;

  /** MODEL 的 Provider 名；GROUP 为 null。 */
  private String providerName;

  /** MODEL 的真实 wire 模型标识；GROUP 为 null。 */
  private String modelId;

  /** 可空描述。 */
  private String description;

  /** MODEL 配置 JSON；GROUP 为 null。 */
  private String configJson;

  /** GROUP 配置 JSON；MODEL 为 null。 */
  private String groupConfigJson;

  private Long version;

  private Instant createTime;

  private Instant updateTime;
}
