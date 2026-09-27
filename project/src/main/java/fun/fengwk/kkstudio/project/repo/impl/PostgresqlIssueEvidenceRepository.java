package fun.fengwk.kkstudio.project.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.project.model.IssueEvidence;
import fun.fengwk.kkstudio.project.repo.IssueEvidenceRepository;
import fun.fengwk.kkstudio.project.repo.impl.mapper.IssueEvidenceMapper;
import fun.fengwk.kkstudio.project.repo.impl.model.IssueEvidenceDO;

import java.util.List;
import java.util.UUID;

/** 基于 PostgreSQL 的 {@link IssueEvidenceRepository} 实现。 */
@AllArgsConstructor
@Repository
public class PostgresqlIssueEvidenceRepository implements IssueEvidenceRepository {

  private final IssueEvidenceMapper mapper;

  @Override
  public boolean insert(IssueEvidence evidence) {
    return mapper.insert(toDO(evidence)) == 1;
  }

  @Override
  public IssueEvidence get(UUID issueId, UUID blobId) {
    IssueEvidenceDO row = mapper.get(issueId, blobId);
    return row == null ? null : toModel(row);
  }

  @Override
  public List<IssueEvidence> listByIssueId(UUID issueId) {
    return mapper.listByIssueId(issueId).stream()
        .map(PostgresqlIssueEvidenceRepository::toModel)
        .toList();
  }

  @Override
  public int deleteByIssueId(UUID issueId) {
    return mapper.deleteByIssueId(issueId);
  }

  private static IssueEvidenceDO toDO(IssueEvidence evidence) {
    IssueEvidenceDO row = new IssueEvidenceDO();
    row.setIssueId(evidence.getIssueId());
    row.setBlobId(evidence.getBlobId());
    row.setActorAgentName(evidence.getActorAgentName());
    row.setRunId(evidence.getRunId());
    row.setName(evidence.getName());
    row.setCreatedAt(evidence.getCreatedAt());
    return row;
  }

  private static IssueEvidence toModel(IssueEvidenceDO row) {
    return IssueEvidence.builder()
        .issueId(row.getIssueId())
        .blobId(row.getBlobId())
        .actorAgentName(row.getActorAgentName())
        .runId(row.getRunId())
        .name(row.getName())
        .createdAt(row.getCreatedAt())
        .build();
  }
}
