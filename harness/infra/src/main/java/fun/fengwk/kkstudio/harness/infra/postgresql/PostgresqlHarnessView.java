package fun.fengwk.kkstudio.harness.infra.postgresql;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.RowMapper;

import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessTransaction.LockKey;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessTransaction.LockRank;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL 聚合视图共享的事务上下文入口；只暴露视图实际消费的 JDBC、锁、缓存与失效能力。 */
abstract class PostgresqlHarnessView {

  final PostgresqlHarnessTransaction tx;

  PostgresqlHarnessView(PostgresqlHarnessTransaction tx) {
    this.tx = Objects.requireNonNull(tx, "tx");
  }

  final void checkOpen() {
    tx.checkOpen();
  }

  final Entry requireExistingEntry(UUID entryId) {
    return tx.requireExistingEntry(entryId);
  }

  final TurnStartPayload requireTurnStartPayload(UUID entryId) {
    return tx.requireTurnStartPayload(entryId);
  }

  final boolean queryExists(String sql, UUID id) {
    return tx.queryExists(sql, id);
  }

  final Instant databaseNow() {
    return tx.databaseNow();
  }

  final <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... arguments) {
    return tx.queryOne(sql, mapper, arguments);
  }

  final <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... arguments) {
    return tx.queryList(sql, mapper, arguments);
  }

  final <T> T queryForObject(String sql, Class<T> requiredType, Object... arguments) {
    return tx.queryForObject(sql, requiredType, arguments);
  }

  final int updateJdbc(String sql, Object... arguments) {
    return tx.updateJdbc(sql, arguments);
  }

  final <T> Optional<T> writeOne(String sql, RowMapper<T> mapper, Object... arguments) {
    return tx.writeOne(sql, mapper, arguments);
  }

  final <T extends RuntimeException> T remember(T failure) {
    return tx.remember(failure);
  }

  static String placeholders(int count) {
    return PostgresqlHarnessTransaction.placeholders(count);
  }

  static IllegalArgumentException integrityViolation(DataIntegrityViolationException error) {
    return PostgresqlHarnessTransaction.integrityViolation(error);
  }

  final RuntimeException translateThreadNameConflict(ThreadState thread, RuntimeException error) {
    return tx.translateThreadNameConflict(thread, error);
  }

  static void requireSingleUpdate(int updated, String kind, Object id) {
    PostgresqlHarnessTransaction.requireSingleUpdate(updated, kind, id);
  }

  final void requireCanLockRank(LockRank rank) {
    tx.requireCanLockRank(rank);
  }

  final void requireCanLockRank(LockRank rank, UUID commandThreadId) {
    tx.requireCanLockRank(rank, commandThreadId);
  }

  final void requireCanLockThread(UUID threadId) {
    tx.requireCanLockThread(threadId);
  }

  final void recordThreadLock(UUID threadId) {
    tx.recordThreadLock(threadId);
  }

  final void requireCanLock(LockKey key) {
    tx.requireCanLock(key);
  }

  final void lockResource(LockKey key) {
    tx.lockResource(key);
  }

  final void requireCanLockTools(List<ToolInvocation> invocations) {
    tx.requireCanLockTools(invocations);
  }

  final void lockTool(ToolInvocation invocation) {
    tx.lockTool(invocation);
  }

  final void requireCanLockWork(WorkTarget target) {
    tx.requireCanLockWork(target);
  }

  final boolean isInLockedTree(UUID threadId) {
    return tx.isInLockedTree(threadId);
  }

  final void requireCanClaimWork() {
    tx.requireCanClaimWork();
  }

  final void recordWorkLock(WorkTarget target) {
    tx.recordWorkLock(target);
  }

  final void requireLocked(LockKey key) {
    tx.requireLocked(key);
  }

  final Map<UUID, EntryPath> entryPathCache() {
    return tx.entryPathCache();
  }

  final boolean isLocked(LockKey key) {
    return tx.isLocked(key);
  }

  final boolean isLockedTree(UUID rootThreadId) {
    return tx.isLockedTree(rootThreadId);
  }

  final void recordJoinedChildThreadLock(UUID threadId) {
    tx.recordJoinedChildThreadLock(threadId);
  }

  final void acquireTreeLock(UUID rootThreadId) {
    tx.lockTree(rootThreadId);
  }

  final void acquireJoinAdmissionLock() {
    tx.lockJoinAdmission();
  }

  final boolean hasLockedTrees() {
    return tx.hasLockedTrees();
  }

  final void notifyWorkAvailable(WorkTargetType type) {
    tx.notifyWorkAvailable(type);
  }

  final void notifyThreadVersion(UUID threadId, long version) {
    tx.notifyThreadVersion(threadId, version);
  }

  final void notifyExecutionTree(UUID rootThreadId) {
    tx.notifyExecutionTree(rootThreadId);
  }

  final void notifyToolInteraction(UUID rootThreadId) {
    tx.notifyToolInteraction(rootThreadId);
  }

  final void notifyEnvironmentWorkChanged(Work work) {
    tx.notifyEnvironmentWorkChanged(work);
  }
}
