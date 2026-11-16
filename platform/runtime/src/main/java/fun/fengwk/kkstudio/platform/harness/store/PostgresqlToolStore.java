package fun.fengwk.kkstudio.platform.harness.store;

import fun.fengwk.kkstudio.common.time.MillisecondTime;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.spi.store.EnvironmentToolWaitRow;
import fun.fengwk.kkstudio.harness.spi.store.PendingEnvironmentWaitRow;
import fun.fengwk.kkstudio.harness.spi.store.PendingToolInvocationRow;
import fun.fengwk.kkstudio.harness.spi.store.ToolStore;
import fun.fengwk.kkstudio.harness.spi.store.UuidOrder;
import fun.fengwk.kkstudio.platform.harness.store.PostgresqlHarnessTransaction.LockKey;

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

/**
 * ToolInvocation 聚合的 PostgreSQL 实现。
 *
 * <p>本类只负责锁阶梯、写入顺序与交互失效通知；等待环境的只读投影在 {@link PostgresqlEnvironmentToolWaits}，新调用的引用校验与 JSONB 列编码在
 * {@link PostgresqlToolInvocationValidation}。
 */
final class PostgresqlToolStore implements ToolStore {

  private final PostgresqlHarnessTransaction tx;
  private final PostgresqlEnvironmentToolWaits environmentWaits;
  private final PostgresqlToolInvocationValidation validation;

  private record ToolCallIndexKey(UUID assistantEntryId, int callIndex) {}

  PostgresqlToolStore(PostgresqlHarnessTransaction tx) {
    this.tx = tx;
    this.environmentWaits = new PostgresqlEnvironmentToolWaits(tx);
    this.validation = new PostgresqlToolInvocationValidation(tx);
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
  public List<PendingEnvironmentWaitRow> listEnvironmentWaitCandidates(
      Instant afterRepresentativeCreatedAt, UUID afterRepresentativeInvocationId, int limit) {
    return environmentWaits.listCandidates(
        afterRepresentativeCreatedAt, afterRepresentativeInvocationId, limit);
  }

  @Override
  public List<EnvironmentToolWaitRow> listEnvironmentWaits(Collection<UUID> invocationIds) {
    return environmentWaits.listWaits(invocationIds);
  }

  @Override
  public List<EnvironmentId> listReadyToolEnvironments() {
    return environmentWaits.listReadyEnvironments();
  }

  @Override
  public Optional<Instant> findNextEnvironmentWorkChange() {
    return environmentWaits.findNextWorkChange();
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
      MillisecondTime.requireMillisecondPrecision(invocation.createdAt());
      MillisecondTime.requireMillisecondPrecision(invocation.updatedAt());
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
      boolean allowedStatus =
          invocation.status() == ToolInvocationStatus.READY
              || invocation.status() == ToolInvocationStatus.FAILED;
      if (!allowedStatus || invocation.attempt() != 0 || invocation.approval() != null) {
        throw new IllegalArgumentException(
            "new tool invocations must be READY or FAILED with attempt 0 and no approval");
      }
      validation.requireUniqueToolCallIndex(invocation);
      validation.requireValidToolReferences(invocation);
    }
    tx.requireCanLockTools(copied);
    for (ToolInvocation invocation : copied) {
      // 仅等待态需要交互失效；本原语按上方不变量只创建 READY/FAILED，因此插入永不发布交互失效信号。
      tx.updateJdbc(
          """
          insert into harness_tool_invocation (
              id, thread_id, turn_start_entry_id, assistant_entry_id, call_index, call, binding, status, attempt,
              approval, result, error, created_at, updated_at, input_receipt
          ) values (
              ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, ?, cast(? as jsonb),
              cast(? as jsonb), cast(? as jsonb), ?, ?, cast(? as jsonb)
          )
          """,
          invocation.id(),
          invocation.threadId(),
          invocation.turnStartEntryId(),
          invocation.assistantEntryId(),
          invocation.callIndex(),
          PostgresqlHarnessRows.JSON.write(invocation.call(), "toolCall"),
          PostgresqlToolInvocationValidation.encodeBinding(invocation),
          invocation.status().name(),
          invocation.attempt(),
          PostgresqlToolInvocationValidation.encodeApproval(invocation),
          PostgresqlToolInvocationValidation.encodeResult(invocation),
          PostgresqlToolInvocationValidation.encodeError(invocation),
          PostgresqlHarnessRows.timestamp(invocation.createdAt()),
          PostgresqlHarnessRows.timestamp(invocation.updatedAt()),
          PostgresqlToolInvocationValidation.encodeInputReceipt(invocation));
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
      MillisecondTime.requireMillisecondPrecision(invocation.createdAt());
      MillisecondTime.requireMillisecondPrecision(invocation.updatedAt());
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
                  error = cast(? as jsonb),
                  updated_at = ?,
                  input_receipt = cast(? as jsonb)
              where id = ?
              """,
              invocation.status().name(),
              invocation.attempt(),
              PostgresqlToolInvocationValidation.encodeApproval(invocation),
              PostgresqlToolInvocationValidation.encodeResult(invocation),
              PostgresqlToolInvocationValidation.encodeError(invocation),
              PostgresqlHarnessRows.timestamp(invocation.updatedAt()),
              PostgresqlToolInvocationValidation.encodeInputReceipt(invocation),
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
}
