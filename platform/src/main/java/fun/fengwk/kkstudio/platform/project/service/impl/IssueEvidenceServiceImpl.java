package fun.fengwk.kkstudio.platform.project.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.plugin.resource.SessionResourceUri;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.IssueEvidence;
import fun.fengwk.kkstudio.platform.project.model.IssueEvidenceOrigin;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueEvidenceRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Issue 公开证据的原子实现。
 *
 * <p>两条公开入口都在调用方事务内完成全部副作用：先以 Issue 自有引用 retain 一次（已发布则跳过，绝不重复计数），再向该 Issue 当时已绑定的参与者 Session
 * 幂等授予同一 Blob，最后人工入口释放 upload owner。授权与发布失败都让整个事务回滚，因此不会出现 「Issue 有证据行但引用未 retain」或「Session 被授权而
 * Issue 无证据」的漂移。
 */
@AllArgsConstructor
@Service
public class IssueEvidenceServiceImpl implements IssueEvidenceService {

  private static final String EVIDENCE_NOT_FOUND_IN_SESSION =
      "Final summary references a resource that the source run session does not hold";

  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueAgentSessionRepository issueAgentSessionRepository;
  private final IssueEvidenceRepository issueEvidenceRepository;
  private final IssueService issueService;
  private final StorageUploadService uploadService;
  private final StorageBlobManager blobManager;
  private final SessionBlobRefManager refManager;

  @Transactional(readOnly = true)
  @Override
  public List<IssueEvidence> listEvidence(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    return issueEvidenceRepository.listRecentByIssueId(issueId, MAX_EVIDENCE_LIMIT);
  }

  @Transactional
  @Override
  public IssueEvidence publishHumanUpload(UUID issueId, UUID uploadId) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(uploadId, "uploadId");

    Issue issueRef = issueRepository.getById(issueId);
    if (issueRef == null) {
      throw new AiResourceNotFoundException("issue");
    }
    projectRepository.lockById(issueRef.getProjectId());
    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue");
    }
    if (issue.isArchived()) {
      throw new AiValidationException(
          "issue_evidence", "Cannot publish evidence on an archived issue");
    }

    StorageUploadService.ReadyUpload ready = lockReadyUpload(uploadId);
    IssueEvidence evidence =
        publish(issue.getId(), ready.blobId(), IssueEvidenceOrigin.HUMAN, null, ready.filename());
    uploadService.delete(uploadId);
    issueService.appendActivity(evidenceActivity(evidence));
    return evidence;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  @Override
  public void publishExecutorEvidence(UUID issueId, UUID runId, String agentName, String summary) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(agentName, "agentName");

    List<UUID> citedBlobIds = SessionResourceUri.scan(summary);
    if (citedBlobIds.isEmpty()) {
      return;
    }

    IssueAgentSession agentSession =
        issueAgentSessionRepository.findByIssueIdAndAgentName(issueId, agentName);
    UUID sessionId = agentSession != null ? agentSession.getSessionId() : null;
    for (UUID blobId : citedBlobIds) {
      // 只公开来源 Run 的 Session 在提交时确实持有的引用：URI 不是权限凭据，不持有即拒绝整个提交，
      // 绝不把私有资源静默变成公开证据。
      if (sessionId == null || !refManager.contains(sessionId, blobId)) {
        throw new AiValidationException("issue_evidence", EVIDENCE_NOT_FOUND_IN_SESSION);
      }
    }
    for (UUID blobId : citedBlobIds) {
      publish(issueId, blobId, IssueEvidenceOrigin.EXECUTOR, runId, null);
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  @Override
  public void grantPublishedEvidence(UUID issueId, UUID sessionId) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(sessionId, "sessionId");
    for (UUID blobId : issueEvidenceRepository.listBlobIdsByIssueId(issueId)) {
      refManager.retainRef(sessionId, blobId);
    }
  }

  @Transactional
  @Override
  public int deleteByIssue(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    List<UUID> blobIds = issueEvidenceRepository.listBlobIdsByIssueId(issueId);
    int deleted = issueEvidenceRepository.deleteByIssueId(issueId);
    for (UUID blobId : blobIds) {
      if (!blobManager.release(blobId)) {
        throw new IllegalStateException("issue evidence blob release returned false for " + blobId);
      }
    }
    return deleted;
  }

  /** 幂等发布一个已发布 Blob 引用：证据行已存在时不再 retain（重复发布不双计），但 Session 授权仍按当前绑定补齐。 */
  private IssueEvidence publish(
      UUID issueId, UUID blobId, IssueEvidenceOrigin origin, UUID runId, String name) {
    boolean inserted =
        issueEvidenceRepository.insertIfAbsent(
            IssueEvidence.builder()
                .issueId(issueId)
                .blobId(blobId)
                .origin(origin)
                .runId(runId)
                .name(name)
                .build());
    if (inserted) {
      try {
        blobManager.retain(blobId);
      } catch (StorageResourceNotFoundException error) {
        throw new AiValidationException(
            "issue_evidence", "Evidence blob is not available for publication");
      }
    }
    for (IssueAgentSession binding : issueAgentSessionRepository.listByIssueId(issueId)) {
      refManager.retainRef(binding.getSessionId(), blobId);
    }
    return issueEvidenceRepository.findByIssueIdAndBlobId(issueId, blobId);
  }

  private StorageUploadService.ReadyUpload lockReadyUpload(UUID uploadId) {
    try {
      return uploadService.lockReady(uploadId);
    } catch (StorageResourceNotFoundException error) {
      throw new AiResourceNotFoundException("storage_upload");
    } catch (StorageVerificationException error) {
      throw new AiValidationException(
          "issue_evidence", "Upload is not ready to be published as evidence");
    }
  }

  /** 人工公开附件是人为事实，必须在有序事实流里留痕；执行者发表的证据由 Run 结果与证据行本身溯源。 */
  private IssueActivity evidenceActivity(IssueEvidence evidence) {
    StringBuilder body = new StringBuilder("Published issue evidence ").append(uri(evidence));
    if (evidence.getName() != null) {
      body.append(" (").append(evidence.getName()).append(')');
    }
    return IssueActivity.builder()
        .issueId(evidence.getIssueId())
        .kind(IssueActivityKind.COMMENT)
        .actorType(IssueActivityActorType.HUMAN)
        .body(body.toString())
        .idempotencyKey("evidence:" + evidence.getBlobId())
        .build();
  }

  private static String uri(IssueEvidence evidence) {
    return SessionResourceUri.format(evidence.getBlobId());
  }
}
