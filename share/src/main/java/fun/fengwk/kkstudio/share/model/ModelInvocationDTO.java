package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.Instant;

/** ModelInvocation read projection; ids are decimal strings of the underlying bigint values. */
@Data
public class ModelInvocationDTO {
  private String id;
  private String threadId;
  private String sessionId;
  private String sourceHeadEntryId;
  private Long executionEpoch;
  private String requestJson;
  private String status;
  private Integer attempt;
  private Instant nextAttemptAt;
  private Instant workerUntil;
  private Instant deadlineAt;
  private Instant lastActivityAt;
  private String resultJson;
  private String errorJson;
  private Instant appliedAt;
  private Instant createdAt;
  private Instant startedAt;
  private Instant finishedAt;
}
