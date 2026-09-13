package fun.fengwk.kkstudio.platform.project.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.project.model.IssueInput;
import fun.fengwk.kkstudio.platform.project.model.IssueInputKind;
import fun.fengwk.kkstudio.platform.project.repo.IssueInputRepository;
import fun.fengwk.kkstudio.platform.project.repo.impl.mapper.IssueInputMapper;
import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueInputDO;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@AllArgsConstructor
@Repository
public class PostgresqlIssueInputRepository implements IssueInputRepository {

  private final IssueInputMapper issueInputMapper;

  @Override
  public boolean append(IssueInput input) {
    return issueInputMapper.insert(toDO(input)) == 1;
  }

  @Override
  public IssueInput findByIdempotencyKey(UUID issueId, String idempotencyKey) {
    return toModel(issueInputMapper.findByIdempotencyKey(issueId, idempotencyKey));
  }

  @Override
  public List<IssueInput> listByIssueId(UUID issueId) {
    return issueInputMapper.listByIssueId(issueId).stream()
        .map(this::toModel)
        .collect(Collectors.toList());
  }

  @Override
  public List<IssueInput> listAfterSequence(UUID issueId, long afterSequence) {
    return issueInputMapper.listAfterSequence(issueId, afterSequence).stream()
        .map(this::toModel)
        .collect(Collectors.toList());
  }

  private IssueInputDO toDO(IssueInput input) {
    if (input == null) {
      return null;
    }
    IssueInputDO target = new IssueInputDO();
    target.setIssueId(input.getIssueId());
    target.setSequence(input.getSequence());
    target.setKind(input.getKind() != null ? input.getKind().name() : null);
    target.setBody(input.getBody());
    target.setIdempotencyKey(input.getIdempotencyKey());
    target.setCreatedAt(input.getCreatedAt());
    return target;
  }

  private IssueInput toModel(IssueInputDO row) {
    if (row == null) {
      return null;
    }
    return IssueInput.builder()
        .issueId(row.getIssueId())
        .sequence(row.getSequence() != null ? row.getSequence() : 0L)
        .kind(row.getKind() != null ? IssueInputKind.valueOf(row.getKind()) : null)
        .body(row.getBody())
        .idempotencyKey(row.getIdempotencyKey())
        .createdAt(row.getCreatedAt())
        .build();
  }
}
