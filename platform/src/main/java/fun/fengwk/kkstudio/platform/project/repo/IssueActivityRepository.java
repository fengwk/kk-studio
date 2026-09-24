package fun.fengwk.kkstudio.platform.project.repo;

import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityKind;

import java.util.List;
import java.util.UUID;

/**
 * Issue Activity 事实流仓储。
 *
 * <p>只追加、不修改：投递游标指向 {@code sequence}，打回计数按本流确定性推导，因此没有可与事实流漂移的冗余计数。
 */
public interface IssueActivityRepository {

  /** 幂等追加：sequence 为空时按 Issue 内单调序号分配；同 Issue 幂等键已存在时返回既有记录。 */
  IssueActivity appendOrGet(IssueActivity activity);

  IssueActivity findByIssueIdAndIdempotencyKey(UUID issueId, String idempotencyKey);

  List<IssueActivity> listByIssueId(UUID issueId);

  /** 按 {@code sequence} 升序返回 {@code sequence > afterSequence} 的最多 {@code limit} 条，供有界分页读取。 */
  List<IssueActivity> listPage(UUID issueId, long afterSequence, int limit);

  boolean existsByIssueIdAndKind(UUID issueId, IssueActivityKind kind);

  /** 判断游标之后是否已存在指定类型的事实，供控制器做有界的具体动作判定。 */
  boolean existsAfterSequenceAndKind(UUID issueId, long afterSequence, IssueActivityKind kind);

  /** 返回最近一次开启打回计数区间的 sequence（人工恢复或确认新要求），无则为 0。 */
  long findReviewWindowStartSequence(UUID issueId);

  /** 统计区间内对不同有效提交作出的正式打回次数（重放同一决定不重复计数）。 */
  long countRejectionsSince(UUID issueId, long afterSequence);

  /** 统计指定操作者类型在区间内作出的正式打回次数，用于「无对应 Agent 授权时人工审查」等判定。 */
  long countRejectionsSince(UUID issueId, long afterSequence, IssueActivityActorType actorType);

  int deleteByIssueId(UUID issueId);
}
