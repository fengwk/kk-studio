package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Test-only in-memory reference implementation of {@link HarnessStore}，供 Processor / 契约测试复用。
 *
 * <p>{@link #transaction} 由单个全局 monitor 串行化：进入事务时对所有 map 与 nextId 做 shallow copy-on-write 快照（持久记录均为
 * immutable records，shallow map copy 即可隔离），回调成功提交快照， 抛出 {@link RuntimeException} 或 {@link Error}
 * 时丢弃快照（完整 rollback，包括 nextId）。事务句柄在 回调结束后关闭，任何后续调用抛 {@link IllegalStateException}；同一 Store
 * 的重入事务被拒绝。
 *
 * <p>模拟必要 schema 约束：Session / Entry / Thread / Command / Invocation id 唯一；Entry 写入前用完整 {@link
 * EntryPath} 校验（每 Session 一个 ROOT 且 ROOT 先于其他 Entry、parent 连续且同 Session、createdAt 顺序与 turn /
 * tool-prefix 结构）；Thread head Entry 存在（insert 与 update）；Command 只能以 QUEUED 插入，所属 thread 存在且 保持
 * {@code (thread, sequence)} 与 {@code (thread, clientCommandId)} 唯一，生命周期只能 QUEUED-&gt;APPLIED /
 * CANCELLED 或 terminal exact-idempotent，consumedTurnStartEntryId 必须指向与 thread 当前 head 同 session 的
 * TURN_START Entry；新 ModelInvocation 只能以 READY / attempt=0 插入并保持 {@code (thread, turnStartEntryId)}
 * 唯一，basisHeadEntryId 必须等于 thread 当前 head（创建时 basis CAS），turnStartEntryId 必须位于 basis 的 EntryPath
 * 上，resultEntryId 全局唯一、限定为 Assistant / AssistantError / AssistantAborted Entry 且其 path 同时包含 basis 与
 * turnStart（同 branch descendant）；新 ToolInvocation 只能以 READY / attempt=0 / approval=null 插入并保持
 * {@code (assistantEntryId, ordinal)} 唯一，其 modelInvocation 的 resultEntryId 必须 等于
 * assistantEntryId，request.call 与 Assistant 中按 ordinal 提取的 ToolCall 精确一致，resultEntryId 全局 唯一、限定为非
 * synthetic 且 status 精确映射 invocation terminal status 的匹配 ToolResult MESSAGE Entry 且其 path 包含
 * assistantEntryId（同 branch）；Work target 必须存在且保持 {@code (targetType, targetId)} 主键。
 *
 * <p>per-transaction lock tracking：updateThread / updateCommands / updateModelInvocation /
 * updateToolInvocations 要求对应行已在本事务锁定，且 Thread / Model / Tool 更新必须通过 aggregate 共享 transition
 * validation （非法状态机跳跃与 terminal 回退/改写被拒绝）；lock* 方法、loadQueuedCommands 与
 * lockToolInvocationsByAssistantEntryId 产生锁；insert* 之后本事务内可直接更新，insertModelInvocation 额外要求 Thread
 * 已在本事务锁定（basis CAS 原子），loadQueuedCommands / insertCommands / updateCommands 也要求相关 Thread
 * 已在本事务锁定（锁序 Thread -&gt; commands）；Work 的 renew / complete / reschedule 方法内部先锁定目标行，
 * lockClaimedWork 在 ownership 校验通过后锁行；Thread 同层按 id 升序，Tool siblings 按 ordinal 升序，Work 同层按 (type,
 * id) 升序；requestWork 与存在行的 deleteWork 要求 owning Thread 已锁定。返回对象与 list 均为 immutable records /
 * copies。
 */
public final class InMemoryHarnessStore implements HarnessStore {

  private static final Comparator<ThreadCommand> COMMAND_LOCK_ORDER =
      Comparator.comparingLong(ThreadCommand::threadId)
          .thenComparingLong(ThreadCommand::sequence)
          .thenComparingLong(ThreadCommand::id);
  private static final Comparator<ToolInvocation> TOOL_LOCK_ORDER =
      Comparator.comparingLong(ToolInvocation::assistantEntryId)
          .thenComparingInt(ToolInvocation::ordinal)
          .thenComparingLong(ToolInvocation::id);
  private static final Comparator<WorkTarget> WORK_LOCK_ORDER =
      Comparator.comparingInt((WorkTarget target) -> target.type().ordinal())
          .thenComparingLong(WorkTarget::id);

  private final Object monitor = new Object();
  private State committed;
  private boolean inTransaction;

  public InMemoryHarnessStore() {
    this.committed = new State();
  }

  /**
   * Test-only constructor that seeds the global id sequence; the next allocated id is {@code
   * initialNextId + 1}.
   */
  InMemoryHarnessStore(long initialNextId) {
    if (initialNextId < 0) {
      throw new IllegalArgumentException("initialNextId must not be negative");
    }
    this.committed = new State();
    this.committed.nextId = initialNextId;
  }

  private static void requireMillisecondPrecision(Instant instant) {
    HarnessStoreTime.requireMillisecondPrecision(instant);
  }

  @Override
  public <T> T transaction(Function<Transaction, T> callback) {
    Objects.requireNonNull(callback, "callback");
    synchronized (monitor) {
      if (inTransaction) {
        throw new IllegalStateException("nested transactions are not supported");
      }
      inTransaction = true;
      try {
        InMemoryTransaction tx = new InMemoryTransaction(State.copyOf(committed));
        try {
          T result = callback.apply(tx);
          tx.close();
          committed = tx.state;
          return result;
        } catch (RuntimeException | Error error) {
          tx.close();
          throw error;
        }
      } finally {
        inTransaction = false;
      }
    }
  }

  /** Committed working state; every transaction works on a shallow copy. */
  private static final class State {
    final Map<Long, Session> sessions = new HashMap<>();
    final Map<Long, Entry> entries = new HashMap<>();
    final Map<Long, ThreadState> threads = new HashMap<>();
    final Map<Long, ThreadCommand> commands = new HashMap<>();
    final Map<Long, ModelInvocation> modelInvocations = new HashMap<>();
    final Map<Long, ToolInvocation> toolInvocations = new HashMap<>();
    final Map<WorkTarget, Work> works = new HashMap<>();
    long nextId;

    static State copyOf(State source) {
      State copy = new State();
      copy.sessions.putAll(source.sessions);
      copy.entries.putAll(source.entries);
      copy.threads.putAll(source.threads);
      copy.commands.putAll(source.commands);
      copy.modelInvocations.putAll(source.modelInvocations);
      copy.toolInvocations.putAll(source.toolInvocations);
      copy.works.putAll(source.works);
      copy.nextId = source.nextId;
      return copy;
    }
  }

  /** Row lock key used for per-transaction update tracking. */
  private enum LockRank {
    THREAD,
    COMMAND,
    MODEL,
    TOOL,
    WORK
  }

  private record LockKey(LockRank rank, String kind, long id) {
    static LockKey thread(long id) {
      return new LockKey(LockRank.THREAD, "thread", id);
    }

    static LockKey command(long id) {
      return new LockKey(LockRank.COMMAND, "command", id);
    }

    static LockKey model(long id) {
      return new LockKey(LockRank.MODEL, "model", id);
    }

    static LockKey tool(long id) {
      return new LockKey(LockRank.TOOL, "tool", id);
    }

    static LockKey work(WorkTarget target) {
      return new LockKey(LockRank.WORK, "work:" + target.type(), target.id());
    }
  }

  private record CommandSequenceKey(long threadId, long sequence) {}

  private record CommandClientKey(long threadId, String clientCommandId) {}

  private record ToolOrdinalKey(long assistantEntryId, int ordinal) {}

  private final class InMemoryTransaction implements Transaction {

    private final State state;
    private final Set<LockKey> locked = new HashSet<>();
    private final Map<Long, Integer> highestToolOrdinalByAssistant = new HashMap<>();
    private final Thread owner = Thread.currentThread();
    private LockRank highestLockRank;
    private Long highestThreadId;
    private WorkTarget highestWorkTarget;
    private boolean closed;

    InMemoryTransaction(State state) {
      this.state = state;
    }

    void close() {
      closed = true;
    }

    private void checkOpen() {
      if (Thread.currentThread() != owner) {
        throw new IllegalStateException("transaction handle may only be used by its owner thread");
      }
      if (closed) {
        throw new IllegalStateException("transaction is closed");
      }
    }

    private void requireCanLockRank(LockRank rank) {
      if (highestLockRank != null && rank.ordinal() < highestLockRank.ordinal()) {
        throw new IllegalStateException(
            "lock order violation: cannot acquire " + rank + " after " + highestLockRank);
      }
    }

    private void requireCanLockThread(long threadId) {
      LockKey key = LockKey.thread(threadId);
      if (locked.contains(key)) {
        return;
      }
      requireCanLock(key);
      if (highestThreadId != null && threadId <= highestThreadId) {
        throw new IllegalStateException(
            "thread locks must be acquired by ascending id: "
                + highestThreadId
                + " before "
                + threadId);
      }
    }

    private void recordThreadLock(long threadId) {
      requireCanLockThread(threadId);
      LockKey key = LockKey.thread(threadId);
      if (!locked.contains(key)) {
        lock(key);
        highestThreadId = threadId;
      }
    }

    private void requireCanLock(LockKey key) {
      if (!locked.contains(key)) {
        requireCanLockRank(key.rank());
      }
    }

    private void lock(LockKey key) {
      requireCanLock(key);
      if (locked.add(key)) {
        highestLockRank = key.rank();
      }
    }

    private void requireCanLockTools(List<ToolInvocation> invocations) {
      Map<Long, Integer> ordinals = new HashMap<>(highestToolOrdinalByAssistant);
      for (ToolInvocation invocation : invocations) {
        LockKey key = LockKey.tool(invocation.id());
        if (locked.contains(key)) {
          continue;
        }
        requireCanLock(key);
        Integer previous = ordinals.put(invocation.assistantEntryId(), invocation.ordinal());
        if (previous != null && invocation.ordinal() <= previous) {
          throw new IllegalStateException(
              "tool invocation locks for assistant entry "
                  + invocation.assistantEntryId()
                  + " must be acquired by ascending ordinal");
        }
      }
    }

    private void lockTool(ToolInvocation invocation) {
      requireCanLockTools(List.of(invocation));
      LockKey key = LockKey.tool(invocation.id());
      if (!locked.contains(key)) {
        lock(key);
        highestToolOrdinalByAssistant.put(invocation.assistantEntryId(), invocation.ordinal());
      }
    }

    private void requireCanLockWork(WorkTarget target) {
      LockKey key = LockKey.work(target);
      if (locked.contains(key)) {
        return;
      }
      requireCanLock(key);
      if (highestWorkTarget != null && WORK_LOCK_ORDER.compare(target, highestWorkTarget) <= 0) {
        throw new IllegalStateException(
            "work locks must be acquired by ascending (type, id): "
                + highestWorkTarget
                + " before "
                + target);
      }
    }

    private void requireCanClaimWork() {
      requireCanLockRank(LockRank.WORK);
      if (highestWorkTarget != null) {
        throw new IllegalStateException(
            "claimNextWork must be the first Work lock acquisition in a transaction");
      }
    }

    private void recordWorkLock(WorkTarget target) {
      requireCanLockWork(target);
      LockKey key = LockKey.work(target);
      if (!locked.contains(key)) {
        lock(key);
        highestWorkTarget = target;
      }
    }

    private void requireLocked(LockKey key) {
      if (!locked.contains(key)) {
        throw new IllegalStateException(key + " is not locked in this transaction");
      }
    }

    private void requireAbsent(Map<Long, ?> rows, long id, String kind) {
      if (rows.containsKey(id)) {
        throw new IllegalArgumentException("duplicate " + kind + " id " + id);
      }
    }

    private Entry requireExistingEntry(long entryId) {
      Entry entry = state.entries.get(entryId);
      if (entry == null) {
        throw new IllegalArgumentException("entry " + entryId + " does not exist");
      }
      return entry;
    }

    @Override
    public long nextId() {
      checkOpen();
      state.nextId = Math.addExact(state.nextId, 1L);
      return state.nextId;
    }

    @Override
    public void insertSession(Session session) {
      checkOpen();
      Objects.requireNonNull(session, "session");
      requireMillisecondPrecision(session.createdAt());
      requireAbsent(state.sessions, session.id(), "session");
      state.sessions.put(session.id(), session);
    }

    @Override
    public Optional<Session> findSession(long id) {
      checkOpen();
      return Optional.ofNullable(state.sessions.get(id));
    }

    @Override
    public void insertEntry(Entry entry) {
      checkOpen();
      Objects.requireNonNull(entry, "entry");
      requireMillisecondPrecision(entry.createdAt());
      requireAbsent(state.entries, entry.id(), "entry");
      if (!state.sessions.containsKey(entry.sessionId())) {
        throw new IllegalArgumentException("session " + entry.sessionId() + " does not exist");
      }
      boolean root = entry.payload().type().isRoot();
      if (root) {
        if (hasRoot(entry.sessionId())) {
          throw new IllegalArgumentException(
              "session " + entry.sessionId() + " already has a ROOT entry");
        }
        new EntryPath(List.of(entry));
      } else {
        if (!hasRoot(entry.sessionId())) {
          throw new IllegalArgumentException(
              "session " + entry.sessionId() + " must have a ROOT entry before any other entry");
        }
        // 写入前校验 parent path + new entry 的完整 EntryPath（turn / tool-prefix 结构不能以非法序列进入 store）。
        EntryPath parentPath = loadEntryPath(entry.parentEntryId());
        List<Entry> nextPath = new ArrayList<>(parentPath.entries());
        nextPath.add(entry);
        new EntryPath(nextPath);
      }
      state.entries.put(entry.id(), entry);
    }

    private boolean hasRoot(long sessionId) {
      for (Entry entry : state.entries.values()) {
        if (entry.sessionId() == sessionId && entry.payload().type().isRoot()) {
          return true;
        }
      }
      return false;
    }

    @Override
    public Optional<Entry> findEntry(long id) {
      checkOpen();
      return Optional.ofNullable(state.entries.get(id));
    }

    @Override
    public EntryPath loadEntryPath(long headEntryId) {
      checkOpen();
      List<Entry> path = new ArrayList<>();
      Long cursor = headEntryId;
      while (cursor != null) {
        Entry entry = requireExistingEntry(cursor);
        path.add(entry);
        cursor = entry.payload().type().isRoot() ? null : entry.parentEntryId();
      }
      Collections.reverse(path);
      return new EntryPath(path);
    }

    @Override
    public void insertThread(ThreadState thread) {
      checkOpen();
      Objects.requireNonNull(thread, "thread");
      requireMillisecondPrecision(thread.createdAt());
      requireMillisecondPrecision(thread.updatedAt());
      requireAbsent(state.threads, thread.id(), "thread");
      requireExistingEntry(thread.headEntryId());
      requireCanLockThread(thread.id());
      state.threads.put(thread.id(), thread);
      recordThreadLock(thread.id());
    }

    @Override
    public Optional<ThreadState> findThread(long id) {
      checkOpen();
      return Optional.ofNullable(state.threads.get(id));
    }

    @Override
    public Optional<ThreadState> lockThread(long id) {
      checkOpen();
      requireCanLockThread(id);
      ThreadState thread = state.threads.get(id);
      if (thread != null) {
        recordThreadLock(id);
      }
      return Optional.ofNullable(thread);
    }

    @Override
    public void updateThread(ThreadState thread) {
      checkOpen();
      Objects.requireNonNull(thread, "thread");
      requireMillisecondPrecision(thread.createdAt());
      requireMillisecondPrecision(thread.updatedAt());
      requireLocked(LockKey.thread(thread.id()));
      ThreadState stored = state.threads.get(thread.id());
      if (stored == null) {
        throw new IllegalArgumentException("thread " + thread.id() + " does not exist");
      }
      ThreadState.validateTransition(stored, thread);
      requireExistingEntry(thread.headEntryId());
      state.threads.put(thread.id(), thread);
    }

    @Override
    public Optional<ThreadCommand> findCommandByClientId(long threadId, String clientCommandId) {
      checkOpen();
      Objects.requireNonNull(clientCommandId, "clientCommandId");
      for (ThreadCommand command : state.commands.values()) {
        if (command.threadId() == threadId && command.clientCommandId().equals(clientCommandId)) {
          return Optional.of(command);
        }
      }
      return Optional.empty();
    }

    @Override
    public List<ThreadCommand> loadQueuedCommands(long threadId) {
      checkOpen();
      // 锁序 Thread -> commands：先锁 Thread 才能读它的 mailbox。
      requireLocked(LockKey.thread(threadId));
      requireCanLockRank(LockRank.COMMAND);
      List<ThreadCommand> queued =
          state.commands.values().stream()
              .filter(
                  command ->
                      command.threadId() == threadId
                          && command.state() == ThreadCommandState.QUEUED)
              .sorted(Comparator.comparingLong(ThreadCommand::sequence))
              .toList();
      for (ThreadCommand command : queued) {
        lock(LockKey.command(command.id()));
      }
      return queued;
    }

    @Override
    public void insertCommands(List<ThreadCommand> commands) {
      checkOpen();
      List<ThreadCommand> copied =
          List.copyOf(commands).stream().sorted(COMMAND_LOCK_ORDER).toList();
      Set<Long> ids = new HashSet<>();
      Set<CommandSequenceKey> sequences = new HashSet<>();
      Set<CommandClientKey> clientIds = new HashSet<>();
      for (ThreadCommand command : copied) {
        requireMillisecondPrecision(command.cancelledAt());
        requireMillisecondPrecision(command.createdAt());
        if (!ids.add(command.id())) {
          throw new IllegalArgumentException("duplicate command id " + command.id());
        }
        if (!sequences.add(new CommandSequenceKey(command.threadId(), command.sequence()))) {
          throw new IllegalArgumentException(
              "duplicate command sequence "
                  + command.sequence()
                  + " on thread "
                  + command.threadId());
        }
        if (!clientIds.add(new CommandClientKey(command.threadId(), command.clientCommandId()))) {
          throw new IllegalArgumentException(
              "duplicate clientCommandId "
                  + command.clientCommandId()
                  + " on thread "
                  + command.threadId());
        }
        requireAbsent(state.commands, command.id(), "command");
        if (command.state() != ThreadCommandState.QUEUED) {
          throw new IllegalArgumentException("inserted commands must be QUEUED");
        }
        if (!state.threads.containsKey(command.threadId())) {
          throw new IllegalArgumentException("thread " + command.threadId() + " does not exist");
        }
        // 锁序 Thread -> commands：enqueue 必须先锁定目标 Thread。
        requireLocked(LockKey.thread(command.threadId()));
        requireUniqueCommandKey(command);
      }
      if (!copied.isEmpty()) {
        requireCanLockRank(LockRank.COMMAND);
      }
      for (ThreadCommand command : copied) {
        state.commands.put(command.id(), command);
        lock(LockKey.command(command.id()));
      }
    }

    private void requireUniqueCommandKey(ThreadCommand command) {
      for (ThreadCommand existing : state.commands.values()) {
        if (existing.threadId() == command.threadId()
            && existing.sequence() == command.sequence()) {
          throw new IllegalArgumentException(
              "command sequence "
                  + command.sequence()
                  + " already used on thread "
                  + command.threadId());
        }
        if (existing.threadId() == command.threadId()
            && existing.clientCommandId().equals(command.clientCommandId())) {
          throw new IllegalArgumentException(
              "clientCommandId "
                  + command.clientCommandId()
                  + " already used on thread "
                  + command.threadId());
        }
      }
    }

    @Override
    public void updateCommands(List<ThreadCommand> commands) {
      checkOpen();
      List<ThreadCommand> copied =
          List.copyOf(commands).stream().sorted(COMMAND_LOCK_ORDER).toList();
      Set<Long> ids = new HashSet<>();
      for (ThreadCommand command : copied) {
        requireMillisecondPrecision(command.cancelledAt());
        requireMillisecondPrecision(command.createdAt());
        if (!ids.add(command.id())) {
          throw new IllegalArgumentException("duplicate command id " + command.id());
        }
        requireLocked(LockKey.command(command.id()));
        ThreadCommand stored = state.commands.get(command.id());
        if (stored == null) {
          throw new IllegalArgumentException("command " + command.id() + " does not exist");
        }
        requireSameCommandIdentity(stored, command);
        // 锁序 Thread -> commands：harvest / stop 更新 mailbox 前必须先锁定相关 Thread；身份检查先行，
        // 伪造身份的 update 仍以 IllegalArgumentException 拒绝。
        requireLocked(LockKey.thread(command.threadId()));
        requireValidCommandLifecycle(stored, command);
        requireValidConsumedTurnStart(command);
      }
      for (ThreadCommand command : copied) {
        state.commands.put(command.id(), command);
      }
    }

    /**
     * consumedTurnStartEntryId（若有）必须指向 TURN_START Entry，且该 Entry 的 path session 与 Thread 当前 head
     * 一致。
     */
    private void requireValidConsumedTurnStart(ThreadCommand command) {
      Long consumedTurnStartEntryId = command.consumedTurnStartEntryId();
      if (consumedTurnStartEntryId == null) {
        return;
      }
      Entry turnStart = requireExistingEntry(consumedTurnStartEntryId);
      if (turnStart.payload().type() != EntryType.TURN_START) {
        throw new IllegalArgumentException(
            "consumedTurnStartEntryId must reference a TURN_START entry");
      }
      long turnStartSessionId = loadEntryPath(consumedTurnStartEntryId).root().sessionId();
      long threadSessionId =
          loadEntryPath(state.threads.get(command.threadId()).headEntryId()).root().sessionId();
      if (turnStartSessionId != threadSessionId) {
        throw new IllegalArgumentException(
            "consumed turn start must be in the command thread's current head session");
      }
    }

    /**
     * QUEUED 只能推进为 APPLIED / CANCELLED；terminal 行只接受 exact-idempotent 重放（相同 marker），禁止
     * terminal-&gt;QUEUED、APPLIED&lt;-&gt;CANCELLED 或 terminal marker 改变。
     */
    private static void requireValidCommandLifecycle(ThreadCommand stored, ThreadCommand command) {
      if (stored.consumedTurnStartEntryId() != null || stored.cancelledAt() != null) {
        if (!Objects.equals(stored.consumedTurnStartEntryId(), command.consumedTurnStartEntryId())
            || !Objects.equals(stored.cancelledAt(), command.cancelledAt())) {
          throw new IllegalArgumentException(
              "terminal commands must be updated exactly idempotently");
        }
        return;
      }
      boolean consumed = command.consumedTurnStartEntryId() != null;
      boolean cancelled = command.cancelledAt() != null;
      if (consumed == cancelled) {
        // both absent: QUEUED -> QUEUED; both present is already rejected by the record contract.
        throw new IllegalArgumentException(
            "queued commands may only transition to APPLIED or CANCELLED");
      }
    }

    private static void requireSameCommandIdentity(ThreadCommand stored, ThreadCommand command) {
      if (stored.threadId() != command.threadId()
          || !stored.payload().equals(command.payload())
          || !stored.clientCommandId().equals(command.clientCommandId())
          || stored.sequence() != command.sequence()
          || !stored.createdAt().equals(command.createdAt())) {
        throw new IllegalArgumentException(
            "command identity (thread/payload/clientCommandId/sequence/createdAt) must not change");
      }
    }

    @Override
    public Optional<ModelInvocation> findModelInvocation(long id) {
      checkOpen();
      return Optional.ofNullable(state.modelInvocations.get(id));
    }

    @Override
    public Optional<ModelInvocation> lockModelInvocation(long id) {
      checkOpen();
      LockKey lockKey = LockKey.model(id);
      requireCanLock(lockKey);
      ModelInvocation invocation = state.modelInvocations.get(id);
      if (invocation != null) {
        lock(lockKey);
      }
      return Optional.ofNullable(invocation);
    }

    @Override
    public Optional<ModelInvocation> findModelInvocationByTurn(
        long threadId, long turnStartEntryId) {
      checkOpen();
      for (ModelInvocation invocation : state.modelInvocations.values()) {
        if (invocation.threadId() == threadId
            && invocation.turnStartEntryId() == turnStartEntryId) {
          return Optional.of(invocation);
        }
      }
      return Optional.empty();
    }

    @Override
    public void insertModelInvocation(ModelInvocation invocation) {
      checkOpen();
      Objects.requireNonNull(invocation, "invocation");
      requireMillisecondPrecision(invocation.createdAt());
      requireMillisecondPrecision(invocation.updatedAt());
      requireAbsent(state.modelInvocations, invocation.id(), "model invocation");
      requireUniqueModelTurn(invocation);
      if (!state.threads.containsKey(invocation.threadId())) {
        throw new IllegalArgumentException("thread " + invocation.threadId() + " does not exist");
      }
      // basis CAS 只有在 Thread 已在本事务锁定时才原子成立。
      requireLocked(LockKey.thread(invocation.threadId()));
      Entry turnStart = requireExistingEntry(invocation.turnStartEntryId());
      if (turnStart.payload().type() != EntryType.TURN_START) {
        throw new IllegalArgumentException("turnStartEntryId must reference a TURN_START entry");
      }
      // 新 durable invocation 初始状态不变量：READY / attempt=0 / 无 terminal facts（record 配合保证后者）。
      if (invocation.status() != ModelInvocationStatus.READY || invocation.attempt() != 0) {
        throw new IllegalArgumentException("new model invocations must be READY with attempt 0");
      }
      // 创建时精确 basis CAS：basis 必须等于 Thread 当前 head。
      ThreadState thread = state.threads.get(invocation.threadId());
      if (thread.headEntryId() != invocation.basisHeadEntryId()) {
        throw new IllegalArgumentException(
            "basisHeadEntryId must equal the current thread head entry");
      }
      requireValidModelBranch(invocation);
      requireValidModelResultEntry(invocation);
      LockKey lockKey = LockKey.model(invocation.id());
      requireCanLock(lockKey);
      state.modelInvocations.put(invocation.id(), invocation);
      lock(lockKey);
    }

    /**
     * turnStartEntryId 必须位于 basisHeadEntryId 的 EntryPath 上，且该 path 的 session 必须与 Thread 当前 head 的
     * session 一致。仅 insert 使用：创建时的 basis CAS 依赖 Thread 当前 head，relocation 后不应重复校验。
     */
    private void requireValidModelBranch(ModelInvocation invocation) {
      EntryPath basisPath = loadEntryPath(invocation.basisHeadEntryId());
      long basisSessionId = basisPath.root().sessionId();
      boolean turnStartOnBasisPath =
          basisPath.entries().stream()
              .anyMatch(entry -> entry.id() == invocation.turnStartEntryId());
      if (!turnStartOnBasisPath) {
        throw new IllegalArgumentException(
            "turnStartEntryId must be on the basisHeadEntryId entry path");
      }
      ThreadState thread = state.threads.get(invocation.threadId());
      long threadSessionId = loadEntryPath(thread.headEntryId()).root().sessionId();
      if (threadSessionId != basisSessionId) {
        throw new IllegalArgumentException("thread head session must match the basis path session");
      }
    }

    private void requireUniqueModelTurn(ModelInvocation invocation) {
      for (ModelInvocation existing : state.modelInvocations.values()) {
        if (existing.threadId() == invocation.threadId()
            && existing.turnStartEntryId() == invocation.turnStartEntryId()) {
          throw new IllegalArgumentException(
              "model invocation already exists for thread "
                  + invocation.threadId()
                  + " turn "
                  + invocation.turnStartEntryId());
        }
      }
    }

    private void requireValidModelResultEntry(ModelInvocation invocation) {
      Long resultEntryId = invocation.resultEntryId();
      if (resultEntryId == null) {
        return;
      }
      Entry result = requireExistingEntry(resultEntryId);
      if (!isModelResultEntry(result)) {
        throw new IllegalArgumentException(
            "model resultEntryId must reference an assistant, assistant-error or"
                + " assistant-aborted entry");
      }
      // result path 必须同时包含 basis 与 turnStart（同 branch descendant），不依赖 Thread 当前 head。
      EntryPath resultPath = loadEntryPath(resultEntryId);
      boolean onBasisPath =
          resultPath.entries().stream()
              .anyMatch(entry -> entry.id() == invocation.basisHeadEntryId());
      boolean sameTurnStart =
          resultPath.entries().stream()
              .anyMatch(entry -> entry.id() == invocation.turnStartEntryId());
      if (!onBasisPath || !sameTurnStart) {
        throw new IllegalArgumentException(
            "model result entry must be on the basis path and in the same turn");
      }
      for (ModelInvocation other : state.modelInvocations.values()) {
        if (other.id() != invocation.id() && Objects.equals(other.resultEntryId(), resultEntryId)) {
          throw new IllegalArgumentException(
              "model resultEntryId " + resultEntryId + " is already used");
        }
      }
    }

    private static boolean isModelResultEntry(Entry entry) {
      return switch (entry.payload().type()) {
        case ASSISTANT_ERROR, ASSISTANT_ABORTED -> true;
        case MESSAGE -> entry.payload() instanceof MessagePayload message
            && message.message().role() == AgentMessageRole.ASSISTANT;
        default -> false;
      };
    }

    @Override
    public void updateModelInvocation(ModelInvocation invocation) {
      checkOpen();
      Objects.requireNonNull(invocation, "invocation");
      requireMillisecondPrecision(invocation.createdAt());
      requireMillisecondPrecision(invocation.updatedAt());
      requireLocked(LockKey.model(invocation.id()));
      ModelInvocation stored = state.modelInvocations.get(invocation.id());
      if (stored == null) {
        throw new IllegalArgumentException(
            "model invocation " + invocation.id() + " does not exist");
      }
      ModelInvocation.validateTransition(stored, invocation);
      // update 不重复依赖 Thread 当前 head / session（relocation 后 terminal exact replay 仍合法）；
      // 仅当 resultEntry 出现时校验类型、path 与唯一性。
      requireValidModelResultEntry(invocation);
      state.modelInvocations.put(invocation.id(), invocation);
    }

    @Override
    public Optional<ToolInvocation> findToolInvocation(long id) {
      checkOpen();
      return Optional.ofNullable(state.toolInvocations.get(id));
    }

    @Override
    public Optional<ToolInvocation> lockToolInvocation(long id) {
      checkOpen();
      ToolInvocation invocation = state.toolInvocations.get(id);
      if (invocation != null) {
        lockTool(invocation);
      }
      return Optional.ofNullable(invocation);
    }

    @Override
    public List<ToolInvocation> loadToolInvocationsByAssistantEntryId(long assistantEntryId) {
      checkOpen();
      return state.toolInvocations.values().stream()
          .filter(invocation -> invocation.assistantEntryId() == assistantEntryId)
          .sorted(Comparator.comparingInt(ToolInvocation::ordinal))
          .toList();
    }

    @Override
    public List<ToolInvocation> lockToolInvocationsByAssistantEntryId(long assistantEntryId) {
      checkOpen();
      requireCanLockRank(LockRank.TOOL);
      List<ToolInvocation> invocations = loadToolInvocationsByAssistantEntryId(assistantEntryId);
      requireCanLockTools(invocations);
      for (ToolInvocation invocation : invocations) {
        lockTool(invocation);
      }
      return invocations;
    }

    @Override
    public void insertToolInvocations(List<ToolInvocation> invocations) {
      checkOpen();
      List<ToolInvocation> copied =
          List.copyOf(invocations).stream().sorted(TOOL_LOCK_ORDER).toList();
      Set<Long> ids = new HashSet<>();
      Set<ToolOrdinalKey> ordinals = new HashSet<>();
      for (ToolInvocation invocation : copied) {
        requireMillisecondPrecision(invocation.createdAt());
        requireMillisecondPrecision(invocation.updatedAt());
        if (!ids.add(invocation.id())) {
          throw new IllegalArgumentException("duplicate tool invocation id " + invocation.id());
        }
        if (!ordinals.add(
            new ToolOrdinalKey(invocation.assistantEntryId(), invocation.ordinal()))) {
          throw new IllegalArgumentException(
              "duplicate tool invocation ordinal "
                  + invocation.ordinal()
                  + " on assistant entry "
                  + invocation.assistantEntryId());
        }
        requireAbsent(state.toolInvocations, invocation.id(), "tool invocation");
        // 新 durable invocation 初始状态不变量：READY / attempt=0 / approval=null / 无 terminal facts（record
        // 配合）。
        if (invocation.status() != ToolInvocationStatus.READY
            || invocation.attempt() != 0
            || invocation.approval() != null) {
          throw new IllegalArgumentException(
              "new tool invocations must be READY with attempt 0 and no approval");
        }
        requireUniqueToolOrdinal(invocation);
        requireValidToolReferences(invocation);
      }
      requireCanLockTools(copied);
      for (ToolInvocation invocation : copied) {
        state.toolInvocations.put(invocation.id(), invocation);
        lockTool(invocation);
      }
    }

    private void requireUniqueToolOrdinal(ToolInvocation invocation) {
      for (ToolInvocation existing : state.toolInvocations.values()) {
        if (existing.assistantEntryId() == invocation.assistantEntryId()
            && existing.ordinal() == invocation.ordinal()) {
          throw new IllegalArgumentException(
              "tool invocation ordinal "
                  + invocation.ordinal()
                  + " already used on assistant entry "
                  + invocation.assistantEntryId());
        }
      }
    }

    private void requireValidToolReferences(ToolInvocation invocation) {
      ModelInvocation model = state.modelInvocations.get(invocation.modelInvocationId());
      if (model == null) {
        throw new IllegalArgumentException(
            "model invocation " + invocation.modelInvocationId() + " does not exist");
      }
      if (model.resultEntryId() == null || model.resultEntryId() != invocation.assistantEntryId()) {
        throw new IllegalArgumentException(
            "model invocation resultEntryId must equal the tool assistantEntryId");
      }
      Entry assistant = requireExistingEntry(invocation.assistantEntryId());
      if (!isAssistantEntry(assistant)) {
        throw new IllegalArgumentException(
            "assistantEntryId must reference an assistant MESSAGE entry");
      }
      requireMatchingAssistantToolCall(invocation, assistant);
      requireValidToolResultEntry(invocation);
    }

    /**
     * request.call 必须与 Assistant MESSAGE 中按 ordinal 提取的 ToolCall（id / toolName /
     * argumentsJson）精确一致。
     */
    private static void requireMatchingAssistantToolCall(
        ToolInvocation invocation, Entry assistant) {
      MessagePayload message = (MessagePayload) assistant.payload();
      List<ToolCallMessageContent> calls = new ArrayList<>();
      for (AgentMessageContent content : message.message().contents()) {
        if (content instanceof ToolCallMessageContent call) {
          calls.add(call);
        }
      }
      if (invocation.ordinal() >= calls.size()) {
        throw new IllegalArgumentException(
            "tool ordinal " + invocation.ordinal() + " exceeds the assistant tool calls");
      }
      ToolCallMessageContent call = calls.get(invocation.ordinal());
      ToolCall requestCall = invocation.request().call();
      if (!call.toolCallId().equals(requestCall.id())
          || !call.toolName().equals(requestCall.toolName())
          || !call.argumentsJson().equals(requestCall.argumentsJson())) {
        throw new IllegalArgumentException(
            "tool request call must exactly match the assistant tool call at the same ordinal");
      }
    }

    private static boolean isAssistantEntry(Entry entry) {
      return entry.payload().type() == EntryType.MESSAGE
          && entry.payload() instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.ASSISTANT;
    }

    private void requireValidToolResultEntry(ToolInvocation invocation) {
      Long resultEntryId = invocation.resultEntryId();
      if (resultEntryId == null) {
        return;
      }
      Entry result = requireExistingEntry(resultEntryId);
      if (!isToolResultEntry(result)) {
        throw new IllegalArgumentException(
            "tool resultEntryId must reference a TOOL MESSAGE entry");
      }
      ToolResultMetadata metadata = ((MessagePayload) result.payload()).toolResultMetadata();
      if (metadata.assistantEntryId() != invocation.assistantEntryId()
          || metadata.ordinal() != invocation.ordinal()
          || !metadata.toolCallId().equals(invocation.request().call().id())) {
        throw new IllegalArgumentException(
            "tool result entry must match the invocation assistant entry, ordinal and"
                + " toolCallId");
      }
      // synthetic 结果（history normalization 补写）不关联真实 ToolInvocation。
      if (metadata.synthetic()) {
        throw new IllegalArgumentException("tool result entry must not be synthetic");
      }
      // metadata.status 必须精确映射 invocation 的 terminal status（非 terminal 不可能携带 resultEntryId）。
      ToolResultStatus expectedStatus =
          switch (invocation.status()) {
            case SUCCEEDED -> ToolResultStatus.SUCCEEDED;
            case FAILED -> ToolResultStatus.FAILED;
            case CANCELLED -> ToolResultStatus.CANCELLED;
            case UNKNOWN -> ToolResultStatus.UNKNOWN;
            default -> throw new IllegalStateException(
                "resultEntryId requires a terminal tool invocation status");
          };
      if (metadata.status() != expectedStatus) {
        throw new IllegalArgumentException(
            "tool result status must exactly map the invocation status");
      }
      // result 必须是 assistant 同 branch 的 descendant（其 path 包含 assistantEntryId）。
      boolean assistantOnResultPath =
          loadEntryPath(resultEntryId).entries().stream()
              .anyMatch(entry -> entry.id() == invocation.assistantEntryId());
      if (!assistantOnResultPath) {
        throw new IllegalArgumentException(
            "tool result entry must be in the same branch as the assistant entry");
      }
      for (ToolInvocation other : state.toolInvocations.values()) {
        if (other.id() != invocation.id() && Objects.equals(other.resultEntryId(), resultEntryId)) {
          throw new IllegalArgumentException(
              "tool resultEntryId " + resultEntryId + " is already used");
        }
      }
    }

    private static boolean isToolResultEntry(Entry entry) {
      return entry.payload().type() == EntryType.MESSAGE
          && entry.payload() instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.TOOL;
    }

    @Override
    public void updateToolInvocations(List<ToolInvocation> invocations) {
      checkOpen();
      List<ToolInvocation> copied =
          List.copyOf(invocations).stream().sorted(TOOL_LOCK_ORDER).toList();
      Set<Long> ids = new HashSet<>();
      Set<Long> resultEntryIds = new HashSet<>();
      for (ToolInvocation invocation : copied) {
        requireMillisecondPrecision(invocation.createdAt());
        requireMillisecondPrecision(invocation.updatedAt());
        if (!ids.add(invocation.id())) {
          throw new IllegalArgumentException("duplicate tool invocation id " + invocation.id());
        }
        if (invocation.resultEntryId() != null && !resultEntryIds.add(invocation.resultEntryId())) {
          throw new IllegalArgumentException(
              "duplicate tool resultEntryId " + invocation.resultEntryId());
        }
        requireLocked(LockKey.tool(invocation.id()));
        ToolInvocation stored = state.toolInvocations.get(invocation.id());
        if (stored == null) {
          throw new IllegalArgumentException(
              "tool invocation " + invocation.id() + " does not exist");
        }
        ToolInvocation.validateTransition(stored, invocation);
        requireValidToolResultEntry(invocation);
      }
      for (ToolInvocation invocation : copied) {
        state.toolInvocations.put(invocation.id(), invocation);
      }
    }

    @Override
    public Optional<Work> findWork(WorkTarget target) {
      checkOpen();
      Objects.requireNonNull(target, "target");
      return Optional.ofNullable(state.works.get(target));
    }

    @Override
    public Optional<Work> lockWork(WorkTarget target) {
      checkOpen();
      Objects.requireNonNull(target, "target");
      requireCanLockWork(target);
      Work work = state.works.get(target);
      if (work != null) {
        recordWorkLock(target);
      }
      return Optional.ofNullable(work);
    }

    @Override
    public Optional<Work> lockClaimedWork(ClaimedWork claim, Instant now) {
      checkOpen();
      Objects.requireNonNull(claim, "claim");
      Objects.requireNonNull(now, "now");
      requireMillisecondPrecision(now);
      requireCanLockWork(claim.target());
      Work work = state.works.get(claim.target());
      if (work == null
          || !claim.leaseToken().equals(work.leaseToken())
          || work.leaseUntil() == null
          || !work.leaseUntil().isAfter(now)) {
        // 行缺失 / token 不匹配 / lease 已过期都是正常竞态：lost / stale ownership，不以异常表达。
        return Optional.empty();
      }
      recordWorkLock(claim.target());
      return Optional.of(work);
    }

    @Override
    public boolean deleteWork(WorkTarget target) {
      checkOpen();
      Objects.requireNonNull(target, "target");
      if (!state.works.containsKey(target)) {
        return false;
      }
      requireWorkOwnerLocked(target);
      requireCanLockWork(target);
      state.works.remove(target);
      recordWorkLock(target);
      return true;
    }

    @Override
    public void requestWork(WorkTarget target, Instant requestedAt) {
      checkOpen();
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(requestedAt, "requestedAt");
      requireMillisecondPrecision(requestedAt);
      requireWorkOwnerLocked(target);
      requireCanLockWork(target);
      Work existing = state.works.get(target);
      Work next =
          existing == null ? Work.initial(target, requestedAt) : existing.request(requestedAt);
      state.works.put(target, next);
      recordWorkLock(target);
    }

    private void requireWorkOwnerLocked(WorkTarget target) {
      long threadId =
          switch (target.type()) {
            case THREAD -> {
              if (!state.threads.containsKey(target.id())) {
                throw new IllegalArgumentException("work target does not exist: " + target);
              }
              yield target.id();
            }
            case MODEL -> {
              ModelInvocation model = state.modelInvocations.get(target.id());
              if (model == null) {
                throw new IllegalArgumentException("work target does not exist: " + target);
              }
              yield model.threadId();
            }
            case TOOL -> {
              ToolInvocation tool = state.toolInvocations.get(target.id());
              if (tool == null) {
                throw new IllegalArgumentException("work target does not exist: " + target);
              }
              ModelInvocation model = state.modelInvocations.get(tool.modelInvocationId());
              if (model == null) {
                throw new IllegalArgumentException(
                    "model invocation "
                        + tool.modelInvocationId()
                        + " does not exist for work target "
                        + target);
              }
              yield model.threadId();
            }
          };
      requireLocked(LockKey.thread(threadId));
    }

    private void requireTargetExists(WorkTarget target) {
      boolean exists =
          switch (target.type()) {
            case THREAD -> state.threads.containsKey(target.id());
            case MODEL -> state.modelInvocations.containsKey(target.id());
            case TOOL -> state.toolInvocations.containsKey(target.id());
          };
      if (!exists) {
        throw new IllegalArgumentException("work target does not exist: " + target);
      }
    }

    @Override
    public Optional<ClaimedWork> claimNextWork(
        WorkTargetType targetType, Instant now, String leaseToken, Instant leaseUntil) {
      checkOpen();
      Objects.requireNonNull(targetType, "targetType");
      Objects.requireNonNull(now, "now");
      Objects.requireNonNull(leaseToken, "leaseToken");
      Objects.requireNonNull(leaseUntil, "leaseUntil");
      requireMillisecondPrecision(now);
      requireMillisecondPrecision(leaseUntil);
      requireCanClaimWork();
      List<Work> due =
          state.works.values().stream()
              .filter(work -> work.target().type() == targetType)
              .filter(work -> !work.availableAt().isAfter(now))
              .filter(work -> work.leaseToken() == null || !work.leaseUntil().isAfter(now))
              .sorted(
                  Comparator.comparing(Work::availableAt)
                      .thenComparingLong(work -> work.target().id()))
              .toList();
      if (due.isEmpty()) {
        return Optional.empty();
      }
      Work candidate = due.get(0);
      requireTargetExists(candidate.target());
      Work claimed = candidate.claim(now, leaseToken, leaseUntil);
      requireCanLockWork(claimed.target());
      state.works.put(claimed.target(), claimed);
      recordWorkLock(claimed.target());
      return Optional.of(
          new ClaimedWork(
              claimed.target(), claimed.wakeVersion(), claimed.leaseToken(), claimed.leaseUntil()));
    }

    @Override
    public void renewWork(ClaimedWork claim, Instant now, Instant newLeaseUntil) {
      checkOpen();
      Objects.requireNonNull(claim, "claim");
      Objects.requireNonNull(now, "now");
      Objects.requireNonNull(newLeaseUntil, "newLeaseUntil");
      requireMillisecondPrecision(now);
      requireMillisecondPrecision(newLeaseUntil);
      Work work = lockedWork(claim.target());
      Work renewed = work.renew(claim.leaseToken(), now, newLeaseUntil);
      state.works.put(renewed.target(), renewed);
    }

    @Override
    public Optional<Work> completeWork(ClaimedWork claim, Instant now) {
      checkOpen();
      Objects.requireNonNull(claim, "claim");
      Objects.requireNonNull(now, "now");
      requireMillisecondPrecision(now);
      Work work = state.works.get(claim.target());
      if (work == null) {
        // 行不存在意味着没有可验证的 lease：completed 的 ownership 已丢失，不能幂等吞掉终态。
        throw new IllegalStateException(
            "work does not exist for target " + claim.target() + " (lost ownership)");
      }
      requireCanLockWork(claim.target());
      recordWorkLock(claim.target());
      Optional<Work> next = work.complete(claim.leaseToken(), claim.claimedWakeVersion(), now);
      if (next.isEmpty()) {
        state.works.remove(claim.target());
      } else {
        state.works.put(claim.target(), next.get());
      }
      return next;
    }

    @Override
    public void rescheduleWork(ClaimedWork claim, Instant now, Instant requestedAt) {
      checkOpen();
      Objects.requireNonNull(claim, "claim");
      Objects.requireNonNull(now, "now");
      Objects.requireNonNull(requestedAt, "requestedAt");
      requireMillisecondPrecision(now);
      requireMillisecondPrecision(requestedAt);
      Work work = lockedWork(claim.target());
      Work next = work.reschedule(claim.leaseToken(), claim.claimedWakeVersion(), now, requestedAt);
      state.works.put(next.target(), next);
    }

    private Work lockedWork(WorkTarget target) {
      requireCanLockWork(target);
      recordWorkLock(target);
      Work work = state.works.get(target);
      if (work == null) {
        throw new IllegalStateException("work does not exist for target " + target);
      }
      return work;
    }
  }
}
