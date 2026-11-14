package fun.fengwk.kkstudio.platform.harness.store;

import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.spi.driver.DriverThreadDeletion;
import fun.fengwk.kkstudio.harness.spi.store.ThreadStore;
import fun.fengwk.kkstudio.harness.spi.store.UuidOrder;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;
import fun.fengwk.kkstudio.platform.harness.store.PostgresqlHarnessTransaction.LockKey;

import java.util.ArrayList;
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
              root_thread_id, yolo_enabled, kind, constraints, execution_control,
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
          thread.kind().name(),
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
  public List<ThreadState> listUserBySession(UUID sessionId) {
    tx.checkOpen();
    Objects.requireNonNull(sessionId, "sessionId");
    if (tx.sessions().find(sessionId).isEmpty()) {
      throw new IllegalArgumentException("session " + sessionId + " does not exist");
    }
    return tx.queryList(
        """
        select *
        from harness_thread
        where session_id = ? and kind = 'USER'
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

  /**
   * 深删除一组 Thread：先按 UUID 顺序锁定依赖事实，再物理删除依赖行与 Thread 行，最后按执行根发布失效。
   *
   * <p>锁阶梯与删除顺序都是协议的一部分：调用方必须已持有目标 Thread 锁与所在执行树的树锁。
   */
  @Override
  public int delete(List<UUID> threadIds, DriverThreadDeletion driverDeletion) {
    tx.checkOpen();
    Objects.requireNonNull(threadIds, "threadIds");
    List<UUID> targets = requireDeletableThreads(threadIds);
    if (targets.isEmpty()) {
      return 0;
    }
    lockCommands(targets);
    List<WorkTarget> driverTargets = lockDriverFacts(targets, driverDeletion);
    List<ToolInvocation> lockedTools = lockTools(targets);
    List<WorkTarget> lockedWorkTargets = lockWorkTargets(targets, driverTargets, lockedTools);
    // 删除前读取受影响执行根：行一旦删除就无法再解析根身份。
    Set<UUID> executionRoots = executionRoots(targets);
    deleteDependentRows(targets, lockedWorkTargets, lockedTools, driverDeletion, driverTargets);
    deleteThreadRows(targets);
    executionRoots.forEach(tx::notifyExecutionTree);
    return targets.size();
  }

  /** 校验并返回按 UUID 升序的删除目标：去重、树锁、存在性、Join 引用与存活子 Thread 都在锁定依赖事实前拒绝。 */
  private List<UUID> requireDeletableThreads(List<UUID> threadIds) {
    List<UUID> targets = List.copyOf(threadIds).stream().sorted(UuidOrder.COMPARATOR).toList();
    requireNoDuplicateTargets(targets);
    for (UUID threadId : targets) {
      tx.requireLocked(LockKey.thread(threadId));
      if (!tx.isInLockedTree(threadId)) {
        throw new IllegalStateException("thread " + threadId + " is not in a locked tree");
      }
    }
    if (targets.isEmpty()) {
      return targets;
    }
    requireThreadsExist(targets);
    requireNoJoinReferences(targets);
    requireNoSurvivingChildren(targets);
    return targets;
  }

  private static void requireNoDuplicateTargets(List<UUID> targets) {
    for (int i = 1; i < targets.size(); i++) {
      if (targets.get(i).equals(targets.get(i - 1))) {
        throw new IllegalArgumentException(
            "duplicate thread id " + targets.get(i) + " must not be deleted twice");
      }
    }
  }

  private void requireThreadsExist(List<UUID> targets) {
    for (UUID threadId : targets) {
      tx.threads()
          .find(threadId)
          .orElseThrow(
              () -> new IllegalArgumentException("thread " + threadId + " does not exist"));
    }
  }

  /** 仍被 Join 引用的 Thread 不能删除：Join 事实只随子 Thread 的深删除一起消失。 */
  private void requireNoJoinReferences(List<UUID> targets) {
    for (UUID threadId : targets) {
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
  }

  /** 父 Thread 只能与它全部子 Thread 同批删除。 */
  private void requireNoSurvivingChildren(List<UUID> targets) {
    Set<UUID> targetIds = Set.copyOf(targets);
    for (UUID threadId : targets) {
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
        if (!targetIds.contains(childId)) {
          throw new IllegalArgumentException(
              "cannot delete parent thread "
                  + threadId
                  + " while child thread "
                  + childId
                  + " still exists");
        }
      }
    }
  }

  /** 逐 Thread 锁定全部命令行，使命令回写与深删除在同一锁阶梯内互斥。 */
  private void lockCommands(List<UUID> targets) {
    for (UUID threadId : targets) {
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
  }

  /** Driver 私有事实的锁与它返回的 Work 目标由 Driver 自己定义，Harness 只按它给出的顺序处理。 */
  private List<WorkTarget> lockDriverFacts(
      List<UUID> targets, DriverThreadDeletion driverDeletion) {
    Objects.requireNonNull(driverDeletion, "driverDeletion");
    return driverDeletion.lockForDeletion(tx, targets);
  }

  /** 按稳定 Tool 锁序锁定全部 ToolInvocation；锁不到的调用已不存在，不再参与后续删除。 */
  private List<ToolInvocation> lockTools(List<UUID> targets) {
    List<ToolInvocation> tools = new ArrayList<>();
    for (UUID threadId : targets) {
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
    return lockedTools;
  }

  /** 按稳定 Work 锁序锁定受影响的 Work（Thread、Driver 与 Tool 目标）。 */
  private List<WorkTarget> lockWorkTargets(
      List<UUID> targets, List<WorkTarget> driverTargets, List<ToolInvocation> lockedTools) {
    List<WorkTarget> workTargets = new ArrayList<>();
    for (UUID threadId : targets) {
      workTargets.add(new WorkTarget(WorkTargetType.THREAD, threadId));
    }
    workTargets.addAll(driverTargets);
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
    return lockedWorkTargets;
  }

  /** 同一执行根只发一次失效：先按根聚合，再在删除成功后按根定点通知。 */
  private Set<UUID> executionRoots(List<UUID> targets) {
    Set<UUID> executionRoots = new LinkedHashSet<>();
    for (UUID threadId : targets) {
      executionRoots.add(tx.executionRootOfThread(threadId));
    }
    return executionRoots;
  }

  /** 先删依赖事实再删 Thread：Work、命令、停止回执、Tool、Driver 事实与执行退出都不能留下孤儿行。 */
  private void deleteDependentRows(
      List<UUID> targets,
      List<WorkTarget> lockedWorkTargets,
      List<ToolInvocation> lockedTools,
      DriverThreadDeletion driverDeletion,
      List<WorkTarget> driverTargets) {
    for (WorkTarget target : lockedWorkTargets) {
      tx.work().delete(target);
    }
    for (UUID threadId : targets) {
      tx.updateJdbc("delete from harness_thread_command where thread_id = ?", threadId);
    }
    // Stop 回执只引用 Thread 与 Entry：与 Thread 同批物理删除，避免深删残留孤儿回执。
    tx.updateJdbc(
        "delete from harness_thread_stop_receipt where thread_id in ("
            + PostgresqlHarnessTransaction.placeholders(targets.size())
            + ")",
        targets.toArray());
    if (!lockedTools.isEmpty()) {
      tx.tools().deleteByIds(lockedTools.stream().map(ToolInvocation::id).toList());
    }
    driverDeletion.deleteLocked(tx, targets, driverTargets);
    // 自引用 parent FK 为 NO ACTION（语句级校验）：同一条 DELETE 可同时包含父与子执行事实。
    // 删除只是 Thread 深删，不是执行退出；先删 execution facts，再删 Thread。
    tx.updateJdbc(
        "delete from harness_execution_exit where thread_id in ("
            + PostgresqlHarnessTransaction.placeholders(targets.size())
            + ")",
        targets.toArray());
  }

  private void deleteThreadRows(List<UUID> targets) {
    int deletedThreads =
        tx.updateJdbc(
            "delete from harness_thread where id in ("
                + PostgresqlHarnessTransaction.placeholders(targets.size())
                + ")",
            targets.toArray());
    if (deletedThreads != targets.size()) {
      throw new IllegalStateException(
          "expected to delete " + targets.size() + " threads but deleted " + deletedThreads);
    }
  }
}
