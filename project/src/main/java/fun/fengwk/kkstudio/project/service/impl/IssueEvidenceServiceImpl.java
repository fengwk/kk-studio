package fun.fengwk.kkstudio.project.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.project.error.ProjectNotFoundException;
import fun.fengwk.kkstudio.project.error.ProjectValidationException;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueEvidence;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.port.EvidenceBlobPort;
import fun.fengwk.kkstudio.project.repo.IssueEvidenceRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.service.IssueEvidenceService;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** {@link IssueEvidenceService} 契约实现。 */
@AllArgsConstructor
@Service
public class IssueEvidenceServiceImpl implements IssueEvidenceService {

  private final IssueEvidenceRepository issueEvidenceRepository;
  private final IssueRepository issueRepository;
  private final IssueRunRepository issueRunRepository;

  private final EvidenceBlobPort blobPort;

  @Override
  @Transactional
  public IssueEvidence publishHumanUpload(UUID issueId, UUID uploadId) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(uploadId, "uploadId");

    Issue issue = issueRepository.getById(issueId);
    if (issue == null) {
      throw new ProjectNotFoundException("issue");
    }
    if (issue.isArchived()) {
      throw new ProjectValidationException(
          "issue", "Cannot publish evidence for an archived issue");
    }

    EvidenceBlobPort.ReadyUpload ready = blobPort.lockReadyUpload(uploadId);
    UUID blobId = ready.blobId();
    IssueEvidence existing = issueEvidenceRepository.get(issueId, blobId);
    if (existing != null) {
      blobPort.deleteUpload(uploadId);
      return existing;
    }

    String validatedName = ProjectValidationUtils.requireDisplayName(ready.filename(), "name", 512);

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
    }
    blobPort.deleteUpload(uploadId);
    return issueEvidenceRepository.get(issueId, blobId);
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
