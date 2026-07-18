package fun.fengwk.kkstudio.core.harness.session.store.model;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code harness_session_entry} 行映射：append-only 语义历史树节点。 */
@Data
public class HarnessSessionEntryDO {
  /** 业务主键。 */
  private Long id;

  /** 所属 Session。 */
  private Long sessionId;

  /** 父 Entry；根消息为空。 */
  private Long parentEntryId;

  /** 写入该 Entry 的 Run；部分系统 Entry 可为空。 */
  private Long runId;

  /** 语义类型（message / tool / snapshot 等）。 */
  private String entryType;

  /** 语义 payload JSON。 */
  private String payloadJson;

  /** 创建时间（映射 {@code gmt_create}）；Entry 不可变后不更新。 */
  private LocalDateTime createTime;
}
