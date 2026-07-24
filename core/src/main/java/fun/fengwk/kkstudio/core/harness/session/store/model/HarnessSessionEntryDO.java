package fun.fengwk.kkstudio.core.harness.session.store.model;

import lombok.Data;

import java.time.OffsetDateTime;

/** {@code harness_entry} 行映射：append-only tree 节点。 */
@Data
public class HarnessSessionEntryDO {
  /** 业务主键。 */
  private Long id;

  /** 所属 Session。 */
  private Long sessionId;

  /** 父 Entry；根节点为空。 */
  private Long parentEntryId;

  /** 语义 entry 类型（ROOT/MESSAGE/RUNTIME_CONFIG/...）。 */
  private String entryType;

  /** 语义 payload JSON 文本（落库时 cast 为 jsonb）。 */
  private String payloadJson;

  /** 创建时间（映射 {@code created_at}）。 */
  private OffsetDateTime createdAt;
}
