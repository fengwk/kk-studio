package fun.fengwk.kkstudio.platform.project.service;

import fun.fengwk.kkstudio.platform.project.model.IssueEvidence;

import java.util.List;
import java.util.UUID;

public interface IssueEvidenceService {
  /** 单次列举返回的证据上限（按发布时间倒序）。 */
  int MAX_EVIDENCE_LIMIT = 100;

  /** 人工上传转 Issue 公开证据：lockReady -> retain Issue 引用 -> delete upload，原子转移，不复制字节。 */
  IssueEvidence publishHumanUpload(UUID issueId, UUID uploadId);

  /** 显式发布一个已存在的 Blob 为 Issue 证据；来源 Run 非空时必须有 Agent 作者。 */
  IssueEvidence publishBlob(
      UUID issueId, String actorAgentName, UUID runId, UUID blobId, String name);

  List<IssueEvidence> listEvidence(UUID issueId);

  /** 深删除：释放本 Issue 持有的全部 Blob 引用，返回释放条数。 */
  int releaseAll(UUID issueId);
}
