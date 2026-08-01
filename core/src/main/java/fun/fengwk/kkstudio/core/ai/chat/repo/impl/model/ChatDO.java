package fun.fengwk.kkstudio.core.ai.chat.repo.impl.model;

import lombok.Data;

import java.time.Instant;

/** {@code chat} 行映射：持久化的多 Session 集合。 */
@Data
public class ChatDO {

  /** 业务主键。 */
  private Long id;

  /** 可选标题。 */
  private String title;

  /** 必填 Agent definition name；不建立外键，Agent 删除后可保留陈旧值。 */
  private String agentName;

  /** 可选默认 Environment 名称；不建立外键，Environment 是 live runtime 身份。 */
  private String environmentName;

  /** 可见发送权限模式。 */
  private boolean yoloEnabled;

  /** 乐观锁行版本。 */
  private Long version;

  /** 创建时间（映射 {@code created_at} timestamptz）。 */
  private Instant createTime;

  /** 更新时间（映射 {@code updated_at} timestamptz）。 */
  private Instant updateTime;
}
