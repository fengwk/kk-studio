package fun.fengwk.kkstudio.harness.infra.postgresql;

import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessTransaction.LockKey;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.store.ThreadStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Thread 聚合的 PostgreSQL 实现。 */
final class PostgresqlThreadStore implements ThreadStore {

  private final PostgresqlHarnessTransaction tx;

  PostgresqlThreadStore(PostgresqlHarnessTransaction tx) {
    this.tx = tx;
  }

  @Override
  public void insert(ThreadState thread) {
    tx.checkOpen();
    Objects.requireNonNull(thread, "thread");
    requireThreadHeadInSession(thread);
    boolean joinedChild =
        thread.parentThreadId() != null
            && tx.hasLockedTrees()
            && tx.isLocked(LockKey.thread(thread.parentThreadId()));
    if (!joinedChild) {
      tx.requireCanLockThread(thread.id());
    }
    try {
      tx.updateJdbc(
          """
          insert into harness_thread (
              id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name,
              root_thread_id, yolo_enabled, spawn_trigger, constraints, execution_control,
              input_through_sequence, next_command_sequence, version, created_at, updated_at
          ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?)
          """,
          thread.id(),
          thread.sessionId(),
          thread.parentThreadId(),
          thread.headEntryId(),
          thread.creationRequestHash(),
          thread.name(),
          thread.rootThreadId(),
          thread.yoloEnabled(),
          thread.spawnTrigger().name(),
          PostgresqlHarnessRows.encodeConstraints(thread.constraints()),
          thread.executionControl().name(),
          thread.inputThroughSequence(),
          thread.nextCommandSequence(),
          thread.version(),
          PostgresqlHarnessRows.timestamp(thread.createdAt()),
          PostgresqlHarnessRows.timestamp(thread.updatedAt()));
    } catch (IllegalArgumentException error) {
      throw tx.translateThreadNameConflict(thread, error);
    }
    // 插入即视为 version 变化：同一位置发布 Thread version 与真实执行根 tree 两条失效信号。
    tx.notifyThreadVersion(thread.id(), thread.version());
    tx.notifyExecutionTree(thread.rootThreadId());
    if (joinedChild) {
      // A newly created child has no pre-existing row to lock in UUID order. Its parent is
      // already locked inside the same tree transaction; no other tree writer can race it.
      tx.recordJoinedChildThreadLock(thread.id());
    } else {
      tx.recordThreadLock(thread.id());
    }
  }

  /** Thread 的 head Entry 必须属于 Thread 持久化的 Session（由同一 Session 的复合 FK 强制，这里给出更早的显式检查）。 */
  private void requireThreadHeadInSession(ThreadState thread) {
    if (tx.sessions().find(thread.sessionId()).isEmpty()) {
      throw new IllegalArgumentException("session " + thread.sessionId() + " does not exist");
    }
    Boolean sameSession =
        tx.queryForObject(
            """
            select exists (
                select 1
                from harness_entry
                where id = ? and session_id = ?
            )
            """,
            Boolean.class,
            thread.headEntryId(),
            thread.sessionId());
    if (!Boolean.TRUE.equals(sameSession)) {
      throw new IllegalArgumentException(
          "thread head entry "
              + thread.headEntryId()
              + " must belong to thread session "
              + thread.sessionId());
    }
  }

  @Override
  public Optional<ThreadState> find(UUID id) {
    tx.checkOpen();
    return tx.queryOne(
        "select * from harness_thread where id = ?", PostgresqlHarnessRows.THREAD, id);
  }

  @Override
  public Optional<ThreadState> lock(UUID id) {
    tx.checkOpen();
    tx.requireCanLockThread(id);
    Optional<ThreadState> thread =
        tx.queryOne(
            "select * from harness_thread where id = ? for update",
            PostgresqlHarnessRows.THREAD,
            id);
    thread.ifPresent(ignored -> tx.recordThreadLock(id));
    return thread;
  }

  @Override
  public List<ThreadState> listBySession(UUID sessionId) {
    tx.checkOpen();
    Objects.requireNonNull(sessionId, "sessionId");
    if (tx.sessions().find(sessionId).isEmpty()) {
      throw new IllegalArgumentException("session " + sessionId + " does not exist");
    }
    return tx.queryList(
        """
        select *
        from harness_thread
        where session_id = ?
        order by created_at, id
        """,
        PostgresqlHarnessRows.THREAD,
        sessionId);
  }

  @Override
  public List<ThreadState> listChildren(UUID parentThreadId) {
    tx.checkOpen();
    Objects.requireNonNull(parentThreadId, "parentThreadId");
    return tx.queryList(
        """
        select *
        from harness_thread
        where parent_thread_id = ?
        order by id
        """,
        PostgresqlHarnessRows.THREAD,
        parentThreadId);
  }

  @Override
  public void update(ThreadState thread) {
    tx.checkOpen();
    Objects.requireNonNull(thread, "thread");
    tx.requireLocked(LockKey.thread(thread.id()));
    ThreadState stored =
        tx.threads()
            .find(thread.id())
            .orElseThrow(
                () -> new IllegalArgumentException("thread " + thread.id() + " does not exist"));
    ThreadState.validateTransition(stored, thread);
    requireThreadHeadInSession(thread);
    int updated;
    try {
      updated =
          tx.updateJdbc(
              """
              update harness_thread
              set head_entry_id = ?,
                  name = ?,
                  yolo_enabled = ?,
                  execution_control = ?,
                  input_through_sequence = ?,
                  next_command_sequence = ?,
                  version = ?,
                  updated_at = ?
              where id = ?
              """,
              thread.headEntryId(),
              thread.name(),
              thread.yoloEnabled(),
              thread.executionControl().name(),
              thread.inputThroughSequence(),
              thread.nextCommandSequence(),
              thread.version(),
              PostgresqlHarnessRows.timestamp(thread.updatedAt()),
              thread.id());
    } catch (IllegalArgumentException error) {
      throw tx.translateThreadNameConflict(thread, error);
    }
    tx.requireSingleUpdate(updated, "thread", thread.id());
    if (stored.version() != thread.version()) {
      // 仅以写前事实（stored）与成功写后事实（thread）比较：同 version 写入（exact replay）保持静默。
      tx.notifyThreadVersion(thread.id(), thread.version());
      tx.notifyExecutionTree(thread.rootThreadId());
    }
  }

  @Override
  public List<UUID> findAncestorChain(UUID threadId) {
    tx.checkOpen();
    Objects.requireNonNull(threadId, "threadId");
    return tx.queryList(
        """
        with recursive ancestors as (
            select id, parent_thread_id, 0 as depth
            from harness_thread where id = ?
            union all
            select t.id, t.parent_thread_id, ancestors.depth + 1
            from harness_thread t join ancestors on t.id = ancestors.parent_thread_id
        )
        select id from ancestors order by depth
        """,
        (rs, ignored) -> rs.getObject("id", UUID.class),
        threadId);
  }

  @Override
  public List<ThreadState> listByRoot(UUID rootThreadId) {
    tx.checkOpen();
    Objects.requireNonNull(rootThreadId, "rootThreadId");
    return tx.queryList(
        "select * from harness_thread where root_thread_id = ?",
        PostgresqlHarnessRows.THREAD,
        rootThreadId);
  }

  @Override
  public void lockTree(UUID rootThreadId) {
    tx.checkOpen();
    Objects.requireNonNull(rootThreadId, "rootThreadId");
    tx.lockTree(rootThreadId);
  }

  // ---------- 应用侧深删除原语 ----------
  @Override
  public int delete(List<UUID> threadIds) {
    tx.checkOpen();
    Objects.requireNonNull(threadIds, "threadIds");
    List<UUID> copied = List.copyOf(threadIds).stream().sorted(UuidOrder.COMPARATOR).toList();
    for (int i = 1; i < copied.size(); i++) {
      if (copied.get(i).equals(copied.get(i - 1))) {
        throw new IllegalArgumentException(
            "duplicate thread id " + copied.get(i) + " must not be deleted twice");
      }
    }
    for (UUID threadId : copied) {
      tx.requireLocked(LockKey.thread(threadId));
      if (!tx.isInLockedTree(threadId)) {
        throw new IllegalStateException("thread " + threadId + " is not in a locked tree");
      }
    }
    if (copied.isEmpty()) {
      return 0;
    }

    for (UUID threadId : copied) {
      tx.threads()
          .find(threadId)
          .orElseThrow(
              () -> new IllegalArgumentException("thread " + threadId + " does not exist"));
    }

    for (UUID threadId : copied) {
      List<UUID> referencingJoinIds =
          tx.queryList(
              """
              select invocation_id
              from harness_thread_join
              where child_thread_id = ? or parent_thread_id = ?
              limit 1
              """,
              (rs, rowNum) -> rs.getObject("invocation_id", UUID.class),
              threadId,
              threadId);
      if (!referencingJoinIds.isEmpty()) {
        throw new IllegalArgumentException(
            "cannot delete thread referenced by join " + referencingJoinIds.get(0));
      }
    }

    Set<UUID> threadIdSet = Set.copyOf(copied);
    for (UUID threadId : copied) {
      List<UUID> childIds =
          tx.queryList(
              """
              select id
              from harness_thread
              where parent_thread_id = ?
              """,
              (rs, rowNum) -> rs.getObject("id", UUID.class),
              threadId);
      for (UUID childId : childIds) {
        if (!threadIdSet.contains(childId)) {
          throw new IllegalArgumentException(
              "cannot delete parent thread "
                  + threadId
                  + " while child thread "
                  + childId
                  + " still exists");
        }
      }
    }

    for (UUID threadId : copied) {
      List<ThreadCommand> commands =
          tx.queryList(
              """
              select *
              from harness_thread_command
              where thread_id = ?
              order by sequence
              for update
              """,
              PostgresqlHarnessRows.COMMAND,
              threadId);
      for (ThreadCommand command : commands) {
        tx.lockResource(LockKey.command(command.threadId(), command.sequence()));
      }
    }

    List<ModelInvocation> models = new ArrayList<>();
    for (UUID threadId : copied) {
      models.addAll(
          tx.queryList(
              "select * from harness_model_invocation where thread_id = ?",
              PostgresqlHarnessRows.MODEL_INVOCATION,
              threadId));
    }
    models.sort(Comparator.comparing(ModelInvocation::id, UuidOrder.COMPARATOR));
    List<ModelInvocation> lockedModels = new ArrayList<>(models.size());
    for (ModelInvocation model : models) {
      tx.builtin().lockModelInvocation(model.id()).ifPresent(lockedModels::add);
    }

    List<ToolInvocation> tools = new ArrayList<>();
    for (UUID threadId : copied) {
      tools.addAll(
          tx.queryList(
              "select * from harness_tool_invocation where thread_id = ?",
              PostgresqlHarnessRows.TOOL_INVOCATION,
              threadId));
    }
    tools.sort(PostgresqlHarnessTransaction.TOOL_LOCK_ORDER);
    List<ToolInvocation> lockedTools = new ArrayList<>(tools.size());
    for (ToolInvocation tool : tools) {
      tx.tools().lock(tool.id()).ifPresent(lockedTools::add);
    }

    List<WorkTarget> workTargets = new ArrayList<>();
    for (UUID threadId : copied) {
      workTargets.add(new WorkTarget(WorkTargetType.THREAD, threadId));
    }
    for (ModelInvocation model : lockedModels) {
      workTargets.add(new WorkTarget(WorkTargetType.MODEL, model.id()));
    }
    for (ToolInvocation tool : lockedTools) {
      workTargets.add(new WorkTarget(WorkTargetType.TOOL, tool.id()));
    }
    workTargets.sort(PostgresqlHarnessTransaction.WORK_LOCK_ORDER);
    List<WorkTarget> lockedWorkTargets = new ArrayList<>();
    for (WorkTarget target : workTargets) {
      if (tx.work().lock(target).isPresent()) {
        lockedWorkTargets.add(target);
      }
    }

    // 删除前读取受影响执行根：行一旦删除就无法再解析根身份。
    // 同一执行根只发一次：先按根聚合，再在删除成功后按根定点失效。
    Set<UUID> deletedThreadRoots = new LinkedHashSet<>();
    for (UUID threadId : copied) {
      deletedThreadRoots.add(tx.executionRootOfThread(threadId));
    }

    for (WorkTarget target : lockedWorkTargets) {
      tx.work().delete(target);
    }
    for (UUID threadId : copied) {
      tx.updateJdbc("delete from harness_thread_command where thread_id = ?", threadId);
    }
    // Stop 回执只引用 Thread 与 Entry：与 Thread 同批物理删除，避免深删残留孤儿回执。
    tx.updateJdbc(
        "delete from harness_thread_stop_receipt where thread_id in ("
            + PostgresqlHarnessTransaction.placeholders(copied.size())
            + ")",
        copied.toArray());
    if (!lockedTools.isEmpty()) {
      tx.tools().deleteByIds(lockedTools.stream().map(ToolInvocation::id).toList());
    }
    for (ModelInvocation model : lockedModels) {
      tx.builtin().deleteModelInvocation(model.id());
    }
    // 自引用 parent FK 为 NO ACTION（语句级校验）：同一条 DELETE 可同时包含父与子执行事实。
    // 删除只是 Thread 深删，不是执行退出；先删 execution facts，再删 Thread。
    tx.updateJdbc(
        "delete from harness_execution_exit where thread_id in ("
            + PostgresqlHarnessTransaction.placeholders(copied.size())
            + ")",
        copied.toArray());
    int deletedThreads =
        tx.updateJdbc(
            "delete from harness_thread where id in ("
                + PostgresqlHarnessTransaction.placeholders(copied.size())
                + ")",
            copied.toArray());
    if (deletedThreads != copied.size()) {
      throw new IllegalStateException(
          "expected to delete " + copied.size() + " threads but deleted " + deletedThreads);
    }
    for (UUID root : deletedThreadRoots) {
      tx.notifyExecutionTree(root);
    }
    return copied.size();
  }
}
