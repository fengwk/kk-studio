package fun.fengwk.kkstudio.platform.project.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Issue 核心实体：当前要求、验收依据、七态阶段与执行/审查角色。
 *
 * <p>正文只在 BACKLOG/TODO 编辑；进行中只追加有来源的指示与证据。不另存规格版本或打回计数，投递位置与打回 次数都由 {@link IssueActivity}
 * 有序事实流确定性推导。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Issue {

  private UUID id;
  private UUID projectId;
  private long number;
  private String title;
  private String description;
  private IssueStatus status;

  /** EXECUTOR 职责的 Agent 身份。 */
  private String assigneeAgentName;

  /** REVIEWER 职责的 Agent 身份；为空表示等待人工审查。 */
  private String reviewerAgentName;

  private long version;
  private Instant archivedAt;
  private Instant createdAt;
  private Instant updatedAt;

  public boolean isArchived() {
    return archivedAt != null;
  }

  public boolean isTerminal() {
    return status != null && status.isTerminal();
  }

  public boolean canBeArchived() {
    return status != null && status.canBeArchived();
  }

  /** 正文（要求/验收依据）是否允许直接编辑。 */
  public boolean isRequirementEditable() {
    return status == IssueStatus.BACKLOG || status == IssueStatus.TODO;
  }
}
