package fun.fengwk.kkstudio.core.environment.repo.impl.model;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code tool_environment} 行映射：全局 Environment daemon 注册表。 */
@Data
public class ToolEnvironmentDO {

  /** 业务主键（Snowflake）。 */
  private Long id;

  /** Environment 唯一名。 */
  private String name;

  /** 描述。 */
  private String description;

  /** daemon 上报的 capabilities 规范 JSON。 */
  private String capabilitiesJson;

  /** 最近一次 daemon 主动上报时间。 */
  private LocalDateTime lastSeenAt;

  /** 乐观锁行版本。 */
  private Long version;

  /** 创建时间（映射 {@code gmt_create}）。 */
  private LocalDateTime createTime;

  /** 更新时间（映射 {@code gmt_modified}）。 */
  private LocalDateTime updateTime;
}
