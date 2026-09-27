package fun.fengwk.kkstudio.platform.project.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.project.model.IssueStageBudgetRow;
import fun.fengwk.kkstudio.platform.project.repo.IssueStageBudgetRepository;
import fun.fengwk.kkstudio.platform.project.repo.impl.mapper.IssueStageBudgetMapper;
import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueStageBudgetDO;

import java.util.List;
import java.util.UUID;

@AllArgsConstructor
@Repository
public class PostgresqlIssueStageBudgetRepository implements IssueStageBudgetRepository {

  private final IssueStageBudgetMapper mapper;

  @Override
  public IssueStageBudgetRow get(UUID issueId, String state) {
    return toModel(mapper.get(issueId, state));
  }

  @Override
  public boolean insert(IssueStageBudgetRow budget) {
    return mapper.insert(toDO(budget)) == 1;
  }

  @Override
  public boolean update(IssueStageBudgetRow budget) {
    return mapper.update(toDO(budget)) == 1;
  }

  @Override
  public boolean delete(UUID issueId, String state) {
    return mapper.delete(issueId, state) == 1;
  }

  @Override
  public List<IssueStageBudgetRow> listByIssueId(UUID issueId) {
    return mapper.listByIssueId(issueId).stream()
        .map(PostgresqlIssueStageBudgetRepository::toModel)
        .toList();
  }

  private static IssueStageBudgetDO toDO(IssueStageBudgetRow budget) {
    IssueStageBudgetDO row = new IssueStageBudgetDO();
    row.setIssueId(budget.getIssueId());
    row.setState(budget.getState());
    row.setMaxRuns(budget.getMaxRuns());
    row.setBudgetAfterOrdinal(budget.getBudgetAfterOrdinal());
    return row;
  }

  private static IssueStageBudgetRow toModel(IssueStageBudgetDO row) {
    if (row == null) {
      return null;
    }
    return IssueStageBudgetRow.builder()
        .issueId(row.getIssueId())
        .state(row.getState())
        .maxRuns(row.getMaxRuns() != null ? row.getMaxRuns() : 0)
        .budgetAfterOrdinal(row.getBudgetAfterOrdinal() != null ? row.getBudgetAfterOrdinal() : 0L)
        .build();
  }
}
