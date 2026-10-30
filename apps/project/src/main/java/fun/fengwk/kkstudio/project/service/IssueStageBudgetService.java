package fun.fengwk.kkstudio.project.service;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.project.domain.IssueStageBudget;
import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.error.ProjectDuplicateException;
import fun.fengwk.kkstudio.project.error.ProjectNotFoundException;
import fun.fengwk.kkstudio.project.error.ProjectValidationException;
import fun.fengwk.kkstudio.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.project.model.IssueStageBudgetRow;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.IssueStageBudgetRepository;
import fun.fengwk.kkstudio.project.service.IssueActivityIdempotency.Identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Issue 阶段预算用例：授权、重置与查询。
 *
 * <p>额度规则直接复用领域 {@link IssueStageBudget}；写路径复用 {@link IssueMutationSupport} 的 owner 锁与幂等
 * receipt，不在本类或平台层复制额度 推导。
 */
@Service
@AllArgsConstructor
public class IssueStageBudgetService {

  private final IssueStageBudgetRepository stageBudgetRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueMutationSupport mutationSupport;

  @Transactional
  public StageBudgetView authorizeStageBudget(
      UUID issueId, long expectedVersion, String requestKey, String state, int maxRuns) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(state, "state");
    String key = ProjectValidationUtils.requireRequestKey(requestKey);
    Identity identity =
        IssueActivityIdempotency.identity(
            IssueActivityKind.CONTROL, "AUTHORIZE", key, state, maxRuns);
    if (mutationSupport.replayed(issueId, identity)) {
      return getStageBudget(issueId, state);
    }
    IssueMutationSupport.Locked locked = mutationSupport.lockOwner(issueId);
    if (mutationSupport.replayedUnderLock(locked.issue(), identity)) {
      return getStageBudget(issueId, state);
    }
    mutationSupport.requireMutable(locked, false);
    mutationSupport.requireVersion(locked, expectedVersion);
    ProjectStateCode code = ProjectStateCode.of(state);
    IssueStageBudget authorized =
        ProjectValidationUtils.resolve(
            "stage_budget", () -> IssueStageBudget.authorize(locked.workflow(), code, maxRuns));
    if (stageBudgetRepository.get(issueId, state) != null) {
      throw new ProjectDuplicateException("stage_budget", "Stage budget is already authorized");
    }
    IssueStageBudgetRow budget =
        IssueStageBudgetRow.builder()
            .issueId(issueId)
            .state(state)
            .maxRuns(authorized.maxRuns())
            .budgetAfterOrdinal(authorized.budgetAfterOrdinal())
            .build();
    if (!stageBudgetRepository.insert(budget)) {
      throw new IllegalStateException("failed to insert stage budget");
    }
    ObjectNode data = mutationSupport.newData();
    data.put("action", "AUTHORIZE");
    data.put("state", state);
    data.put("maxRuns", authorized.maxRuns());
    data.put("budgetAfterOrdinal", authorized.budgetAfterOrdinal());
    mutationSupport.appendActivity(
        locked.issue(), identity, IssueActivityActorType.HUMAN, null, null, null, data);
    mutationSupport.persist(locked, expectedVersion);
    return view(budget);
  }

  @Transactional
  public StageBudgetView resetStageBudget(
      UUID issueId, long expectedVersion, String requestKey, String state, int maxRuns) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(state, "state");
    String key = ProjectValidationUtils.requireRequestKey(requestKey);
    Identity identity =
        IssueActivityIdempotency.identity(IssueActivityKind.CONTROL, "RESET", key, state, maxRuns);
    if (mutationSupport.replayed(issueId, identity)) {
      return getStageBudget(issueId, state);
    }
    IssueMutationSupport.Locked locked = mutationSupport.lockOwner(issueId);
    if (mutationSupport.replayedUnderLock(locked.issue(), identity)) {
      return getStageBudget(issueId, state);
    }
    mutationSupport.requireMutable(locked, false);
    mutationSupport.requireVersion(locked, expectedVersion);
    if (issueRunRepository.lockActiveByIssueId(issueId) != null) {
      throw new ProjectValidationException(
          "stage_budget", "Cannot reset stage budget while an active run exists");
    }
    ProjectStateCode code = ProjectStateCode.of(state);
    IssueStageBudgetRow existing = stageBudgetRepository.get(issueId, state);
    long previousMax = existing != null ? existing.getMaxRuns() : 0;
    long previousAfter = existing != null ? existing.getBudgetAfterOrdinal() : 0;
    IssueStageBudget currentBudget =
        existing == null
            ? ProjectValidationUtils.resolve(
                "stage_budget", () -> IssueStageBudget.authorize(locked.workflow(), code, maxRuns))
            : new IssueStageBudget(code, existing.getMaxRuns(), existing.getBudgetAfterOrdinal());
    IssueStageBudget reset =
        ProjectValidationUtils.resolve(
            "stage_budget",
            () ->
                currentBudget.reset(
                    locked.workflow(), maxRuns, locked.issue().getNextRunOrdinal()));
    IssueStageBudgetRow budget =
        IssueStageBudgetRow.builder()
            .issueId(issueId)
            .state(state)
            .maxRuns(reset.maxRuns())
            .budgetAfterOrdinal(reset.budgetAfterOrdinal())
            .build();
    boolean applied =
        existing == null
            ? stageBudgetRepository.insert(budget)
            : stageBudgetRepository.update(budget);
    if (!applied) {
      throw new IllegalStateException("failed to reset stage budget");
    }
    ObjectNode data = mutationSupport.newData();
    data.put("action", "RESET");
    data.put("state", state);
    data.put("previousMaxRuns", previousMax);
    data.put("maxRuns", reset.maxRuns());
    data.put("previousBudgetAfterOrdinal", previousAfter);
    data.put("budgetAfterOrdinal", reset.budgetAfterOrdinal());
    mutationSupport.appendActivity(
        locked.issue(), identity, IssueActivityActorType.HUMAN, null, null, null, data);
    mutationSupport.persist(locked, expectedVersion);
    return view(budget);
  }

  public StageBudgetView getStageBudget(UUID issueId, String state) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(state, "state");
    IssueStageBudgetRow budget = stageBudgetRepository.get(issueId, state);
    if (budget == null) {
      throw new ProjectNotFoundException("stage_budget");
    }
    return view(budget);
  }

  private StageBudgetView view(IssueStageBudgetRow budget) {
    long used =
        issueRunRepository.countByIssueIdAndStateAfterOrdinal(
            budget.getIssueId(), budget.getState(), budget.getBudgetAfterOrdinal());
    long remaining = Math.max(0L, budget.getMaxRuns() - used);
    return new StageBudgetView(
        budget.getState(), budget.getMaxRuns(), budget.getBudgetAfterOrdinal(), used, remaining);
  }
}
