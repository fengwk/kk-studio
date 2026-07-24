package fun.fengwk.kkstudio.core.harness.tool.worker;

import lombok.Data;

import java.time.OffsetDateTime;

/** Final-schema {@code harness_tool_invocation} row. */
@Data
public class ToolInvocationDO {
  private Long id;
  private Long threadId;
  private Long sessionId;
  private Long assistantEntryId;
  private Integer ordinal;
  private String toolCallId;
  private String descriptorJson;
  private String argumentsJson;
  private String location;
  private String environmentName;
  private Long executionEpoch;
  private String status;
  private Integer attempt;
  private OffsetDateTime nextAttemptAt;
  private String workerToken;
  private OffsetDateTime workerUntil;
  private OffsetDateTime deadlineAt;
  private OffsetDateTime lastActivityAt;
  private String resultJson;
  private String errorJson;
  private OffsetDateTime appliedAt;
  private OffsetDateTime createdAt;
  private OffsetDateTime startedAt;
  private OffsetDateTime finishedAt;
}
