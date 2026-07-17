package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Public read representation of an Environment.
 *
 * <p>主键在 HTTP / DTO 边界以十进制字符串暴露，与项目内其它 snowflake 资源保持一致；持久层在 {@link
 * fun.fengwk.kkstudio.core.environment.service.model.ToolEnvironment} 仍为 {@code Long}。
 *
 * <p>{@code capabilitiesJson} 和 {@code lastSeenAt} 仅由 daemon-facing application service 写入，REST
 * create/update 不接受这两个字段，避免外部调用者直接伪造 capability 或心跳时间。
 */
@Data
public class ToolEnvironmentDTO {

  private String id;
  private String name;
  private String description;
  private String capabilitiesJson;
  private LocalDateTime lastSeenAt;
  private Long version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
