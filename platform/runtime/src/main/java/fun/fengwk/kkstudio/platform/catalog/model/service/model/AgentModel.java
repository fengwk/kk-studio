package fun.fengwk.kkstudio.platform.catalog.model.service.model;

import lombok.Data;

import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;

import java.time.Instant;

/** 统一 Agent model 资源：单 MODEL 或按序降级的 MODEL GROUP。 */
@Data
public class AgentModel {

  /** 全局唯一逻辑名称；单 Model 与模型组共享同一名称空间。 */
  private String name;

  /** 资源类型。 */
  private AgentModelKind kind;

  /** MODEL 绑定 Provider 名称；GROUP 为 null。 */
  private String providerName;

  /** MODEL 发往上游 Provider 的真实模型标识；GROUP 为 null。 */
  private String modelId;

  /** 描述，可选。 */
  private String description;

  /** MODEL 结构化配置 JSON；GROUP 为 null。 */
  private String configJson;

  /** GROUP 组配置 JSON；MODEL 为 null。 */
  private String groupConfigJson;

  /** 乐观锁版本：非负，从 0 开始，每次写操作 +1。 */
  private Long version;

  private Instant createTime;

  private Instant updateTime;
}
