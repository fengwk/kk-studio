package fun.fengwk.kkstudio.harness.infra.postgresql;

import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessTransaction.LockKey;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.EnvironmentToolWaitRow;
import fun.fengwk.kkstudio.harness.runtime.store.PendingEnvironmentWaitRow;
import fun.fengwk.kkstudio.harness.runtime.store.PendingToolInvocationRow;
import fun.fengwk.kkstudio.harness.runtime.store.ToolStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** ToolInvocation 聚合的 PostgreSQL 实现。 */
final class PostgresqlToolStore implements ToolStore {

  private final PostgresqlHarnessTransaction tx;

  /**
   * 「等待环境」只读聚合查询：按 {@code (真实执行根, 冻结的所需环境)} 分组，代表取组内最早 {@code (created_at, id)}。
   *
   * <p>候选是「调用已 READY、其 TOOL Work 已到期且无有效执行 lease、冻结了非空 {@code required_environment_id}，且该环境没有有效
   * READY 连接租约」的调用；时间条件统一使用数据库时钟 {@code statement_timestamp()}，与 Work claim 的 due / lease
   * 判定同一时间域。根取自 Thread 不可变的 {@code root_thread_id}，分组后按代表 keyset 过滤并升序输出。查询只读，不产生锁。
   */
  private static final String LIST_PENDING_ENVIRONMENT_WAITS =
      """
      with waiting as (
          select i.id as invocation_id,
                 i.created_at as created_at,
                 t.root_thread_id as root_thread_id,
                 w.required_environment_id as environment_id
          from harness_tool_invocation i
          join harness_thread t on t.id = i.thread_id
          join durable_work w on w.target_type = 'TOOL' and w.target_id = i.id
          where i.status = 'READY'
            and w.required_environment_id is not null
            and w.available_at <= statement_timestamp()
            and (w.lease_until is null or w.lease_until <= statement_timestamp())
            and not exists (
                select 1
                from environment_connection ec
                where ec.environment_id = w.required_environment_id
                  and ec.status = 'READY'
                  and ec.lease_until > statement_timestamp()
            )
      ),
      grouped as (
          select root_thread_id,
                 environment_id,
                 min(created_at) as representative_created_at,
                 (array_agg(invocation_id order by created_at, invocation_id))[1]
                     as representative_invocation_id,
                 count(*) as waiting_count
          from waiting
          group by root_thread_id, environment_id
      )
      select root_thread_id,
             environment_id,
             representative_created_at,
             representative_invocation_id,
             waiting_count
      from grouped
      where (representative_created_at, representative_invocation_id) > (?, ?)
      order by representative_created_at, representative_invocation_id
      limit ?
      """;

  /** 待处理读模型仅因时间推移最早可能改变的权威时刻（见 {@link ToolStore#findNextEnvironmentWaitChange}）。 */
  private static final String NEXT_ENVIRONMENT_WAIT_CHANGE =
      """
      select least(
                 min(case when ec.lease_until > statement_timestamp() then ec.lease_until end),
                 min(case when w.available_at > statement_timestamp() then w.available_at end),
                 min(case when w.lease_until > statement_timestamp() then w.lease_until end)
             ) as next_change_at
      from harness_tool_invocation i
      join durable_work w on w.target_type = 'TOOL' and w.target_id = i.id
      left join environment_connection ec
        on ec.environment_id = w.required_environment_id and ec.status = 'READY'
      where i.status = 'READY'
        and w.required_environment_id is not null
      """;

  private record ToolCallIndexKey(UUID assistantEntryId, int callIndex) {}

  PostgresqlToolStore(PostgresqlHarnessTransaction tx) {
    this.tx = tx;
  }

  @Override
  public Optional<ToolInvocation> find(UUID id) {
    tx.checkOpen();
    return tx.queryOne(
        "select * from harness_tool_invocation where id = ?",
        PostgresqlHarnessRows.TOOL_INVOCATION,
        id);
  }

  @Override
  public Optional<ToolInvocation> lock(UUID id) {
    tx.checkOpen();
    Optional<ToolInvocation> current = tx.tools().find(id);
    if (current.isEmpty()) {
      return Optional.empty();
    }
    tx.requireCanLockTools(current.stream().toList());
    Optional<ToolInvocation> invocation =
        tx.queryOne(
            "select * from harness_tool_invocation where id = ? for update",
            PostgresqlHarnessRows.TOOL_INVOCATION,
            id);
    invocation.ifPresent(tx::lockTool);
    return invocation;
  }

  @Override
  public List<ToolInvocation> loadByAssistantEntry(UUID assistantEntryId) {
    tx.checkOpen();
    return tx.queryList(
        """
        select *
        from harness_tool_invocation
        where assistant_entry_id = ?
        order by call_index
        """,
        PostgresqlHarnessRows.TOOL_INVOCATION,
        assistantEntryId);
  }

  @Override
  public List<ToolInvocation> lockByAssistantEntry(UUID assistantEntryId) {
    tx.checkOpen();
    // Work-stage reentry requires every current sibling to be already locked.
    List<ToolInvocation> current = tx.tools().loadByAssistantEntry(assistantEntryId);
    tx.requireCanLockTools(current);
    List<ToolInvocation> invocations =
        tx.queryList(
            """
            select *
            from harness_tool_invocation
            where assistant_entry_id = ?
            order by call_index
            for update
            """,
            PostgresqlHarnessRows.TOOL_INVOCATION,
            assistantEntryId);
    for (ToolInvocation invocation : invocations) {
      tx.lockTool(invocation);
    }
    return invocations;
  }

  @Override
  public List<PendingToolInvocationRow> listPending(
      Instant afterCreatedAt, UUID afterId, int limit) {
    tx.checkOpen();
    Objects.requireNonNull(afterCreatedAt, "afterCreatedAt");
    Objects.requireNonNull(afterId, "afterId");
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    return tx.queryList(
        """
        select t.*, t.thread_id as pending_thread_id, th.session_id as pending_session_id
        from harness_tool_invocation t
        join harness_thread th on th.id = t.thread_id
        where t.status in ('WAITING_APPROVAL', 'WAITING_INPUT')
          and (t.created_at, t.id) > (?, ?)
        order by t.created_at, t.id
        limit ?
        """,
        PostgresqlHarnessRows.PENDING_TOOL_INVOCATION,
        PostgresqlHarnessRows.timestamp(afterCreatedAt),
        afterId,
        limit);
  }

  @Override
  public List<PendingEnvironmentWaitRow> listPendingEnvironmentWaits(
      Instant afterRepresentativeCreatedAt, UUID afterRepresentativeInvocationId, int limit) {
    tx.checkOpen();
    Objects.requireNonNull(afterRepresentativeCreatedAt, "afterRepresentativeCreatedAt");
    Objects.requireNonNull(afterRepresentativeInvocationId, "afterRepresentativeInvocationId");
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    return tx.queryList(
        LIST_PENDING_ENVIRONMENT_WAITS,
        PostgresqlHarnessRows.PENDING_ENVIRONMENT_WAIT,
        PostgresqlHarnessRows.timestamp(afterRepresentativeCreatedAt),
        afterRepresentativeInvocationId,
        limit);
  }

  @Override
  public List<EnvironmentToolWaitRow> listEnvironmentWaits(Collection<UUID> invocationIds) {
    tx.checkOpen();
    Objects.requireNonNull(invocationIds, "invocationIds");
    List<UUID> ids = List.copyOf(invocationIds);
    if (ids.isEmpty()) {
      return List.of();
    }
    return tx.queryList(
        """
        select i.id as invocation_id,
               w.required_environment_id,
               e.name as environment_name,
               case when i.status = 'READY' and w.required_environment_id is not null then
                 least(
                   (select min(ec.lease_until)
                    from environment_connection ec
                    where ec.environment_id = w.required_environment_id
                      and ec.status = 'READY'
                      and ec.lease_until > statement_timestamp()),
                   case when w.available_at > statement_timestamp() then w.available_at end,
                   case when w.lease_until > statement_timestamp() then w.lease_until end
                 )
               end as freshness_at,
               i.status = 'READY'
                 and w.target_id is not null
                 and w.required_environment_id is not null
                 and w.available_at <= statement_timestamp()
                 and (w.lease_until is null or w.lease_until <= statement_timestamp())
                 and not exists (
                     select 1
                     from environment_connection ec
                     where ec.environment_id = w.required_environment_id
                       and ec.status = 'READY'
                       and ec.lease_until > statement_timestamp()
                 ) as waiting_for_environment
        from harness_tool_invocation i
        left join durable_work w on w.target_type = 'TOOL' and w.target_id = i.id
        left join environment e on e.id = w.required_environment_id
        where i.id in (%s)
        """
            .formatted(PostgresqlHarnessTransaction.placeholders(ids.size())),
        PostgresqlHarnessRows.ENVIRONMENT_TOOL_WAIT,
        ids.toArray());
  }

  @Override
  public Optional<Instant> findNextEnvironmentWaitChange() {
    tx.checkOpen();
    Timestamp nextChangeAt = tx.queryForObject(NEXT_ENVIRONMENT_WAIT_CHANGE, Timestamp.class);
    return Optional.ofNullable(nextChangeAt).map(Timestamp::toInstant);
  }

  @Override
  public void insert(List<ToolInvocation> invocations) {
    tx.checkOpen();
    List<ToolInvocation> copied =
        List.copyOf(invocations).stream()
            .sorted(PostgresqlHarnessTransaction.TOOL_LOCK_ORDER)
            .toList();
    Set<UUID> ids = new HashSet<>();
    Set<ToolCallIndexKey> callIndexes = new HashSet<>();
    for (ToolInvocation invocation : copied) {
      PostgresqlHarnessRows.requireMillisecondPrecision(invocation.createdAt());
      PostgresqlHarnessRows.requireMillisecondPrecision(invocation.updatedAt());
      if (!ids.add(invocation.id())) {
        throw new IllegalArgumentException("duplicate tool invocation id " + invocation.id());
      }
      if (!callIndexes.add(
          new ToolCallIndexKey(invocation.assistantEntryId(), invocation.callIndex()))) {
        throw new IllegalArgumentException(
            "duplicate tool invocation callIndex "
                + invocation.callIndex()
                + " on assistant entry "
                + invocation.assistantEntryId());
      }
      if (tx.tools().find(invocation.id()).isPresent()) {
        throw new IllegalArgumentException("duplicate tool invocation id " + invocation.id());
      }
      if ((invocation.status() != ToolInvocationStatus.READY
              && invocation.status() != ToolInvocationStatus.FAILED)
          || invocation.attempt() != 0
          || invocation.approval() != null) {
        throw new IllegalArgumentException(
            "new tool invocations must be READY or unattached FAILED with attempt 0 and no approval");
      }
      requireUniqueToolCallIndex(invocation);
      requireValidToolReferences(invocation);
    }
    tx.requireCanLockTools(copied);
    for (ToolInvocation invocation : copied) {
      // 仅等待态需要交互失效；本原语按上方不变量只创建 READY/FAILED，因此插入永不发布交互失效信号。
      tx.updateJdbc(
          """
          insert into harness_tool_invocation (
              id, thread_id, turn_start_entry_id, assistant_entry_id, call_index, call, binding, status, attempt,
              approval, result, effects, error, created_at, updated_at, input_receipt
          ) values (
              ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, ?, cast(? as jsonb),
              cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?, ?, cast(? as jsonb)
          )
          """,
          invocation.id(),
          invocation.threadId(),
          invocation.turnStartEntryId(),
          invocation.assistantEntryId(),
          invocation.callIndex(),
          PostgresqlHarnessRows.JSON.write(invocation.call(), "toolCall"),
          encodeToolBinding(invocation),
          invocation.status().name(),
          invocation.attempt(),
          encodeToolApproval(invocation),
          encodeToolResult(invocation),
          encodeToolEffects(invocation),
          encodeToolError(invocation),
          PostgresqlHarnessRows.timestamp(invocation.createdAt()),
          PostgresqlHarnessRows.timestamp(invocation.updatedAt()),
          encodeToolInputReceipt(invocation));
      tx.lockTool(invocation);
    }
  }

  @Override
  public void update(List<ToolInvocation> invocations) {
    tx.checkOpen();
    List<ToolInvocation> copied =
        List.copyOf(invocations).stream()
            .sorted(PostgresqlHarnessTransaction.TOOL_LOCK_ORDER)
            .toList();
    Set<UUID> ids = new HashSet<>();
    Set<UUID> interactionIds = new LinkedHashSet<>();
    for (ToolInvocation invocation : copied) {
      PostgresqlHarnessRows.requireMillisecondPrecision(invocation.createdAt());
      PostgresqlHarnessRows.requireMillisecondPrecision(invocation.updatedAt());
      if (!ids.add(invocation.id())) {
        throw new IllegalArgumentException("duplicate tool invocation id " + invocation.id());
      }
      tx.requireLocked(LockKey.tool(invocation.id()));
      ToolInvocation stored =
          tx.tools()
              .find(invocation.id())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "tool invocation " + invocation.id() + " does not exist"));
      ToolInvocation.validateTransition(stored, invocation);
      // 写前或写后任一状态为等待态：等待集合发生变化，按真实执行根发布交互失效。
      if (PostgresqlHarnessTransaction.isWaitingInteraction(stored.status())
          || PostgresqlHarnessTransaction.isWaitingInteraction(invocation.status())) {
        interactionIds.add(invocation.id());
      }
    }
    for (ToolInvocation invocation : copied) {
      int updated =
          tx.updateJdbc(
              """
              update harness_tool_invocation
              set status = ?,
                  attempt = ?,
                  approval = cast(? as jsonb),
                  result = cast(? as jsonb),
                  effects = cast(? as jsonb),
                  error = cast(? as jsonb),
                  updated_at = ?,
                  input_receipt = cast(? as jsonb)
              where id = ?
              """,
              invocation.status().name(),
              invocation.attempt(),
              encodeToolApproval(invocation),
              encodeToolResult(invocation),
              encodeToolEffects(invocation),
              encodeToolError(invocation),
              PostgresqlHarnessRows.timestamp(invocation.updatedAt()),
              encodeToolInputReceipt(invocation),
              invocation.id());
      tx.requireSingleUpdate(updated, "tool invocation", invocation.id());
    }
    for (UUID interactionId : interactionIds) {
      tx.notifyToolInteraction(tx.executionRootOfToolInvocation(interactionId));
    }
  }

  @Override
  public int deleteByIds(List<UUID> toolInvocationIds) {
    tx.checkOpen();
    List<UUID> copied =
        List.copyOf(toolInvocationIds).stream().sorted(UuidOrder.COMPARATOR).toList();
    for (int i = 1; i < copied.size(); i++) {
      if (copied.get(i).equals(copied.get(i - 1))) {
        throw new IllegalArgumentException(
            "duplicate tool invocation id " + copied.get(i) + " must not be deleted twice");
      }
    }
    // 删除等待调用前先沿不可变祖先链取得真实执行根：事实一旦删除便无法再解析根身份（不做删除后回寻）。
    List<UUID> interactionRoots = new ArrayList<>();
    for (UUID toolInvocationId : copied) {
      ToolInvocation stored =
          tx.tools()
              .find(toolInvocationId)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "tool invocation " + toolInvocationId + " does not exist"));
      tx.requireLocked(LockKey.tool(toolInvocationId));
      if (PostgresqlHarnessTransaction.isWaitingInteraction(stored.status())) {
        interactionRoots.add(tx.executionRootOfToolInvocation(toolInvocationId));
      }
    }
    if (copied.isEmpty()) {
      return 0;
    }
    for (UUID toolInvocationId : copied) {
      int deleted =
          tx.updateJdbc("delete from harness_tool_invocation where id = ?", toolInvocationId);
      tx.requireSingleUpdate(deleted, "tool invocation", toolInvocationId);
    }
    for (UUID interactionRoot : interactionRoots) {
      tx.notifyToolInteraction(interactionRoot);
    }
    return copied.size();
  }

  private void requireUniqueToolCallIndex(ToolInvocation invocation) {
    Boolean exists =
        tx.queryForObject(
            """
            select exists (
                select 1
                from harness_tool_invocation
                where assistant_entry_id = ? and call_index = ?
            )
            """,
            Boolean.class,
            invocation.assistantEntryId(),
            invocation.callIndex());
    if (Boolean.TRUE.equals(exists)) {
      throw new IllegalArgumentException(
          "tool invocation callIndex "
              + invocation.callIndex()
              + " already used on assistant entry "
              + invocation.assistantEntryId());
    }
  }

  private void requireValidToolReferences(ToolInvocation invocation) {
    ThreadState thread =
        tx.threads()
            .find(invocation.threadId())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "thread " + invocation.threadId() + " does not exist"));
    if (invocation.binding() == null && invocation.status() != ToolInvocationStatus.FAILED) {
      throw new IllegalArgumentException("unbound tool invocations must be terminal FAILED slots");
    }
    Entry turnStart = tx.requireExistingEntry(invocation.turnStartEntryId());
    if (turnStart.payload().type() != EntryType.TURN_START
        || !(turnStart.payload() instanceof TurnStartPayload start)) {
      throw new IllegalArgumentException("turnStartEntryId must reference a TURN_START entry");
    }
    if (!start.ownerThreadId().equals(thread.id())
        || !turnStart.sessionId().equals(thread.sessionId())) {
      throw new IllegalArgumentException(
          "turn start must belong to the tool invocation thread and session");
    }
    Entry assistant = tx.requireExistingEntry(invocation.assistantEntryId());
    if (!isAssistantEntry(assistant)) {
      throw new IllegalArgumentException(
          "assistantEntryId must reference an assistant MESSAGE entry");
    }
    if (!assistant.sessionId().equals(thread.sessionId())) {
      throw new IllegalArgumentException(
          "assistantEntryId must belong to the tool invocation thread session");
    }
    EntryPath assistantPath = tx.entries().loadPath(assistant.id());
    if (assistantPath.openTurnStart().map(Entry::id).orElse(null) == null
        || !assistantPath.openTurnStart().orElseThrow().id().equals(turnStart.id())) {
      throw new IllegalArgumentException(
          "assistantEntryId must be on the branch opened by turnStartEntryId");
    }
    requireMatchingAssistantToolCall(invocation, assistant);
  }

  private static void requireMatchingAssistantToolCall(ToolInvocation invocation, Entry assistant) {
    MessagePayload message = (MessagePayload) assistant.payload();
    List<ToolCallMessageContent> calls = new ArrayList<>();
    for (AgentMessageContent content : message.message().contents()) {
      if (content instanceof ToolCallMessageContent call) {
        calls.add(call);
      }
    }
    if (invocation.callIndex() >= calls.size()) {
      throw new IllegalArgumentException(
          "tool callIndex " + invocation.callIndex() + " exceeds the assistant tool calls");
    }
    ToolCallMessageContent call = calls.get(invocation.callIndex());
    ToolCall requestCall = invocation.call();
    String expectedRendererKey =
        invocation.binding() == null
            ? HistoryPayloadMapper.UNBOUND_RENDERER_KEY
            : invocation.binding().descriptor().rendererKey();
    if (!call.toolCallId().equals(requestCall.id())
        || !call.toolName().equals(requestCall.toolName())
        || !call.rendererKey().equals(expectedRendererKey)
        || !call.argumentsJson().equals(requestCall.argumentsJson())) {
      throw new IllegalArgumentException(
          "tool call/binding must exactly match the assistant tool call at the same callIndex");
    }
  }

  private static boolean isAssistantEntry(Entry entry) {
    return entry.payload().type() == EntryType.MESSAGE
        && entry.payload() instanceof MessagePayload message
        && message.message().role() == AgentMessageRole.ASSISTANT;
  }

  private static String encodeToolApproval(ToolInvocation invocation) {
    return invocation.approval() == null
        ? null
        : PostgresqlHarnessRows.TOOL_APPROVALS.encode(invocation.approval());
  }

  private static String encodeToolBinding(ToolInvocation invocation) {
    return invocation.binding() == null
        ? null
        : PostgresqlHarnessRows.TOOL_BINDINGS.encode(invocation.binding());
  }

  private static String encodeToolResult(ToolInvocation invocation) {
    return invocation.result() == null ? null : ToolResultJsonCodec.encode(invocation.result());
  }

  private static String encodeToolEffects(ToolInvocation invocation) {
    return PostgresqlHarnessRows.TOOL_EFFECTS.encode(invocation.effects());
  }

  private static String encodeToolInputReceipt(ToolInvocation invocation) {
    return invocation.inputReceipt() == null
        ? null
        : PostgresqlHarnessRows.JSON.write(invocation.inputReceipt(), "toolInputReceipt");
  }

  private static String encodeToolError(ToolInvocation invocation) {
    return invocation.error() == null
        ? null
        : PostgresqlHarnessRows.JSON.write(invocation.error(), "toolInvocationError");
  }
}
