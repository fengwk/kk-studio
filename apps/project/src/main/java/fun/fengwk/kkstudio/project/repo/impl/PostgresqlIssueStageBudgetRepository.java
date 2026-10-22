package fun.fengwk.kkstudio.project.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.project.model.IssueStageBudgetRow;
import fun.fengwk.kkstudio.project.repo.IssueStageBudgetRepository;
import fun.fengwk.kkstudio.project.repo.impl.model.IssueStageBudgetDO;
import fun.fengwk.kkstudio.project.repo.impl.store.IssueStageBudgetJdbcStore;

import java.util.List;
import java.util.UUID;

@AllArgsConstructor
@Repository
public class PostgresqlIssueStageBudgetRepository implements IssueStageBudgetRepository {

  private final IssueStageBudgetJdbcStore store;
  private final PostgresqlProjectChangeNotifier notifier;

  @Override
  public IssueStageBudgetRow get(UUID issueId, String state) {
    return toModel(store.get(issueId, state));
  }

  @Override
  public boolean insert(IssueStageBudgetRow budget) {
    boolean changed = store.insert(toDO(budget)) == 1;
    if (changed) {
      notifier.issueChanged(budget.getIssueId());
    }
    return changed;
  }

  @Override
  public boolean update(IssueStageBudgetRow budget) {
    boolean changed = store.update(toDO(budget)) == 1;
    if (changed) {
      notifier.issueChanged(budget.getIssueId());
    }
    return changed;
  }

  @Override
  public boolean delete(UUID issueId, String state) {
    boolean changed = store.delete(issueId, state) == 1;
    if (changed) {
      notifier.issueChanged(issueId);
    }
    return changed;
  }

  @Override
  public int deleteByIssueId(UUID issueId) {
    int changed = store.deleteByIssueId(issueId);
    if (changed > 0) {
      notifier.issueChanged(issueId);
    }
    return changed;
  }

  @Override
  public List<IssueStageBudgetRow> listByIssueId(UUID issueId) {
    return store.listByIssueId(issueId).stream()
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
