package fun.fengwk.kkstudio.core.chat.repo.impl.model;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code chat_session} 行映射：Chat 与 HarnessSession 的多对多成员关系。 */
@Data
public class ChatSessionDO {

  /** 业务主键（Snowflake）。 */
  private Long id;

  /** 所属 Chat。 */
  private Long chatId;

  /** 关联的 HarnessSession。 */
  private Long sessionId;

  /** 关联创建时间（映射 {@code gmt_create}）。 */
  private LocalDateTime createTime;
}
