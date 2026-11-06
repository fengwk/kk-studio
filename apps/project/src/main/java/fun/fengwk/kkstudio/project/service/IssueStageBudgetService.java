package fun.fengwk.kkstudio.project.service;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.IssueStageBudgetRepository;
import fun.fengwk.kkstudio.project.service.IssueActivityIdempotency.Identity;
import fun.fengwk.kkstudio.project.service.IssueActivityIdempotency.ReceiptLock;

import java.util.Objects;
import java.util.UUID;

/**
 * Issue 阶段预算用例：授权、重置与查询。
 *
 * <p>写路径复用 {@link IssueMutationSupport} 的 owner 锁与幂等 receipt，不在本类或平台层复制额度 推导。
 */
@Service
@AllArgsConstructor
public class IssueStageBudgetService {

  private final IssueStageBudgetRepository stageBudgetRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueMutationSupport mutationSupport;

  /** 首次自动执行接受时授权阶段额度（工作阶段必须是启用且有 Agent 的阶段）。 */
  @Transactional
  public StageBudgetView authorizeStageBudget(
      UUID issueId, long expectedVersion, String requestKey, String state, int maxRuns) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(state, "state");
    String key = ProjectValidationUtils.requireRequestKey(requestKey);
    Identity identity =
        IssueActivityIdempotency.identity(
            IssueActivityKind.CONTROL, "AUTHORIZE", key, state, maxRuns);
    ReceiptLock<IssueMutationSupport.Locked> receipt =
        mutationSupport.lockUnlessReplayed(issueId, identity);
    if (receipt.activity() != null) {
      return getStageBudget(issueId, state);
    }
    IssueMutationSupport.Locked locked = receipt.locked();
    mutationSupport.requireMutable(locked);
    mutationSupport.requireVersion(locked, expectedVersion);
    ProjectStateCode code = ProjectStateCode.of(state);
    StageBudgetView authorized =
        ProjectValidationUtils.resolve(
            "stage_budget", () -> StageBudgetView.authorize(locked.workflow(), code, maxRuns));
    if (stageBudgetRepository.get(issueId, state) != null) {
      throw DomainException.duplicate("stage_budget", "Stage budget is already authorized");
    }
    if (!stageBudgetRepository.insert(issueId, authorized)) {
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
    return getStageBudget(issueId, state);
  }

  /** 重置阶段额度：高水位取服务端当前 {@code nextRunOrdinal - 1}，不得回退而重新授权历史 Run。 */
  @Transactional
  public StageBudgetView resetStageBudget(
      UUID issueId, long expectedVersion, String requestKey, String state, int maxRuns) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(state, "state");
    String key = ProjectValidationUtils.requireRequestKey(requestKey);
    Identity identity =
        IssueActivityIdempotency.identity(IssueActivityKind.CONTROL, "RESET", key, state, maxRuns);
    ReceiptLock<IssueMutationSupport.Locked> receipt =
        mutationSupport.lockUnlessReplayed(issueId, identity);
    if (receipt.activity() != null) {
      return getStageBudget(issueId, state);
    }
    IssueMutationSupport.Locked locked = receipt.locked();
    mutationSupport.requireMutable(locked);
    mutationSupport.requireVersion(locked, expectedVersion);
    if (issueRunRepository.lockActiveByIssueId(issueId) != null) {
      throw DomainException.validation(
          "stage_budget", "Cannot reset stage budget while an active run exists");
    }
    ProjectStateCode code = ProjectStateCode.of(state);
    StageBudgetView existing = stageBudgetRepository.get(issueId, state);
    long previousMax = existing != null ? existing.maxRuns() : 0;
    long previousAfter = existing != null ? existing.budgetAfterOrdinal() : 0;
    StageBudgetView currentBudget =
        existing == null
            ? ProjectValidationUtils.resolve(
                "stage_budget", () -> StageBudgetView.authorize(locked.workflow(), code, maxRuns))
            : StageBudgetView.budget(code, existing.maxRuns(), existing.budgetAfterOrdinal());
    StageBudgetView reset =
        ProjectValidationUtils.resolve(
            "stage_budget",
            () ->
                currentBudget.reset(
                    locked.workflow(), maxRuns, locked.issue().getNextRunOrdinal()));
    boolean applied =
        existing == null
            ? stageBudgetRepository.insert(issueId, reset)
            : stageBudgetRepository.update(issueId, reset);
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
    return getStageBudget(issueId, state);
  }

  public StageBudgetView getStageBudget(UUID issueId, String state) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(state, "state");
    StageBudgetView budget = stageBudgetRepository.get(issueId, state);
    if (budget == null) {
      throw DomainException.notFound("stage_budget");
    }
    return budget;
  }
}
