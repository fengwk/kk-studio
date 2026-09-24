package fun.fengwk.kkstudio.platform.project.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.project.model.IssueEvidence;
import fun.fengwk.kkstudio.platform.project.model.IssueEvidenceOrigin;
import fun.fengwk.kkstudio.platform.project.repo.IssueEvidenceRepository;
import fun.fengwk.kkstudio.platform.project.repo.impl.mapper.IssueEvidenceMapper;
import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueEvidenceDO;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@AllArgsConstructor
@Repository
public class PostgresqlIssueEvidenceRepository implements IssueEvidenceRepository {

  private final IssueEvidenceMapper issueEvidenceMapper;

  @Override
  public boolean insertIfAbsent(IssueEvidence evidence) {
    return issueEvidenceMapper.insertIfAbsent(toDO(evidence)) > 0;
  }

  @Override
  public IssueEvidence findByIssueIdAndBlobId(UUID issueId, UUID blobId) {
    return toModel(issueEvidenceMapper.findByIssueIdAndBlobId(issueId, blobId));
  }

  @Override
  public List<IssueEvidence> listRecentByIssueId(UUID issueId, int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be positive");
    }
    return issueEvidenceMapper.listRecentByIssueId(issueId, limit).stream()
        .map(this::toModel)
        .collect(Collectors.toList());
  }

  @Override
  public List<UUID> listBlobIdsByIssueId(UUID issueId) {
    return issueEvidenceMapper.listBlobIdsByIssueId(issueId);
  }

  @Override
  public int deleteByIssueId(UUID issueId) {
    return issueEvidenceMapper.deleteByIssueId(issueId);
  }

  private IssueEvidenceDO toDO(IssueEvidence evidence) {
    if (evidence == null) {
      return null;
    }
    IssueEvidenceDO target = new IssueEvidenceDO();
    target.setIssueId(evidence.getIssueId());
    target.setBlobId(evidence.getBlobId());
    target.setOrigin(evidence.getOrigin() != null ? evidence.getOrigin().name() : null);
    target.setRunId(evidence.getRunId());
    target.setName(evidence.getName());
    target.setCreatedAt(evidence.getCreatedAt());
    return target;
  }

  private IssueEvidence toModel(IssueEvidenceDO row) {
    if (row == null) {
      return null;
    }
    return IssueEvidence.builder()
        .issueId(row.getIssueId())
        .blobId(row.getBlobId())
        .origin(row.getOrigin() != null ? IssueEvidenceOrigin.valueOf(row.getOrigin()) : null)
        .runId(row.getRunId())
        .name(row.getName())
        .createdAt(row.getCreatedAt())
        .build();
  }
}
