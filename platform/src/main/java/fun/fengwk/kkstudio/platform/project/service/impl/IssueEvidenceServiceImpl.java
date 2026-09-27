package fun.fengwk.kkstudio.platform.project.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueEvidence;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.repo.IssueEvidenceRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService.ReadyUpload;

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
  private final StorageUploadService uploadService;
  private final StorageBlobManager blobManager;

  @Override
  @Transactional
  public IssueEvidence publishHumanUpload(UUID issueId, UUID uploadId) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(uploadId, "uploadId");

    Issue issue = issueRepository.getById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue");
    }
    if (issue.isArchived()) {
      throw new AiValidationException("issue", "Cannot publish evidence for an archived issue");
    }

    ReadyUpload ready = uploadService.lockReady(uploadId);
    UUID blobId = ready.blobId();
    IssueEvidence existing = issueEvidenceRepository.get(issueId, blobId);
    if (existing != null) {
      uploadService.delete(uploadId);
      return existing;
    }

    String validatedName = ProjectValidationUtils.requireDisplayName(ready.filename(), "name", 512);

    blobManager.retain(blobId);
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
      blobManager.release(blobId);
    }
    uploadService.delete(uploadId);
    return issueEvidenceRepository.get(issueId, blobId);
  }

  @Override
  @Transactional
  public IssueEvidence publishBlob(
      UUID issueId, String actorAgentName, UUID runId, UUID blobId, String name) {
    Objects.requireNonNull(issueId, "issueId");
    if (blobId == null) {
      throw new AiValidationException("blobId", "blobId must not be null");
    }
    String validatedName = ProjectValidationUtils.requireDisplayName(name, "name", 512);

    Issue issue = issueRepository.getById(issueId);
    if (issue == null) {
      throw new AiResourceNotFoundException("issue");
    }
    if (issue.isArchived()) {
      throw new AiValidationException("issue", "Cannot publish evidence for an archived issue");
    }

    if (runId != null) {
      if (actorAgentName == null || actorAgentName.isBlank()) {
        throw new AiValidationException(
            "actorAgentName", "actorAgentName must not be blank when runId is provided");
      }
      ProjectValidationUtils.requireCanonicalName(actorAgentName, "actorAgentName");
      IssueRun run = issueRunRepository.getById(runId);
      if (run == null) {
        throw new AiResourceNotFoundException("run");
      }
      if (!issueId.equals(run.getIssueId())) {
        throw new AiValidationException("runId", "run does not belong to the target issue");
      }
    } else if (actorAgentName != null) {
      ProjectValidationUtils.requireCanonicalName(actorAgentName, "actorAgentName");
    }

    IssueEvidence existing = issueEvidenceRepository.get(issueId, blobId);
    if (existing != null) {
      return existing;
    }

    blobManager.retain(blobId);
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
      blobManager.release(blobId);
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
      blobManager.release(evidence.getBlobId());
    }
    return issueEvidenceRepository.deleteByIssueId(issueId);
  }
}
