package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.Instant;

/** ToolInvocation read projection; ids are decimal strings of the underlying bigint values. */
@Data
public class ToolInvocationDTO {
  private String id;
  private String threadId;
  private String assistantEntryId;
  private Integer ordinal;
  private String toolCallId;
  private String toolName;
  private String toolVersion;
  private String targetType;
  private String environmentName;
  private String argumentsJson;
  private String status;
  private String permissionAction;
  private String permissionDecision;
  private Instant deadlineAt;
  private String leaseOwner;
  private Instant leaseUntil;
  private Instant cancelRequestedAt;
  private String resultJson;
  private String errorMessage;
  private Instant createTime;
  private Instant startedAt;
  private Instant finishedAt;
  private Instant updateTime;
}
