package fun.fengwk.kkstudio.platform.project.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Issue Activity：唯一有序事实流上的一条记录。
 *
 * <p>记录类型、操作者、目标角色/Run（若有）、相关 Run/提交、正文、时间与幂等键；投递游标指向 {@code sequence}，打回计数按“不同有效提交”从本流确定性计算。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueActivity {

  private UUID issueId;
  private long sequence;
  private IssueActivityKind kind;
  private IssueActivityActorType actorType;

  /** AGENT 操作者身份；HUMAN/SYSTEM 必须为空。 */
  private String actorAgentName;

  /** 定向目标职责；为空表示无目标。 */
  private IssueRunRole targetRole;

  /** 相关 Run（同 Issue），可空。 */
  private UUID runId;

  /** 正式审查决定绑定的被审查提交 Run，仅 REVIEW_DECISION 非空。 */
  private UUID submissionRunId;

  /** 正式审查决定，仅 REVIEW_DECISION 非空。 */
  private ReviewDecision decision;

  private String body;
  private String idempotencyKey;
  private Instant createdAt;

  /** 是否是一次会计入打回阈值的正式打回。 */
  public boolean isRejection() {
    return kind == IssueActivityKind.REVIEW_DECISION
        && decision == ReviewDecision.REQUEST_CHANGES
        && submissionRunId != null;
  }

  /** 是否开启新的打回计数区间（人工恢复或确认新要求）。 */
  public boolean startsReviewWindow() {
    return kind == IssueActivityKind.RECOVERY || kind == IssueActivityKind.SPEC_CHANGE;
  }
}
