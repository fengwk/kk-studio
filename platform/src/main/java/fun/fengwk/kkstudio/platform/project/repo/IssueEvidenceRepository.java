package fun.fengwk.kkstudio.platform.project.repo;

import fun.fengwk.kkstudio.platform.project.model.IssueEvidence;

import java.util.List;
import java.util.UUID;

/**
 * Issue 公开证据仓储。
 *
 * <p>身份为 {@code (issueId, blobId)}：只插入、不更新；已存在时插入是 no-op，因此发布天然幂等，调用方据此决定是否 retain。
 */
public interface IssueEvidenceRepository {

  /** 幂等插入：返回是否真正新增了一行（同一 {@code (issueId, blobId)} 已发布时返回 {@code false}，未重复计数）。 */
  boolean insertIfAbsent(IssueEvidence evidence);

  IssueEvidence findByIssueIdAndBlobId(UUID issueId, UUID blobId);

  /** 有界读取：按发布时间倒序返回最多 {@code limit} 条，供公开读取面使用。 */
  List<IssueEvidence> listRecentByIssueId(UUID issueId, int limit);

  /** 该 Issue 已发布证据的全部 blob id（授权、释放与对账用，按 blob id 升序）。 */
  List<UUID> listBlobIdsByIssueId(UUID issueId);

  int deleteByIssueId(UUID issueId);
}
