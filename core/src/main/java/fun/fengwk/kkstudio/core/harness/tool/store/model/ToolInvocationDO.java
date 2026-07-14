package fun.fengwk.kkstudio.core.harness.tool.store.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ToolInvocationDO {
  private Long id;
  private Long runId;
  private Long assistantEntryId;
  private Integer ordinal;
  private String toolCallId;
  private String toolName;
  private String toolVersion;
  private String targetType;
  private Long environmentId;
  private String argumentsJson;
  private String status;
  private String permissionAction;
  private String permissionDecision;
  private LocalDateTime deadlineAt;
  private String leaseOwner;
  private LocalDateTime leaseUntil;
  private LocalDateTime cancelRequestedAt;
  private String resultJson;
  private String errorMessage;
  private LocalDateTime createTime;
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;
  private LocalDateTime updateTime;
}
