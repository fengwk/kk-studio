package fun.fengwk.kkstudio.core.ai.runtime.thread.command;

import lombok.Data;

import java.time.OffsetDateTime;

/** final {@code harness_session/harness_entry/harness_thread/harness_thread_input} 查询窄行。 */
@Data
public class ThreadCommandRow {
  private Long id;
  private Long sessionId;
  private Long threadId;
  private Long parentEntryId;
  private String title;
  private Long headEntryId;
  private String environmentName;
  private Long inputSequence;
  private Boolean runnable;
  private Long executionEpoch;
  private Long revision;
  private String processorToken;
  private OffsetDateTime processorUntil;
  private String entryType;
  private String payloadJson;
  private Long sequence;
  private String inputType;
  private String idempotencyKey;
  private String status;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
  private OffsetDateTime appliedAt;
}
