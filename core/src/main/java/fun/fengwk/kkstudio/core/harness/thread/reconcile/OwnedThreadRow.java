package fun.fengwk.kkstudio.core.harness.thread.reconcile;

import lombok.Data;

import java.time.OffsetDateTime;

/** {@code lockThread} 的 Thread 行投影；包含校验 ownership 和推进 head 所需的全部字段。 */
@Data
public class OwnedThreadRow {
  private long id;
  private long sessionId;
  private long headEntryId;
  private long inputSequence;
  private boolean runnable;
  private long executionEpoch;
  private String processorToken;
  private OffsetDateTime processorUntil;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
}
