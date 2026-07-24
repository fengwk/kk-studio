package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.Instant;

/** ToolInvocation read projection; ids are decimal strings of the underlying bigint values. */
@Data
public class ToolInvocationDTO {
  private String id;
  private String threadId;
  private String sessionId;
  private String assistantEntryId;
  private Integer ordinal;
  private String toolCallId;
  private String toolName;
  private String toolVersion;
  private String location;
  private String environmentName;
  private String argumentsJson;
  private Long executionEpoch;
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
