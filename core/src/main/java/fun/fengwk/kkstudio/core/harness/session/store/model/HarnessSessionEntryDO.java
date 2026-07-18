package fun.fengwk.kkstudio.core.harness.session.store.model;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code harness_session_entry} 行映射：append-only tree 节点。 */
@Data
public class HarnessSessionEntryDO {
  /** 业务主键。 */
  private Long id;

  /** 所属 Session。 */
  private Long sessionId;

  /** 父 Entry；根节点为空。 */
  private Long parentEntryId;

  /** 语义 entry 类型（message/agent_snapshot/...）。 */
  private String entryType;

  /** 语义 payload JSON。 */
  private String payloadJson;

  /** 创建时间（映射 {@code gmt_create}）。 */
  private LocalDateTime createTime;
}
