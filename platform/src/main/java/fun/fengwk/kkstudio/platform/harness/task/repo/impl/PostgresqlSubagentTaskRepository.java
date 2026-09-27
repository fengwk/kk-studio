package fun.fengwk.kkstudio.platform.harness.task.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskMessages.Outcome;
import fun.fengwk.kkstudio.platform.harness.task.SubagentTask;
import fun.fengwk.kkstudio.platform.harness.task.SubagentTaskDraft;
import fun.fengwk.kkstudio.platform.harness.task.repo.SubagentTaskRepository;
import fun.fengwk.kkstudio.platform.harness.task.repo.impl.mapper.SubagentTaskMapper;
import fun.fengwk.kkstudio.platform.harness.task.repo.impl.model.SubagentTaskDO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@AllArgsConstructor
@Repository
public class PostgresqlSubagentTaskRepository implements SubagentTaskRepository {

  /** advisory 锁 key 命名空间：与其它使用 advisory 锁的组件隔离，避免不同用途的 key 互相阻塞。 */
  private static final String QUOTA_LOCK_NAMESPACE = "kk-studio/harness/subagent-task-quota/";

  private final SubagentTaskMapper mapper;

  @Override
  public boolean insert(SubagentTaskDraft draft) {
    return mapper.insert(toDO(draft)) == 1;
  }

  @Override
  public SubagentTask findByInvocationId(UUID invocationId) {
    return toModel(mapper.findByInvocationId(invocationId));
  }

  @Override
  public List<SubagentTask> listUndeliveredAfter(
      Instant afterCreatedAt, UUID afterInvocationId, int limit) {
    List<SubagentTaskDO> rows =
        mapper.listUndeliveredAfter(afterCreatedAt, afterInvocationId, limit);
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    return rows.stream().map(PostgresqlSubagentTaskRepository::toModel).toList();
  }

  @Override
  public void lockQuota(UUID rootThreadId, UUID parentThreadId) {
    // 固定顺序（根 → 父）：并发接受都以同序申请，不会互相等待；同一 key 重复申请是幂等的。
    mapper.lockQuotaKey(QUOTA_LOCK_NAMESPACE + rootThreadId);
    mapper.lockQuotaKey(QUOTA_LOCK_NAMESPACE + parentThreadId);
  }

  @Override
  public int countOpenByParentThreadId(UUID parentThreadId) {
    return mapper.countOpenByParentThreadId(parentThreadId);
  }

  @Override
  public int countOpenByRootThreadId(UUID rootThreadId) {
    return mapper.countOpenByRootThreadId(rootThreadId);
  }

  @Override
  public boolean hasOpenInSubtree(UUID threadId) {
    return mapper.hasOpenInSubtree(threadId);
  }

  @Override
  public List<UUID> listSettledParentThreadIdsInSubtree(UUID threadId) {
    List<UUID> parents = mapper.listSettledParentThreadIdsInSubtree(threadId);
    return parents == null ? List.of() : List.copyOf(parents);
  }

  @Override
  public boolean hasUndeliveredByChildThreadId(UUID childThreadId) {
    return mapper.hasUndeliveredByChildThreadId(childThreadId);
  }

  @Override
  public boolean settleResult(
      UUID invocationId, Outcome outcome, String report, String partialResult, String error) {
    return mapper.settleResult(invocationId, outcome.name(), report, partialResult, error) == 1;
  }

  @Override
  public boolean markDelivered(UUID invocationId) {
    return mapper.markDelivered(invocationId) == 1;
  }

  @Override
  public boolean updateReminderTurn(
      UUID invocationId, long previousReminderTurn, long reminderTurn) {
    return mapper.updateReminderTurn(invocationId, previousReminderTurn, reminderTurn) == 1;
  }

  private static SubagentTaskDO toDO(SubagentTaskDraft draft) {
    SubagentTaskDO row = new SubagentTaskDO();
    row.setInvocationId(draft.invocationId());
    row.setParentThreadId(draft.parentThreadId());
    row.setRootThreadId(draft.rootThreadId());
    row.setChildSessionId(draft.childSessionId());
    row.setChildThreadId(draft.childThreadId());
    row.setSourceHeadEntryId(draft.sourceHeadEntryId());
    row.setAgent(draft.agent());
    row.setPrompt(draft.prompt());
    row.setMaxTurns(draft.maxTurns());
    row.setStatus(draft.status());
    row.setReminderTurn(draft.reminderTurn());
    return row;
  }

  private static SubagentTask toModel(SubagentTaskDO row) {
    if (row == null) {
      return null;
    }
    return new SubagentTask(
        row.getInvocationId(),
        row.getParentThreadId(),
        row.getRootThreadId(),
        row.getChildSessionId(),
        row.getChildThreadId(),
        row.getSourceHeadEntryId(),
        row.getAgent(),
        row.getPrompt(),
        row.getMaxTurns(),
        row.getStatus(),
        toOutcome(row.getOutcome()),
        row.getReport(),
        row.getPartialResult(),
        row.getError(),
        row.getReminderTurn() == null ? 0L : row.getReminderTurn(),
        row.getSettledAt(),
        row.getCreatedAt(),
        row.getUpdatedAt());
  }

  private static Outcome toOutcome(String outcome) {
    return outcome == null ? null : Outcome.valueOf(outcome);
  }
}
