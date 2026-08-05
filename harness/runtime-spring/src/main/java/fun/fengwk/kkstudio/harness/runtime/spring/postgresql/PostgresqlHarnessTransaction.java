package fun.fengwk.kkstudio.harness.runtime.spring.postgresql;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

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
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;

import java.sql.SQLException;
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

final class PostgresqlHarnessTransaction implements HarnessStore.Transaction {

  private static final String SEQUENCE_NAME = "harness_runtime_id_seq";

  private static final String CLAIM_NEXT_WORK =
      """
      with candidate as (
          select target_type, target_id
          from harness_work
          where target_type = ?
            and available_at <= ?
            and (lease_until is null or lease_until <= ?)
          order by available_at, target_id
          for update skip locked
          limit 1
      )
      update harness_work work
      set lease_token = ?, lease_until = ?
      from candidate
      where work.target_type = candidate.target_type
        and work.target_id = candidate.target_id
      returning work.*
      """;

  private static final String REQUEST_WORK =
      """
      insert into harness_work (
          target_type, target_id, available_at, wake_version, lease_token, lease_until
      ) values (?, ?, ?, 1, null, null)
      on conflict (target_type, target_id) do update
      set available_at = least(harness_work.available_at, excluded.available_at),
          wake_version = harness_work.wake_version + 1
      returning *
      """;

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

  private final JdbcTemplate jdbc;
  private final Set<LockKey> locked = new HashSet<>();
  private final Map<Long, Integer> highestToolOrdinalByAssistant = new HashMap<>();
  private final Thread owner = Thread.currentThread();
  private RuntimeException databaseFailure;
  private LockRank highestLockRank;
  private Long highestThreadId;
  private WorkTarget highestWorkTarget;
  private boolean closed;

  PostgresqlHarnessTransaction(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  void close() {
    closed = true;
  }

  void rethrowDatabaseFailure() {
    if (databaseFailure != null) {
      throw databaseFailure;
    }
  }

  @Override
  public long nextId() {
    checkOpen();
    try {
      Long id = jdbc.queryForObject("select nextval('" + SEQUENCE_NAME + "')", Long.class);
      if (id == null || id <= 0) {
        throw new ArithmeticException("durable id sequence did not return a positive id");
      }
      return id;
    } catch (DataAccessException error) {
      if (hasSqlState(error, "2200H") || hasSqlState(error, "22003")) {
        throw remember(new ArithmeticException("durable id sequence exhausted"));
      }
      throw remember(error);
    }
  }

  @Override
  public void insertSession(Session session) {
    checkOpen();
    Objects.requireNonNull(session, "session");
    update(
        "insert into harness_session (id, title, created_at) values (?, ?, ?)",
        session.id(),
        session.title(),
        PostgresqlHarnessRows.timestamp(session.createdAt()));
  }

  @Override
  public Optional<Session> findSession(long id) {
    checkOpen();
    return queryOne(
        "select * from harness_session where id = ?", PostgresqlHarnessRows.SESSION, id);
  }

  @Override
  public void insertEntry(Entry entry) {
    checkOpen();
    Objects.requireNonNull(entry, "entry");
    if (findEntry(entry.id()).isPresent()) {
      throw new IllegalArgumentException("duplicate entry id " + entry.id());
    }
    if (findSession(entry.sessionId()).isEmpty()) {
      throw new IllegalArgumentException("session " + entry.sessionId() + " does not exist");
    }
    if (entry.payload().type().isRoot()) {
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
      EntryPath parentPath = loadEntryPath(entry.parentEntryId());
      List<Entry> nextPath = new ArrayList<>(parentPath.entries());
      nextPath.add(entry);
      new EntryPath(nextPath);
    }
    update(
        """
        insert into harness_entry (
            id, session_id, parent_entry_id, entry_type, payload, created_at
        ) values (?, ?, ?, ?, cast(? as jsonb), ?)
        """,
        entry.id(),
        entry.sessionId(),
        entry.parentEntryId(),
        entry.payload().type().name(),
        PostgresqlHarnessRows.ENTRY_PAYLOADS.encode(entry.payload()),
        PostgresqlHarnessRows.timestamp(entry.createdAt()));
  }

  @Override
  public Optional<Entry> findEntry(long id) {
    checkOpen();
    return queryOne("select * from harness_entry where id = ?", PostgresqlHarnessRows.ENTRY, id);
  }

  @Override
  public EntryPath loadEntryPath(long headEntryId) {
    checkOpen();
    List<Entry> path = new ArrayList<>();
    Set<Long> visited = new HashSet<>();
    Long cursor = headEntryId;
    while (cursor != null) {
      if (!visited.add(cursor)) {
        throw new IllegalArgumentException("entry parent cycle detected at " + cursor);
      }
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
    requireExistingEntry(thread.headEntryId());
    requireCanLockThread(thread.id());
    update(
        """
        insert into harness_thread (
            id, head_entry_id, yolo_enabled, next_command_sequence, revision,
            created_at, updated_at
        ) values (?, ?, ?, ?, ?, ?, ?)
        """,
        thread.id(),
        thread.headEntryId(),
        thread.yoloEnabled(),
        thread.nextCommandSequence(),
        thread.revision(),
        PostgresqlHarnessRows.timestamp(thread.createdAt()),
        PostgresqlHarnessRows.timestamp(thread.updatedAt()));
    recordThreadLock(thread.id());
  }

  @Override
  public Optional<ThreadState> findThread(long id) {
    checkOpen();
    return queryOne("select * from harness_thread where id = ?", PostgresqlHarnessRows.THREAD, id);
  }

  @Override
  public Optional<ThreadState> lockThread(long id) {
    checkOpen();
    requireCanLockThread(id);
    Optional<ThreadState> thread =
        queryOne(
            "select * from harness_thread where id = ? for update",
            PostgresqlHarnessRows.THREAD,
            id);
    thread.ifPresent(ignored -> recordThreadLock(id));
    return thread;
  }

  @Override
  public void updateThread(ThreadState thread) {
    checkOpen();
    Objects.requireNonNull(thread, "thread");
    requireLocked(LockKey.thread(thread.id()));
    ThreadState stored =
        findThread(thread.id())
            .orElseThrow(
                () -> new IllegalArgumentException("thread " + thread.id() + " does not exist"));
    ThreadState.validateTransition(stored, thread);
    requireExistingEntry(thread.headEntryId());
    int updated =
        update(
            """
            update harness_thread
            set head_entry_id = ?,
                yolo_enabled = ?,
                next_command_sequence = ?,
                revision = ?,
                updated_at = ?
            where id = ?
            """,
            thread.headEntryId(),
            thread.yoloEnabled(),
            thread.nextCommandSequence(),
            thread.revision(),
            PostgresqlHarnessRows.timestamp(thread.updatedAt()),
            thread.id());
    requireSingleUpdate(updated, "thread", thread.id());
  }

  @Override
  public Optional<ThreadCommand> findCommandByClientId(long threadId, String clientCommandId) {
    checkOpen();
    Objects.requireNonNull(clientCommandId, "clientCommandId");
    return queryOne(
        """
        select *
        from harness_thread_command
        where thread_id = ? and client_command_id = ?
        """,
        PostgresqlHarnessRows.COMMAND,
        threadId,
        clientCommandId);
  }

  @Override
  public List<ThreadCommand> loadQueuedCommands(long threadId) {
    checkOpen();
    requireLocked(LockKey.thread(threadId));
    requireCanLockRank(LockRank.COMMAND);
    List<ThreadCommand> commands =
        queryList(
            """
            select *
            from harness_thread_command
            where thread_id = ?
              and consumed_turn_start_entry_id is null
              and cancelled_at is null
            order by sequence
            for update
            """,
            PostgresqlHarnessRows.COMMAND,
            threadId);
    for (ThreadCommand command : commands) {
      lock(LockKey.command(command.id()));
    }
    return commands;
  }

  @Override
  public void insertCommands(List<ThreadCommand> commands) {
    checkOpen();
    List<ThreadCommand> copied = List.copyOf(commands).stream().sorted(COMMAND_LOCK_ORDER).toList();
    Set<Long> ids = new HashSet<>();
    Set<CommandSequenceKey> sequences = new HashSet<>();
    Set<CommandClientKey> clientIds = new HashSet<>();
    for (ThreadCommand command : copied) {
      PostgresqlHarnessRows.requireMillisecondPrecision(command.cancelledAt());
      PostgresqlHarnessRows.requireMillisecondPrecision(command.createdAt());
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
      if (findCommand(command.id()).isPresent()) {
        throw new IllegalArgumentException("duplicate command id " + command.id());
      }
      if (command.state() != ThreadCommandState.QUEUED) {
        throw new IllegalArgumentException("inserted commands must be QUEUED");
      }
      if (findThread(command.threadId()).isEmpty()) {
        throw new IllegalArgumentException("thread " + command.threadId() + " does not exist");
      }
      requireLocked(LockKey.thread(command.threadId()));
      requireUniqueCommandKey(command);
    }
    if (!copied.isEmpty()) {
      requireCanLockRank(LockRank.COMMAND);
    }
    for (ThreadCommand command : copied) {
      update(
          """
          insert into harness_thread_command (
              id, thread_id, sequence, command_type, payload, client_command_id,
              consumed_turn_start_entry_id, cancelled_at, created_at
          ) values (?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?)
          """,
          command.id(),
          command.threadId(),
          command.sequence(),
          command.type().name(),
          PostgresqlHarnessRows.COMMAND_PAYLOADS.encode(command.payload()),
          command.clientCommandId(),
          command.consumedTurnStartEntryId(),
          PostgresqlHarnessRows.timestamp(command.cancelledAt()),
          PostgresqlHarnessRows.timestamp(command.createdAt()));
      lock(LockKey.command(command.id()));
    }
  }

  @Override
  public void updateCommands(List<ThreadCommand> commands) {
    checkOpen();
    List<ThreadCommand> copied = List.copyOf(commands).stream().sorted(COMMAND_LOCK_ORDER).toList();
    Set<Long> ids = new HashSet<>();
    for (ThreadCommand command : copied) {
      PostgresqlHarnessRows.requireMillisecondPrecision(command.cancelledAt());
      PostgresqlHarnessRows.requireMillisecondPrecision(command.createdAt());
      if (!ids.add(command.id())) {
        throw new IllegalArgumentException("duplicate command id " + command.id());
      }
      requireLocked(LockKey.command(command.id()));
      ThreadCommand stored =
          findCommand(command.id())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException("command " + command.id() + " does not exist"));
      requireSameCommandIdentity(stored, command);
      requireLocked(LockKey.thread(command.threadId()));
      requireValidCommandLifecycle(stored, command);
      requireValidConsumedTurnStart(command);
    }
    for (ThreadCommand command : copied) {
      int updated =
          update(
              """
              update harness_thread_command
              set consumed_turn_start_entry_id = ?, cancelled_at = ?
              where id = ?
              """,
              command.consumedTurnStartEntryId(),
              PostgresqlHarnessRows.timestamp(command.cancelledAt()),
              command.id());
      requireSingleUpdate(updated, "command", command.id());
    }
  }

  @Override
  public Optional<ModelInvocation> findModelInvocation(long id) {
    checkOpen();
    return queryOne(
        "select * from harness_model_invocation where id = ?",
        PostgresqlHarnessRows.MODEL_INVOCATION,
        id);
  }

  @Override
  public Optional<ModelInvocation> lockModelInvocation(long id) {
    checkOpen();
    LockKey lockKey = LockKey.model(id);
    requireCanLock(lockKey);
    Optional<ModelInvocation> invocation =
        queryOne(
            "select * from harness_model_invocation where id = ? for update",
            PostgresqlHarnessRows.MODEL_INVOCATION,
            id);
    invocation.ifPresent(ignored -> lock(lockKey));
    return invocation;
  }

  @Override
  public Optional<ModelInvocation> findModelInvocationByTurn(long threadId, long turnStartEntryId) {
    checkOpen();
    return queryOne(
        """
        select *
        from harness_model_invocation
        where thread_id = ? and turn_start_entry_id = ?
        """,
        PostgresqlHarnessRows.MODEL_INVOCATION,
        threadId,
        turnStartEntryId);
  }

  @Override
  public void insertModelInvocation(ModelInvocation invocation) {
    checkOpen();
    Objects.requireNonNull(invocation, "invocation");
    if (findModelInvocation(invocation.id()).isPresent()) {
      throw new IllegalArgumentException("duplicate model invocation id " + invocation.id());
    }
    requireUniqueModelTurn(invocation);
    ThreadState thread =
        findThread(invocation.threadId())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "thread " + invocation.threadId() + " does not exist"));
    requireLocked(LockKey.thread(invocation.threadId()));
    Entry turnStart = requireExistingEntry(invocation.turnStartEntryId());
    if (turnStart.payload().type() != EntryType.TURN_START) {
      throw new IllegalArgumentException("turnStartEntryId must reference a TURN_START entry");
    }
    if (invocation.status() != ModelInvocationStatus.READY || invocation.attempt() != 0) {
      throw new IllegalArgumentException("new model invocations must be READY with attempt 0");
    }
    if (thread.headEntryId() != invocation.basisHeadEntryId()) {
      throw new IllegalArgumentException(
          "basisHeadEntryId must equal the current thread head entry");
    }
    requireValidModelBranch(invocation, thread);
    requireValidModelResultEntry(invocation);
    LockKey lockKey = LockKey.model(invocation.id());
    requireCanLock(lockKey);
    update(
        """
        insert into harness_model_invocation (
            id, thread_id, turn_start_entry_id, basis_head_entry_id, request, status, attempt,
            stream_checkpoint, result, error, result_entry_id, created_at, updated_at
        ) values (
            ?, ?, ?, ?, cast(? as jsonb), ?, ?, cast(? as jsonb), cast(? as jsonb),
            cast(? as jsonb), ?, ?, ?
        )
        """,
        invocation.id(),
        invocation.threadId(),
        invocation.turnStartEntryId(),
        invocation.basisHeadEntryId(),
        PostgresqlHarnessRows.MODEL_REQUESTS.encode(invocation.request()),
        invocation.status().name(),
        invocation.attempt(),
        encodeStreamCheckpoint(invocation),
        encodeModelResult(invocation),
        encodeModelError(invocation),
        invocation.resultEntryId(),
        PostgresqlHarnessRows.timestamp(invocation.createdAt()),
        PostgresqlHarnessRows.timestamp(invocation.updatedAt()));
    lock(lockKey);
  }

  @Override
  public void updateModelInvocation(ModelInvocation invocation) {
    checkOpen();
    Objects.requireNonNull(invocation, "invocation");
    requireLocked(LockKey.model(invocation.id()));
    ModelInvocation stored =
        findModelInvocation(invocation.id())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "model invocation " + invocation.id() + " does not exist"));
    ModelInvocation.validateTransition(stored, invocation);
    requireValidModelResultEntry(invocation);
    int updated =
        update(
            """
            update harness_model_invocation
            set status = ?,
                attempt = ?,
                stream_checkpoint = cast(? as jsonb),
                result = cast(? as jsonb),
                error = cast(? as jsonb),
                result_entry_id = ?,
                updated_at = ?
            where id = ?
            """,
            invocation.status().name(),
            invocation.attempt(),
            encodeStreamCheckpoint(invocation),
            encodeModelResult(invocation),
            encodeModelError(invocation),
            invocation.resultEntryId(),
            PostgresqlHarnessRows.timestamp(invocation.updatedAt()),
            invocation.id());
    requireSingleUpdate(updated, "model invocation", invocation.id());
  }

  @Override
  public Optional<ToolInvocation> findToolInvocation(long id) {
    checkOpen();
    return queryOne(
        "select * from harness_tool_invocation where id = ?",
        PostgresqlHarnessRows.TOOL_INVOCATION,
        id);
  }

  @Override
  public Optional<ToolInvocation> lockToolInvocation(long id) {
    checkOpen();
    Optional<ToolInvocation> current = findToolInvocation(id);
    if (current.isEmpty()) {
      return Optional.empty();
    }
    requireCanLockTools(current.stream().toList());
    Optional<ToolInvocation> invocation =
        queryOne(
            "select * from harness_tool_invocation where id = ? for update",
            PostgresqlHarnessRows.TOOL_INVOCATION,
            id);
    invocation.ifPresent(this::lockTool);
    return invocation;
  }

  @Override
  public List<ToolInvocation> loadToolInvocationsByAssistantEntryId(long assistantEntryId) {
    checkOpen();
    return queryList(
        """
        select *
        from harness_tool_invocation
        where assistant_entry_id = ?
        order by ordinal
        """,
        PostgresqlHarnessRows.TOOL_INVOCATION,
        assistantEntryId);
  }

  @Override
  public List<ToolInvocation> lockToolInvocationsByAssistantEntryId(long assistantEntryId) {
    checkOpen();
    requireCanLockRank(LockRank.TOOL);
    List<ToolInvocation> current = loadToolInvocationsByAssistantEntryId(assistantEntryId);
    requireCanLockTools(current);
    List<ToolInvocation> invocations =
        queryList(
            """
            select *
            from harness_tool_invocation
            where assistant_entry_id = ?
            order by ordinal
            for update
            """,
            PostgresqlHarnessRows.TOOL_INVOCATION,
            assistantEntryId);
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
      PostgresqlHarnessRows.requireMillisecondPrecision(invocation.createdAt());
      PostgresqlHarnessRows.requireMillisecondPrecision(invocation.updatedAt());
      if (!ids.add(invocation.id())) {
        throw new IllegalArgumentException("duplicate tool invocation id " + invocation.id());
      }
      if (!ordinals.add(new ToolOrdinalKey(invocation.assistantEntryId(), invocation.ordinal()))) {
        throw new IllegalArgumentException(
            "duplicate tool invocation ordinal "
                + invocation.ordinal()
                + " on assistant entry "
                + invocation.assistantEntryId());
      }
      if (findToolInvocation(invocation.id()).isPresent()) {
        throw new IllegalArgumentException("duplicate tool invocation id " + invocation.id());
      }
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
      update(
          """
          insert into harness_tool_invocation (
              id, model_invocation_id, assistant_entry_id, ordinal, request, status, attempt,
              approval, result, error, result_entry_id, created_at, updated_at
          ) values (
              ?, ?, ?, ?, cast(? as jsonb), ?, ?, cast(? as jsonb), cast(? as jsonb),
              cast(? as jsonb), ?, ?, ?
          )
          """,
          invocation.id(),
          invocation.modelInvocationId(),
          invocation.assistantEntryId(),
          invocation.ordinal(),
          PostgresqlHarnessRows.TOOL_REQUESTS.encode(invocation.request()),
          invocation.status().name(),
          invocation.attempt(),
          encodeToolApproval(invocation),
          encodeToolResult(invocation),
          encodeToolError(invocation),
          invocation.resultEntryId(),
          PostgresqlHarnessRows.timestamp(invocation.createdAt()),
          PostgresqlHarnessRows.timestamp(invocation.updatedAt()));
      lockTool(invocation);
    }
  }

  @Override
  public void updateToolInvocations(List<ToolInvocation> invocations) {
    checkOpen();
    List<ToolInvocation> copied =
        List.copyOf(invocations).stream().sorted(TOOL_LOCK_ORDER).toList();
    Set<Long> ids = new HashSet<>();
    Set<Long> resultEntryIds = new HashSet<>();
    for (ToolInvocation invocation : copied) {
      PostgresqlHarnessRows.requireMillisecondPrecision(invocation.createdAt());
      PostgresqlHarnessRows.requireMillisecondPrecision(invocation.updatedAt());
      if (!ids.add(invocation.id())) {
        throw new IllegalArgumentException("duplicate tool invocation id " + invocation.id());
      }
      if (invocation.resultEntryId() != null && !resultEntryIds.add(invocation.resultEntryId())) {
        throw new IllegalArgumentException(
            "duplicate tool resultEntryId " + invocation.resultEntryId());
      }
      requireLocked(LockKey.tool(invocation.id()));
      ToolInvocation stored =
          findToolInvocation(invocation.id())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "tool invocation " + invocation.id() + " does not exist"));
      ToolInvocation.validateTransition(stored, invocation);
      requireValidToolResultEntry(invocation);
    }
    for (ToolInvocation invocation : copied) {
      int updated =
          update(
              """
              update harness_tool_invocation
              set status = ?,
                  attempt = ?,
                  approval = cast(? as jsonb),
                  result = cast(? as jsonb),
                  error = cast(? as jsonb),
                  result_entry_id = ?,
                  updated_at = ?
              where id = ?
              """,
              invocation.status().name(),
              invocation.attempt(),
              encodeToolApproval(invocation),
              encodeToolResult(invocation),
              encodeToolError(invocation),
              invocation.resultEntryId(),
              PostgresqlHarnessRows.timestamp(invocation.updatedAt()),
              invocation.id());
      requireSingleUpdate(updated, "tool invocation", invocation.id());
    }
  }

  @Override
  public Optional<Work> findWork(WorkTarget target) {
    checkOpen();
    Objects.requireNonNull(target, "target");
    return queryOne(
        "select * from harness_work where target_type = ? and target_id = ?",
        PostgresqlHarnessRows.WORK,
        target.type().name(),
        target.id());
  }

  @Override
  public Optional<Work> lockWork(WorkTarget target) {
    checkOpen();
    Objects.requireNonNull(target, "target");
    requireCanLockWork(target);
    Optional<Work> work =
        queryOne(
            """
            select *
            from harness_work
            where target_type = ? and target_id = ?
            for update
            """,
            PostgresqlHarnessRows.WORK,
            target.type().name(),
            target.id());
    work.ifPresent(ignored -> recordWorkLock(target));
    return work;
  }

  @Override
  public Optional<Work> lockClaimedWork(ClaimedWork claim, Instant now) {
    checkOpen();
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(now, "now");
    requireCanLockWork(claim.target());
    Optional<Work> work =
        queryOne(
            """
            select *
            from harness_work
            where target_type = ?
              and target_id = ?
              and lease_token = ?
              and lease_until > ?
            for update
            """,
            PostgresqlHarnessRows.WORK,
            claim.target().type().name(),
            claim.target().id(),
            claim.leaseToken(),
            PostgresqlHarnessRows.timestamp(now));
    work.ifPresent(ignored -> recordWorkLock(claim.target()));
    return work;
  }

  @Override
  public boolean deleteWork(WorkTarget target) {
    checkOpen();
    Objects.requireNonNull(target, "target");
    if (findWork(target).isEmpty()) {
      return false;
    }
    requireWorkOwnerLocked(target);
    if (lockWork(target).isEmpty()) {
      return false;
    }
    int deleted =
        update(
            "delete from harness_work where target_type = ? and target_id = ?",
            target.type().name(),
            target.id());
    requireSingleUpdate(deleted, "work", target.id());
    return true;
  }

  @Override
  public void requestWork(WorkTarget target, Instant requestedAt) {
    checkOpen();
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(requestedAt, "requestedAt");
    requireWorkOwnerLocked(target);
    requireCanLockWork(target);
    Work work =
        writeOne(
                REQUEST_WORK,
                PostgresqlHarnessRows.WORK,
                target.type().name(),
                target.id(),
                PostgresqlHarnessRows.timestamp(requestedAt))
            .orElseThrow(() -> new IllegalStateException("work upsert returned no row"));
    recordWorkLock(work.target());
    notifyWorkAvailable();
  }

  @Override
  public Optional<ClaimedWork> claimNextWork(
      WorkTargetType targetType, Instant now, String leaseToken, Instant leaseUntil) {
    checkOpen();
    Objects.requireNonNull(targetType, "targetType");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(leaseUntil, "leaseUntil");
    Work.initial(new WorkTarget(targetType, 1L), now).claim(now, leaseToken, leaseUntil);
    requireCanClaimWork();
    Optional<Work> claimed =
        writeOne(
            CLAIM_NEXT_WORK,
            PostgresqlHarnessRows.WORK,
            targetType.name(),
            PostgresqlHarnessRows.timestamp(now),
            PostgresqlHarnessRows.timestamp(now),
            leaseToken,
            PostgresqlHarnessRows.timestamp(leaseUntil));
    if (claimed.isEmpty()) {
      return Optional.empty();
    }
    Work work = claimed.get();
    requireTargetExists(work.target());
    recordWorkLock(work.target());
    return Optional.of(
        new ClaimedWork(work.target(), work.wakeVersion(), work.leaseToken(), work.leaseUntil()));
  }

  @Override
  public void renewWork(ClaimedWork claim, Instant now, Instant newLeaseUntil) {
    checkOpen();
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(newLeaseUntil, "newLeaseUntil");
    PostgresqlHarnessRows.requireMillisecondPrecision(now);
    PostgresqlHarnessRows.requireMillisecondPrecision(newLeaseUntil);
    Work renewed = lockedWork(claim.target()).renew(claim.leaseToken(), now, newLeaseUntil);
    updateWork(renewed);
  }

  @Override
  public Optional<Work> completeWork(ClaimedWork claim, Instant now) {
    checkOpen();
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(now, "now");
    PostgresqlHarnessRows.requireMillisecondPrecision(now);
    Work work = lockedWork(claim.target());
    Optional<Work> next = work.complete(claim.leaseToken(), claim.claimedWakeVersion(), now);
    if (next.isEmpty()) {
      int deleted =
          update(
              "delete from harness_work where target_type = ? and target_id = ?",
              claim.target().type().name(),
              claim.target().id());
      requireSingleUpdate(deleted, "work", claim.target().id());
    } else {
      updateWork(next.get());
      notifyWorkAvailable();
    }
    return next;
  }

  @Override
  public void rescheduleWork(ClaimedWork claim, Instant now, Instant requestedAt) {
    checkOpen();
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(requestedAt, "requestedAt");
    PostgresqlHarnessRows.requireMillisecondPrecision(now);
    PostgresqlHarnessRows.requireMillisecondPrecision(requestedAt);
    Work next =
        lockedWork(claim.target())
            .reschedule(claim.leaseToken(), claim.claimedWakeVersion(), now, requestedAt);
    updateWork(next);
    notifyWorkAvailable();
  }

  private Optional<ThreadCommand> findCommand(long id) {
    return queryOne(
        "select * from harness_thread_command where id = ?", PostgresqlHarnessRows.COMMAND, id);
  }

  private boolean hasRoot(long sessionId) {
    Boolean exists =
        queryForObject(
            """
            select exists (
                select 1
                from harness_entry
                where session_id = ? and entry_type = 'ROOT'
            )
            """,
            Boolean.class,
            sessionId);
    return Boolean.TRUE.equals(exists);
  }

  private Entry requireExistingEntry(long entryId) {
    return findEntry(entryId)
        .orElseThrow(() -> new IllegalArgumentException("entry " + entryId + " does not exist"));
  }

  private void requireUniqueCommandKey(ThreadCommand command) {
    Boolean sequenceExists =
        queryForObject(
            """
            select exists (
                select 1
                from harness_thread_command
                where thread_id = ? and sequence = ?
            )
            """,
            Boolean.class,
            command.threadId(),
            command.sequence());
    if (Boolean.TRUE.equals(sequenceExists)) {
      throw new IllegalArgumentException(
          "command sequence "
              + command.sequence()
              + " already used on thread "
              + command.threadId());
    }
    Boolean clientExists =
        queryForObject(
            """
            select exists (
                select 1
                from harness_thread_command
                where thread_id = ? and client_command_id = ?
            )
            """,
            Boolean.class,
            command.threadId(),
            command.clientCommandId());
    if (Boolean.TRUE.equals(clientExists)) {
      throw new IllegalArgumentException(
          "clientCommandId "
              + command.clientCommandId()
              + " already used on thread "
              + command.threadId());
    }
  }

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
    ThreadState thread =
        findThread(command.threadId())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "thread " + command.threadId() + " does not exist"));
    long threadSessionId = loadEntryPath(thread.headEntryId()).root().sessionId();
    if (turnStartSessionId != threadSessionId) {
      throw new IllegalArgumentException(
          "consumed turn start must be in the command thread's current head session");
    }
  }

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

  private void requireUniqueModelTurn(ModelInvocation invocation) {
    Boolean exists =
        queryForObject(
            """
            select exists (
                select 1
                from harness_model_invocation
                where thread_id = ? and turn_start_entry_id = ?
            )
            """,
            Boolean.class,
            invocation.threadId(),
            invocation.turnStartEntryId());
    if (Boolean.TRUE.equals(exists)) {
      throw new IllegalArgumentException(
          "model invocation already exists for thread "
              + invocation.threadId()
              + " turn "
              + invocation.turnStartEntryId());
    }
  }

  private void requireValidModelBranch(ModelInvocation invocation, ThreadState thread) {
    EntryPath basisPath = loadEntryPath(invocation.basisHeadEntryId());
    long basisSessionId = basisPath.root().sessionId();
    boolean turnStartOnBasisPath =
        basisPath.entries().stream().anyMatch(entry -> entry.id() == invocation.turnStartEntryId());
    if (!turnStartOnBasisPath) {
      throw new IllegalArgumentException(
          "turnStartEntryId must be on the basisHeadEntryId entry path");
    }
    long threadSessionId = loadEntryPath(thread.headEntryId()).root().sessionId();
    if (threadSessionId != basisSessionId) {
      throw new IllegalArgumentException("thread head session must match the basis path session");
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
    Boolean used =
        queryForObject(
            """
            select exists (
                select 1
                from harness_model_invocation
                where result_entry_id = ? and id <> ?
            )
            """,
            Boolean.class,
            resultEntryId,
            invocation.id());
    if (Boolean.TRUE.equals(used)) {
      throw new IllegalArgumentException(
          "model resultEntryId " + resultEntryId + " is already used");
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

  private void requireUniqueToolOrdinal(ToolInvocation invocation) {
    Boolean exists =
        queryForObject(
            """
            select exists (
                select 1
                from harness_tool_invocation
                where assistant_entry_id = ? and ordinal = ?
            )
            """,
            Boolean.class,
            invocation.assistantEntryId(),
            invocation.ordinal());
    if (Boolean.TRUE.equals(exists)) {
      throw new IllegalArgumentException(
          "tool invocation ordinal "
              + invocation.ordinal()
              + " already used on assistant entry "
              + invocation.assistantEntryId());
    }
  }

  private void requireValidToolReferences(ToolInvocation invocation) {
    ModelInvocation model =
        findModelInvocation(invocation.modelInvocationId())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "model invocation " + invocation.modelInvocationId() + " does not exist"));
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

  private static void requireMatchingAssistantToolCall(ToolInvocation invocation, Entry assistant) {
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
      throw new IllegalArgumentException("tool resultEntryId must reference a TOOL MESSAGE entry");
    }
    ToolResultMetadata metadata = ((MessagePayload) result.payload()).toolResultMetadata();
    if (metadata.assistantEntryId() != invocation.assistantEntryId()
        || metadata.ordinal() != invocation.ordinal()
        || !metadata.toolCallId().equals(invocation.request().call().id())) {
      throw new IllegalArgumentException(
          "tool result entry must match the invocation assistant entry, ordinal and toolCallId");
    }
    if (metadata.synthetic()) {
      throw new IllegalArgumentException("tool result entry must not be synthetic");
    }
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
    boolean assistantOnResultPath =
        loadEntryPath(resultEntryId).entries().stream()
            .anyMatch(entry -> entry.id() == invocation.assistantEntryId());
    if (!assistantOnResultPath) {
      throw new IllegalArgumentException(
          "tool result entry must be in the same branch as the assistant entry");
    }
    Boolean used =
        queryForObject(
            """
            select exists (
                select 1
                from harness_tool_invocation
                where result_entry_id = ? and id <> ?
            )
            """,
            Boolean.class,
            resultEntryId,
            invocation.id());
    if (Boolean.TRUE.equals(used)) {
      throw new IllegalArgumentException(
          "tool resultEntryId " + resultEntryId + " is already used");
    }
  }

  private void requireWorkOwnerLocked(WorkTarget target) {
    long threadId =
        switch (target.type()) {
          case THREAD -> findThread(target.id())
              .orElseThrow(
                  () -> new IllegalArgumentException("work target does not exist: " + target))
              .id();
          case MODEL -> findModelInvocation(target.id())
              .orElseThrow(
                  () -> new IllegalArgumentException("work target does not exist: " + target))
              .threadId();
          case TOOL -> {
            ToolInvocation tool =
                findToolInvocation(target.id())
                    .orElseThrow(
                        () ->
                            new IllegalArgumentException("work target does not exist: " + target));
            yield findModelInvocation(tool.modelInvocationId())
                .orElseThrow(
                    () ->
                        new IllegalArgumentException(
                            "model invocation "
                                + tool.modelInvocationId()
                                + " does not exist for work target "
                                + target))
                .threadId();
          }
        };
    requireLocked(LockKey.thread(threadId));
  }

  private static boolean isToolResultEntry(Entry entry) {
    return entry.payload().type() == EntryType.MESSAGE
        && entry.payload() instanceof MessagePayload message
        && message.message().role() == AgentMessageRole.TOOL;
  }

  private void requireTargetExists(WorkTarget target) {
    String table =
        switch (target.type()) {
          case THREAD -> "harness_thread";
          case MODEL -> "harness_model_invocation";
          case TOOL -> "harness_tool_invocation";
        };
    Boolean exists =
        queryForObject(
            "select exists (select 1 from " + table + " where id = ?)", Boolean.class, target.id());
    if (!Boolean.TRUE.equals(exists)) {
      throw new IllegalArgumentException("work target does not exist: " + target);
    }
  }

  private Work lockedWork(WorkTarget target) {
    return lockWork(target)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "work does not exist for target " + target + " (lost ownership)"));
  }

  private void updateWork(Work work) {
    int updated =
        update(
            """
            update harness_work
            set available_at = ?,
                wake_version = ?,
                lease_token = ?,
                lease_until = ?
            where target_type = ? and target_id = ?
            """,
            PostgresqlHarnessRows.timestamp(work.availableAt()),
            work.wakeVersion(),
            work.leaseToken(),
            PostgresqlHarnessRows.timestamp(work.leaseUntil()),
            work.target().type().name(),
            work.target().id());
    requireSingleUpdate(updated, "work", work.target().id());
  }

  private static String encodeStreamCheckpoint(ModelInvocation invocation) {
    return invocation.streamCheckpoint() == null
        ? null
        : PostgresqlHarnessRows.STREAM_CHECKPOINTS.encode(invocation.streamCheckpoint());
  }

  private static String encodeModelResult(ModelInvocation invocation) {
    return invocation.result() == null
        ? null
        : PostgresqlHarnessRows.MODEL_RESULTS.encode(invocation.result());
  }

  private static String encodeModelError(ModelInvocation invocation) {
    return invocation.error() == null
        ? null
        : PostgresqlHarnessRows.MODEL_ERRORS.encode(invocation.error());
  }

  private static String encodeToolApproval(ToolInvocation invocation) {
    return invocation.approval() == null
        ? null
        : PostgresqlHarnessRows.TOOL_APPROVALS.encode(invocation.approval());
  }

  private static String encodeToolResult(ToolInvocation invocation) {
    return invocation.result() == null ? null : ToolResultJsonCodec.encode(invocation.result());
  }

  private static String encodeToolError(ToolInvocation invocation) {
    return invocation.error() == null
        ? null
        : PostgresqlHarnessRows.TOOL_ERRORS.encode(invocation.error());
  }

  private void notifyWorkAvailable() {
    boolean sent =
        queryOne(
                "select pg_notify(?, ?) as ignored, true as sent",
                (resultSet, rowNumber) -> resultSet.getBoolean("sent"),
                PostgresqlWorkChannel.NAME,
                "")
            .orElseThrow(() -> new IllegalStateException("pg_notify returned no row"));
    if (!sent) {
      throw new IllegalStateException("pg_notify did not confirm execution");
    }
  }

  private <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... arguments) {
    List<T> rows;
    try {
      rows = jdbc.query(sql, mapper, arguments);
    } catch (DataIntegrityViolationException error) {
      throw remember(integrityViolation(error));
    } catch (DataAccessException error) {
      throw remember(error);
    }
    if (rows.size() > 1) {
      throw new IllegalStateException("query expected at most one row but returned " + rows.size());
    }
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  private <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... arguments) {
    try {
      return List.copyOf(jdbc.query(sql, mapper, arguments));
    } catch (DataIntegrityViolationException error) {
      throw remember(integrityViolation(error));
    } catch (DataAccessException error) {
      throw remember(error);
    }
  }

  private <T> T queryForObject(String sql, Class<T> requiredType, Object... arguments) {
    try {
      return jdbc.queryForObject(sql, requiredType, arguments);
    } catch (DataIntegrityViolationException error) {
      throw remember(integrityViolation(error));
    } catch (DataAccessException error) {
      throw remember(error);
    }
  }

  private int update(String sql, Object... arguments) {
    try {
      return jdbc.update(sql, arguments);
    } catch (DataIntegrityViolationException error) {
      throw remember(integrityViolation(error));
    } catch (DataAccessException error) {
      throw remember(error);
    }
  }

  private <T> Optional<T> writeOne(String sql, RowMapper<T> mapper, Object... arguments) {
    return queryOne(sql, mapper, arguments);
  }

  private <T extends RuntimeException> T remember(T failure) {
    if (databaseFailure == null) {
      databaseFailure = failure;
    }
    return failure;
  }

  private static IllegalArgumentException integrityViolation(
      DataIntegrityViolationException error) {
    return new IllegalArgumentException("PostgreSQL integrity constraint violation", error);
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

  private static void requireSingleUpdate(int updated, String kind, long id) {
    if (updated != 1) {
      throw new IllegalArgumentException(kind + " " + id + " does not exist");
    }
  }

  private static boolean hasSqlState(Throwable error, String expected) {
    Throwable cursor = error;
    while (cursor != null) {
      if (cursor instanceof SQLException sqlException
          && expected.equals(sqlException.getSQLState())) {
        return true;
      }
      cursor = cursor.getCause();
    }
    return false;
  }

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
}
