package fun.fengwk.kkstudio.project.repo;

import fun.fengwk.kkstudio.project.model.IssueEvidence;

import java.util.List;
import java.util.UUID;

/** {@code project_issue_evidence} 持久化端口：Issue 显式公开的 Blob 证据引用。 */
public interface IssueEvidenceRepository {

  /**
   * 插入证据行（仅首次发布生效）。
   *
   * @param evidence 证据实体
   * @return true 表示成功插入新行，false 表示 (issue_id, blob_id) 已存在
   */
  boolean insert(IssueEvidence evidence);

  /** 读取指定 Issue 与 Blob 的证据记录。 */
  IssueEvidence get(UUID issueId, UUID blobId);

  /** 列举指定 Issue 的全部证据（按发布时间倒序）。 */
  List<IssueEvidence> listByIssueId(UUID issueId);

  /** 删除指定 Issue 的全部证据行，返回删除条数。 */
  int deleteByIssueId(UUID issueId);
}
