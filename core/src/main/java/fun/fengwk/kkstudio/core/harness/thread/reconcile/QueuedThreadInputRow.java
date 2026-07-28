package fun.fengwk.kkstudio.core.harness.thread.reconcile;

import lombok.Data;

import java.time.OffsetDateTime;

/** {@code listQueuedInputs} 的排队中 ThreadInput 投影；字段足以还原 typed mailbox input。 */
@Data
public class QueuedThreadInputRow {
  private long id;
  private long threadId;
  private long sequence;
  private String inputType;
  private String payloadJson;
  private String idempotencyKey;
  private String status;
  private OffsetDateTime createdAt;
  private OffsetDateTime appliedAt;
}
