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

  /** 可选默认 Agent definition id；不建立外键，Agent 删除后可保留陈旧值。 */
  private Long defaultAgentId;

  /** 乐观锁行版本。 */
  private Long version;

  /** 创建时间（映射 {@code created_at} timestamptz）。 */
  private Instant createTime;

  /** 更新时间（映射 {@code updated_at} timestamptz）。 */
  private Instant updateTime;
}
