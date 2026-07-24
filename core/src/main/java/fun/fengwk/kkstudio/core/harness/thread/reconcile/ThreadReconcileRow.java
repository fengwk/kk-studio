package fun.fengwk.kkstudio.core.harness.thread.reconcile;

import lombok.Data;

import java.time.OffsetDateTime;

/** final-schema reconcile mapper 的扁平行对象；按查询填充所需字段。 */
@Data
public class ThreadReconcileRow {
  private long id;
  private long threadId;
  private long sessionId;
  private Long parentEntryId;
  private long headEntryId;
  private long inputSequence;
  private long sourceHeadEntryId;
  private long executionEpoch;
  private long sequence;
  private int ordinal;
  private boolean runnable;
  private String processorToken;
  private OffsetDateTime processorUntil;
  private String status;
  private String entryType;
  private String inputType;
  private String payloadJson;
  private String requestJson;
  private String resultJson;
  private String errorJson;
  private String descriptorJson;
  private String argumentsJson;
  private String toolCallId;
  private String location;
  private String environmentName;
  private String idempotencyKey;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
  private OffsetDateTime appliedAt;
  private OffsetDateTime finishedAt;
}
