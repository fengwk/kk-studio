package fun.fengwk.kkstudio.project.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.project.error.ProjectNotFoundException;
import fun.fengwk.kkstudio.project.error.ProjectValidationException;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueActivity;
import fun.fengwk.kkstudio.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.project.model.IssueEvidence;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.port.EvidenceBlobPort;
import fun.fengwk.kkstudio.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.project.repo.IssueEvidenceRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.project.service.impl.IssueActivityIdempotency.Identity;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** {@link IssueEvidenceService} 契约实现。 */
@AllArgsConstructor
@Service
public class IssueEvidenceServiceImpl implements IssueEvidenceService {

  /** 托管资源 URI 前缀：与平台 {@code SessionResourceUri} 的规范形态一致；project 不反向依赖 platform，故只在此保留协议常量。 */
  private static final String RESOURCE_URI_PREFIX = "kkstudio:/resources/";

  private final IssueEvidenceRepository issueEvidenceRepository;
  private final IssueRepository issueRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueActivityRepository issueActivityRepository;
  private final ProjectRepository projectRepository;

  private final EvidenceBlobPort blobPort;

  @Override
  @Transactional
  public IssueEvidence publishHumanUpload(UUID issueId, UUID uploadId) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(uploadId, "uploadId");

    // 与 IssueService/RunService 统一的锁序：Project FOR KEY SHARE -> Issue FOR UPDATE。Issue 行锁同时保证事实流游标
    // （next_activity_sequence）在并发发布下单调且只推进一次。
    Issue initial = issueRepository.getById(issueId);
    if (initial == null) {
      throw new ProjectNotFoundException("issue");
    }
    Project project = projectRepository.lockForKeyShare(initial.getProjectId());
    if (project == null) {
      throw new ProjectNotFoundException("project");
    }
    if (project.isArchived()) {
      throw new ProjectValidationException(
          "project", "Cannot publish evidence in an archived project");
    }
    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new ProjectNotFoundException("issue");
    }
    if (!issue.getProjectId().equals(project.getId())) {
      throw new ProjectValidationException("issue", "Issue hierarchy is inconsistent");
    }
    if (issue.isArchived()) {
      throw new ProjectValidationException(
          "issue", "Cannot publish evidence for an archived issue");
    }

    EvidenceBlobPort.ReadyUpload ready = blobPort.lockReadyUpload(uploadId);
    UUID blobId = ready.blobId();
    IssueEvidence existing = issueEvidenceRepository.get(issueId, blobId);
    if (existing != null) {
      // 证据行已存在：重复交付不双计，也不再补写人为事实。
      blobPort.deleteUpload(uploadId);
      return existing;
    }

    String validatedName = ProjectValidationUtils.requireDisplayName(ready.filename(), "name", 512);

    long version = issue.getVersion();
    blobPort.retainBlob(blobId);
    IssueEvidence evidence =
        IssueEvidence.builder()
            .issueId(issueId)
            .blobId(blobId)
            .actorAgentName(null)
            .runId(null)
            .name(validatedName)
            .build();
    boolean inserted = issueEvidenceRepository.insert(evidence);
    if (!inserted) {
      blobPort.releaseBlob(blobId);
      return issueEvidenceRepository.get(issueId, blobId);
    }
    appendPublicationActivity(issue, blobId, validatedName);
    if (!issueRepository.updateById(issue, version)) {
      throw new IllegalStateException("failed to advance issue activity cursor");
    }
    blobPort.deleteUpload(uploadId);
    return issueEvidenceRepository.get(issueId, blobId);
  }

  /**
   * 人工公开附件是人为事实：在发布同一事务内向该 Issue 的有序时间线追加一条 HUMAN COMMENT。
   *
   * <p>身份由证据 {@code (issue_id, blob_id)} 唯一性决定（一个 Blob 只发布一次，也只留痕一次）。执行者发表的证据由来源 Run 的结果与证据行本身溯源，
   * 不重复制造人为事实。
   */
  private void appendPublicationActivity(Issue issue, UUID blobId, String name) {
    Identity identity =
        IssueActivityIdempotency.identity(
            IssueActivityKind.COMMENT, "EVIDENCE_PUBLISHED", "evidence:" + blobId, blobId, name);
    long sequence = issue.getNextActivitySequence();
    IssueActivity activity =
        IssueActivity.builder()
            .issueId(issue.getId())
            .sequence(sequence)
            .kind(identity.kind())
            .actorType(IssueActivityActorType.HUMAN)
            .body("Published issue evidence " + RESOURCE_URI_PREFIX + blobId + " (" + name + ")")
            .data("{}")
            .idempotencyKey(identity.key())
            .requestHash(identity.requestHash())
            .build();
    if (!issueActivityRepository.insert(activity)) {
      throw new IllegalStateException("failed to insert issue activity");
    }
    issue.setNextActivitySequence(sequence + 1);
  }

  @Override
  @Transactional
  public IssueEvidence publishBlob(
      UUID issueId, String actorAgentName, UUID runId, UUID blobId, String name) {
    Objects.requireNonNull(issueId, "issueId");
    if (blobId == null) {
      throw new ProjectValidationException("blobId", "blobId must not be null");
    }
    String validatedName = ProjectValidationUtils.requireDisplayName(name, "name", 512);

    Issue issue = issueRepository.getById(issueId);
    if (issue == null) {
      throw new ProjectNotFoundException("issue");
    }
    if (issue.isArchived()) {
      throw new ProjectValidationException(
          "issue", "Cannot publish evidence for an archived issue");
    }

    if (runId != null) {
      if (actorAgentName == null || actorAgentName.isBlank()) {
        throw new ProjectValidationException(
            "actorAgentName", "actorAgentName must not be blank when runId is provided");
      }
      ProjectValidationUtils.requireCanonicalName(actorAgentName, "actorAgentName");
      IssueRun run = issueRunRepository.getById(runId);
      if (run == null) {
        throw new ProjectNotFoundException("run");
      }
      if (!issueId.equals(run.getIssueId())) {
        throw new ProjectValidationException("runId", "run does not belong to the target issue");
      }
    } else if (actorAgentName != null) {
      ProjectValidationUtils.requireCanonicalName(actorAgentName, "actorAgentName");
    }

    IssueEvidence existing = issueEvidenceRepository.get(issueId, blobId);
    if (existing != null) {
      return existing;
    }

    blobPort.retainBlob(blobId);
    IssueEvidence evidence =
        IssueEvidence.builder()
            .issueId(issueId)
            .blobId(blobId)
            .actorAgentName(actorAgentName)
            .runId(runId)
            .name(validatedName)
            .build();
    boolean inserted = issueEvidenceRepository.insert(evidence);
    if (!inserted) {
      blobPort.releaseBlob(blobId);
    }
    return issueEvidenceRepository.get(issueId, blobId);
  }

  @Override
  public List<IssueEvidence> listEvidence(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    return issueEvidenceRepository.listByIssueId(issueId).stream()
        .limit(MAX_EVIDENCE_LIMIT)
        .toList();
  }

  @Override
  @Transactional
  public int releaseAll(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    List<IssueEvidence> evidences = issueEvidenceRepository.listByIssueId(issueId);
    for (IssueEvidence evidence : evidences) {
      blobPort.releaseBlob(evidence.getBlobId());
    }
    return issueEvidenceRepository.deleteByIssueId(issueId);
  }
}
