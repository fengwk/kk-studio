package fun.fengwk.kkstudio.core.ai.runtime.goal.store;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.ai.runtime.goal.store.mapper.HarnessThreadGoalMapper;
import fun.fengwk.kkstudio.core.ai.runtime.goal.store.model.HarnessThreadGoalDO;
import fun.fengwk.kkstudio.harness.runtime.goal.GoalStatus;
import fun.fengwk.kkstudio.harness.runtime.goal.GoalStore;
import fun.fengwk.kkstudio.harness.runtime.goal.ThreadGoal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

/** 数据库支撑的持久 Thread goal store。 */
@Repository
public class DatabaseGoalStore implements GoalStore {
  private final HarnessThreadGoalMapper mapper;

  public DatabaseGoalStore(HarnessThreadGoalMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ThreadGoal> find(long threadId) {
    requirePositive(threadId);
    return Optional.ofNullable(mapper.find(threadId)).map(DatabaseGoalStore::toDomain);
  }

  @Override
  @Transactional
  public ThreadGoal createOrReplace(
      long threadId, String objective, Long tokenBudget, Instant now) {
    requirePositive(threadId);
    if (objective == null || objective.isBlank()) {
      throw new IllegalArgumentException("objective must not be blank");
    }
    if (tokenBudget != null && tokenBudget <= 0) {
      throw new IllegalArgumentException("tokenBudget must be positive when present");
    }
    Instant timestamp = Objects.requireNonNull(now, "now");
    OffsetDateTime local = OffsetDateTime.ofInstant(timestamp, ZoneOffset.UTC);
    HarnessThreadGoalDO existing = mapper.findForUpdate(threadId);
    HarnessThreadGoalDO row = new HarnessThreadGoalDO();
    row.setThreadId(threadId);
    row.setObjective(objective.trim());
    row.setTokenBudget(tokenBudget);
    row.setStatus(GoalStatus.active.name());
    row.setReason(null);
    row.setUpdateTime(local);
    if (existing == null) {
      row.setCreateTime(local);
      mapper.insert(row);
    } else {
      row.setCreateTime(existing.getCreateTime());
      mapper.update(row);
    }
    return toDomain(mapper.find(threadId));
  }

  @Override
  @Transactional
  public ThreadGoal updateTerminal(long threadId, GoalStatus status, String reason, Instant now) {
    requirePositive(threadId);
    Objects.requireNonNull(status, "status");
    if (!status.terminal()) {
      throw new IllegalArgumentException("status must be complete or blocked");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("reason is required and must not be blank");
    }
    Instant timestamp = Objects.requireNonNull(now, "now");
    HarnessThreadGoalDO existing = mapper.findForUpdate(threadId);
    if (existing == null) {
      throw new IllegalStateException("No goal is set.");
    }
    GoalStatus current = GoalStatus.parseStored(existing.getStatus());
    if (current != GoalStatus.active) {
      throw new IllegalStateException(
          "Goal status is " + current.name() + "; it cannot be updated by the model.");
    }
    int updated =
        mapper.updateTerminal(
            threadId,
            status.name(),
            reason.trim(),
            OffsetDateTime.ofInstant(timestamp, ZoneOffset.UTC));
    if (updated != 1) {
      throw new IllegalStateException(
          "Goal status is " + current.name() + "; it cannot be updated by the model.");
    }
    return toDomain(mapper.find(threadId));
  }

  private static ThreadGoal toDomain(HarnessThreadGoalDO row) {
    Objects.requireNonNull(row, "row");
    return new ThreadGoal(
        row.getThreadId(),
        row.getObjective(),
        row.getTokenBudget(),
        GoalStatus.parseStored(row.getStatus()),
        row.getReason(),
        row.getCreateTime().toInstant(),
        row.getUpdateTime().toInstant());
  }

  private static void requirePositive(long threadId) {
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
  }
}
