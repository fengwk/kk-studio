package fun.fengwk.kkstudio.core.harness.query;

import lombok.Data;

import java.time.OffsetDateTime;

/** final schema 查询窄行；按 SQL 投影复用，未选中字段保持 null。 */
@Data
public class HarnessQueryRow {
  private Long id;
  private Long sessionId;
  private Long threadId;
  private String title;
  private Long parentEntryId;
  private String entryType;
  private String payloadJson;
  private Long headEntryId;
  private Long inputSequence;
  private Boolean runnable;
  private Long executionEpoch;
  private String processorToken;
  private OffsetDateTime processorUntil;
  private String sessionTitle;
  private Boolean hasQueuedInput;
  private Boolean hasActiveModel;
  private Boolean hasActiveTool;
  private Boolean hasOpenInteraction;
  private Long sequence;
  private String inputType;
  private String idempotencyKey;
  private String status;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
  private OffsetDateTime appliedAt;
}
