package fun.fengwk.kkstudio.harness.infra.postgresql;

import fun.fengwk.kkstudio.harness.builtin.history.ModelAttemptMaterialization;
import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessTransaction.LockKey;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.store.CommandStore;
import fun.fengwk.kkstudio.harness.runtime.store.EntryStore;
import fun.fengwk.kkstudio.harness.runtime.store.ExecutionExitStore;
import fun.fengwk.kkstudio.harness.runtime.store.JoinStore;
import fun.fengwk.kkstudio.harness.runtime.store.SessionStore;
import fun.fengwk.kkstudio.harness.runtime.store.StopReceiptStore;
import fun.fengwk.kkstudio.harness.runtime.store.ThreadStore;
import fun.fengwk.kkstudio.harness.runtime.store.ToolStore;
import fun.fengwk.kkstudio.harness.runtime.store.WorkStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Builtin 私有模型事务视图；与 Runtime transaction 共享同一连接、时钟、锁序与 poisoning 状态。 */
final class PostgresqlModelTransaction extends PostgresqlHarnessView
    implements BuiltinStore.Transaction {

  PostgresqlModelTransaction(PostgresqlHarnessTransaction tx) {
    super(tx);
  }

  @Override
  public Instant now() {
    return tx.now();
  }

  @Override
  public UUID nextId() {
    return tx.nextId();
  }

  @Override
  public SessionStore sessions() {
    return tx.sessions();
  }

  @Override
  public EntryStore entries() {
    return tx.entries();
  }

  @Override
  public ThreadStore threads() {
    return tx.threads();
  }

  @Override
  public CommandStore commands() {
    return tx.commands();
  }

  @Override
  public ToolStore tools() {
    return tx.tools();
  }

  @Override
  public WorkStore work() {
    return tx.work();
  }

  @Override
  public JoinStore joins() {
    return tx.joins();
  }

  @Override
  public StopReceiptStore stops() {
    return tx.stops();
  }

  @Override
  public ExecutionExitStore exits() {
    return tx.exits();
  }

  @Override
  public Optional<ModelInvocation> findModelInvocation(UUID id) {
    checkOpen();
    return queryOne(
        "select * from harness_model_invocation where id = ?",
        PostgresqlHarnessRows.MODEL_INVOCATION,
        id);
  }

  @Override
  public Optional<ModelInvocation> lockModelInvocation(UUID id) {
    checkOpen();
    LockKey lockKey = LockKey.driver(id);
    requireCanLock(lockKey);
    Optional<ModelInvocation> invocation =
        queryOne(
            "select * from harness_model_invocation where id = ? for update",
            PostgresqlHarnessRows.MODEL_INVOCATION,
            id);
    invocation.ifPresent(ignored -> lockResource(lockKey));
    return invocation;
  }

  @Override
  public Optional<ModelInvocation> findModelInvocationByTurn(UUID threadId, UUID turnStartEntryId) {
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
        tx.threads()
            .find(invocation.threadId())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "thread " + invocation.threadId() + " does not exist"));
    requireLocked(LockKey.thread(invocation.threadId()));
    TurnStartPayload turnStartPayload = requireTurnStartPayload(invocation.turnStartEntryId());
    if (!turnStartPayload.ownerThreadId().equals(invocation.threadId())) {
      throw new IllegalArgumentException(
          "turn start ownerThreadId must equal the model invocation threadId");
    }
    Integer contextWindow = turnStartPayload.contextWindow();
    if (contextWindow == null || contextWindow <= 0) {
      throw new IllegalArgumentException(
          "model invocations require a positive turn start contextWindow");
    }
    if (invocation.status() != ModelInvocationStatus.READY || invocation.attempt() != 0) {
      throw new IllegalArgumentException("new model invocations must be READY with attempt 0");
    }
    if (!thread.headEntryId().equals(invocation.requestHeadEntryId())) {
      throw new IllegalArgumentException(
          "requestHeadEntryId must equal the current thread head entry");
    }
    requireValidModelBranch(invocation, thread);
    requireValidModelResultEntry(invocation);
    LockKey lockKey = LockKey.driver(invocation.id());
    requireCanLock(lockKey);
    updateJdbc(
        """
        insert into harness_model_invocation (
            id, thread_id, turn_start_entry_id, request_head_entry_id, request_spec, status, attempt,
            stream_checkpoint, result, error, result_entry_id, failed_attempts, created_at, updated_at,
            provider_replay_state, group_execution, requested_at
        ) values (
            ?, ?, ?, ?, cast(? as jsonb), ?, ?, cast(? as jsonb), cast(? as jsonb),
            cast(? as jsonb), ?, cast(? as jsonb), ?, ?, cast(? as jsonb), cast(? as jsonb), ?
        )
        """,
        invocation.id(),
        invocation.threadId(),
        invocation.turnStartEntryId(),
        invocation.requestHeadEntryId(),
        PostgresqlHarnessRows.MODEL_REQUESTS.encode(invocation.requestSpec()),
        invocation.status().name(),
        invocation.attempt(),
        encodeStreamCheckpoint(invocation),
        encodeModelResult(invocation),
        encodeModelError(invocation),
        invocation.resultEntryId(),
        PostgresqlHarnessRows.JSON.write(invocation.failedAttempts(), "modelAttemptFailures"),
        PostgresqlHarnessRows.timestamp(invocation.createdAt()),
        PostgresqlHarnessRows.timestamp(invocation.updatedAt()),
        encodeModelProviderReplayState(invocation),
        encodeGroupExecution(invocation),
        invocation.status() == ModelInvocationStatus.READY
            ? null
            : PostgresqlHarnessRows.timestamp(invocation.createdAt()));
    lockResource(lockKey);
  }

  @Override
  public void updateModelInvocation(ModelInvocation invocation) {
    checkOpen();
    Objects.requireNonNull(invocation, "invocation");
    requireLocked(LockKey.driver(invocation.id()));
    ModelInvocation stored =
        findModelInvocation(invocation.id())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "model invocation " + invocation.id() + " does not exist"));
    ModelInvocation.validateTransition(stored, invocation);
    requireValidModelResultEntry(invocation);
    if (stored.resultEntryId() == null && invocation.resultEntryId() != null) {
      ModelAttemptMaterialization.validate(
          stored, invocation, tx.entries().loadPath(invocation.resultEntryId()));
    }
    int updated =
        updateJdbc(
            """
            update harness_model_invocation
            set status = ?,
                attempt = ?,
                stream_checkpoint = cast(? as jsonb),
                result = cast(? as jsonb),
                error = cast(? as jsonb),
                result_entry_id = ?,
                failed_attempts = cast(? as jsonb),
                updated_at = ?,
                provider_replay_state = cast(? as jsonb),
                group_execution = cast(? as jsonb),
                requested_at = ?
            where id = ?
            """,
            invocation.status().name(),
            invocation.attempt(),
            encodeStreamCheckpoint(invocation),
            encodeModelResult(invocation),
            encodeModelError(invocation),
            invocation.resultEntryId(),
            PostgresqlHarnessRows.JSON.write(invocation.failedAttempts(), "modelAttemptFailures"),
            PostgresqlHarnessRows.timestamp(invocation.updatedAt()),
            encodeModelProviderReplayState(invocation),
            encodeGroupExecution(invocation),
            invocation.requestedAt() == null
                ? null
                : PostgresqlHarnessRows.timestamp(invocation.requestedAt()),
            invocation.id());
    requireSingleUpdate(updated, "model invocation", invocation.id());
  }

  private static String encodeModelProviderReplayState(ModelInvocation invocation) {
    return invocation.providerReplayState() == null
        ? null
        : PostgresqlHarnessRows.PROVIDER_REPLAY_STATES.encode(invocation.providerReplayState());
  }

  private static String encodeGroupExecution(ModelInvocation invocation) {
    return invocation.groupProgress() == null
        ? null
        : PostgresqlHarnessRows.JSON.write(invocation.groupProgress(), "groupExecution");
  }

  @Override
  public boolean isGroupMemberCooling(
      String groupName, long groupVersion, String memberModelName, String memberVariant) {
    Instant now = now();
    return queryOne(
            """
            select exists (
                select 1
                from agent_model_group_cooldown c
                join agent_model m on m.name = c.group_name
                where c.group_name = ?
                  and c.member_model_name = ?
                  and c.member_variant = ?
                  and c.group_version = ?
                  and m.version = ?
                  and c.cooldown_until > ?
            )
            """,
            (rs, rowNumber) -> rs.getBoolean(1),
            groupName,
            memberModelName,
            memberVariant,
            groupVersion,
            groupVersion,
            PostgresqlHarnessRows.timestamp(now))
        .orElse(false);
  }

  @Override
  public void freezeGroupMember(
      String groupName,
      long groupVersion,
      String memberModelName,
      String memberVariant,
      Instant cooldownUntil) {
    Instant now = now();
    // 与组配置写入共用 agent_model 行锁：先锁定当前版本，旧版本 callback 不得写入/延长新配置的冷却。
    Long currentVersion =
        queryOne(
                "select version from agent_model where name = ? for share",
                (rs, rowNumber) -> rs.getLong(1),
                groupName)
            .orElse(null);
    if (currentVersion == null || currentVersion != groupVersion) {
      return;
    }
    updateJdbc(
        """
        insert into agent_model_group_cooldown (
            group_name, member_model_name, member_variant, group_version, cooldown_until,
            created_at, updated_at
        )
        values (?, ?, ?, ?, ?, ?, ?)
        on conflict (group_name, member_model_name, member_variant) do update
        set cooldown_until = greatest(
                agent_model_group_cooldown.cooldown_until,
                excluded.cooldown_until),
            group_version = excluded.group_version,
            updated_at = excluded.updated_at
        where agent_model_group_cooldown.group_version = excluded.group_version
        """,
        groupName,
        memberModelName,
        memberVariant,
        groupVersion,
        PostgresqlHarnessRows.timestamp(cooldownUntil),
        PostgresqlHarnessRows.timestamp(now),
        PostgresqlHarnessRows.timestamp(now));
  }

  @Override
  public boolean deleteModelInvocation(UUID modelInvocationId) {
    checkOpen();
    Objects.requireNonNull(modelInvocationId, "modelInvocationId");
    if (findModelInvocation(modelInvocationId).isEmpty()) {
      throw new IllegalArgumentException(
          "model invocation " + modelInvocationId + " does not exist");
    }
    requireLocked(LockKey.driver(modelInvocationId));
    if (hasToolInvocationChildren(modelInvocationId)) {
      throw new IllegalArgumentException(
          "model invocation " + modelInvocationId + " has tool invocation children");
    }
    int deleted =
        updateJdbc("delete from harness_model_invocation where id = ?", modelInvocationId);
    requireSingleUpdate(deleted, "model invocation", modelInvocationId);
    return true;
  }

  private boolean hasToolInvocationChildren(UUID modelInvocationId) {
    ModelInvocation model =
        findModelInvocation(modelInvocationId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "model invocation " + modelInvocationId + " does not exist"));
    Boolean exists =
        queryForObject(
            """
            select exists (
                select 1
                from harness_tool_invocation
                where thread_id = ? and turn_start_entry_id = ?
            )
            """,
            Boolean.class,
            model.threadId(),
            model.turnStartEntryId());
    return Boolean.TRUE.equals(exists);
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
    EntryPath requestHeadPath = tx.entries().loadPath(invocation.requestHeadEntryId());
    boolean turnStartOnRequestHeadPath =
        requestHeadPath.entries().stream()
            .anyMatch(entry -> entry.id().equals(invocation.turnStartEntryId()));
    if (!turnStartOnRequestHeadPath) {
      throw new IllegalArgumentException(
          "turnStartEntryId must be on the requestHeadEntryId entry path");
    }
    if (!thread.sessionId().equals(requestHeadPath.head().sessionId())) {
      throw new IllegalArgumentException("thread session must match the request head path session");
    }
  }

  private void requireValidModelResultEntry(ModelInvocation invocation) {
    UUID resultEntryId = invocation.resultEntryId();
    if (resultEntryId == null) {
      return;
    }
    Entry result = requireExistingEntry(resultEntryId);
    if (!isModelResultEntry(result)) {
      throw new IllegalArgumentException(
          "model resultEntryId must reference an assistant, assistant-error or assistant-aborted"
              + " entry");
    }
    if (resultEntryId.equals(invocation.requestHeadEntryId())) {
      throw new IllegalArgumentException(
          "model result entry must be a strict descendant of the request head entry");
    }
    EntryPath resultPath = tx.entries().loadPath(resultEntryId);
    boolean onBasisPath =
        resultPath.entries().stream()
            .anyMatch(entry -> entry.id().equals(invocation.requestHeadEntryId()));
    boolean sameTurnStart =
        resultPath.entries().stream()
            .anyMatch(entry -> entry.id().equals(invocation.turnStartEntryId()));
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

  private static String encodeStreamCheckpoint(ModelInvocation invocation) {
    return invocation.streamCheckpoint() == null
        ? null
        : PostgresqlHarnessRows.JSON.write(invocation.streamCheckpoint(), "streamCheckpoint");
  }

  private static String encodeModelResult(ModelInvocation invocation) {
    return invocation.result() == null
        ? null
        : PostgresqlHarnessRows.MODEL_RESULTS.encode(invocation.result());
  }

  private static String encodeModelError(ModelInvocation invocation) {
    return invocation.error() == null
        ? null
        : PostgresqlHarnessRows.JSON.write(invocation.error(), "modelInvocationError");
  }
}
