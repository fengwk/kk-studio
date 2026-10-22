package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

import java.time.Instant;

/** 统一 Agent 模型资源：单 MODEL 与 MODEL GROUP 共享唯一的全局逻辑名称空间。 */
@Data
public class AgentModelDTO {

  /** 全局唯一逻辑名称（主键）；单 Model 与模型组不可重名。 */
  private String name;

  /** 资源类型。 */
  private AgentModelKind kind;

  /** 可空描述。 */
  private String description;

  /** MODEL 所属 Provider 名；GROUP 为 null。 */
  private String providerName;

  /** MODEL 发往上游的真实模型标识；GROUP 为 null。 */
  private String modelId;

  /** MODEL 的结构化可执行配置；GROUP 为 null，组价格/能力以成员为准。 */
  private AgentModelConfigDTO config;

  /** GROUP 的有序成员、立即降级状态码与固定冷却秒数；MODEL 为 null。 */
  private AgentModelGroupDTO group;

  /** 非负十进制字符串版本号；客户端每次更新时必须回传。 */
  private String version;

  /** 创建时间（UTC Instant）。 */
  private Instant createTime;

  /** 更新时间（UTC Instant）。 */
  private Instant updateTime;
}
