package fun.fengwk.kkstudio.harness.builtin.store.testing;

import fun.fengwk.kkstudio.harness.builtin.history.ModelAttemptMaterialization;
import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Builtin 测试 Store：组合 runtime 的唯一通用内存 Store，把 ModelInvocation 与 group cooldown 放进核心事务快照。
 *
 * <p>Model 行与属性存放在 runtime 核心事务快照的 driver row 区域，因此与核心 Store 共享同一 copy-on-write 快照、rollback、driver
 * 级锁与按 Thread 深删除语义。它不是生产实现，也不是生产 SPI。
 *
 * <p>Builtin-specific model 校验由本类事务实现：新 ModelInvocation 只能 READY / attempt=0 插入，保持 {@code (thread,
 * turnStartEntryId)} 唯一、requestHeadEntryId 等于 Thread 当前 head、turnStartEntryId 位于 requestHead 的
 * EntryPath 且 owner 匹配；resultEntryId 必须是同一 turn 的 ASSISTANT / ASSISTANT_ERROR / ASSISTANT_ABORTED
 * descendant 且全局唯一；update 走 ModelInvocation transition validation，delete 拒绝仍有 tool invocation
 * children 的模型行。核心 Store 不实现这些约束，只提供 driver row 快照与 owner 关联。
 */
public final class InMemoryBuiltinHarnessStore extends InMemoryHarnessStore
    implements BuiltinStore {

  /** group 成员冷却身份；typed key 避免字段拼接导致不同成员身份碰撞。 */
  private record GroupCooldownKey(
      String groupName, long groupVersion, String memberModelName, String memberVariant) {}

  public InMemoryBuiltinHarnessStore() {
    super();
  }

  /** 播种全局 id 序列，供事务/回滚测试使用。 */
  public InMemoryBuiltinHarnessStore(long initialNextId) {
    super(initialNextId);
  }

  @Override
  protected InMemoryTransaction createTransaction(State state) {
    return new BuiltinTransaction(state);
  }

  @Override
  public BuiltinStore.Transaction in(HarnessStore.Transaction tx) {
    Objects.requireNonNull(tx, "tx");
    if (tx instanceof BuiltinStore.Transaction builtin) {
      return builtin;
    }
    throw new IllegalArgumentException("transaction does not expose BuiltinStore.Transaction");
  }

  private final class BuiltinTransaction extends InMemoryTransaction
      implements BuiltinStore.Transaction {

    private BuiltinTransaction(State state) {
      super(state);
    }

    private Map<UUID, Object> modelRows() {
      return driverRows();
    }

    @Override
    public Optional<ModelInvocation> findModelInvocation(UUID id) {
      return findDriverRow(id).map(ModelInvocation.class::cast);
    }

    @Override
    public Optional<ModelInvocation> lockModelInvocation(UUID id) {
      return lockDriverRow(id).map(ModelInvocation.class::cast);
    }

    @Override
    public Optional<ModelInvocation> findModelInvocationByTurn(
        UUID threadId, UUID turnStartEntryId) {
      for (Object row : driverRows().values()) {
        if (row instanceof ModelInvocation invocation
            && invocation.threadId().equals(threadId)
            && invocation.turnStartEntryId().equals(turnStartEntryId)) {
          return Optional.of(invocation);
        }
      }
      return Optional.empty();
    }

    @Override
    public void insertModelInvocation(ModelInvocation invocation) {
      Objects.requireNonNull(invocation, "invocation");
      requireMillisecondPrecision(invocation.createdAt());
      requireMillisecondPrecision(invocation.updatedAt());
      if (findDriverRow(invocation.id()).isPresent()) {
        throw new IllegalArgumentException("duplicate model invocation id " + invocation.id());
      }
      requireUniqueModelTurn(invocation);
      ThreadState thread =
          findThread(invocation.threadId())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "thread " + invocation.threadId() + " does not exist"));
      requireThreadLocked(invocation.threadId());
      Entry turnStart =
          findEntry(invocation.turnStartEntryId())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "entry " + invocation.turnStartEntryId() + " does not exist"));
      if (!(turnStart.payload() instanceof TurnStartPayload start)) {
        throw new IllegalArgumentException("turnStartEntryId must reference a TURN_START entry");
      }
      if (!start.ownerThreadId().equals(invocation.threadId())) {
        throw new IllegalArgumentException(
            "turn start ownerThreadId must equal the model invocation threadId");
      }
      if (start.contextWindow() == null || start.contextWindow() <= 0) {
        throw new IllegalArgumentException(
            "model invocations require a positive turn start contextWindow");
      }
      if (start.maxOutputTokens() == null || start.maxOutputTokens() <= 0) {
        throw new IllegalArgumentException(
            "model invocations require positive turn start maxOutputTokens");
      }
      if (invocation.status() != ModelInvocationStatus.READY || invocation.attempt() != 0) {
        throw new IllegalArgumentException("new model invocations must be READY with attempt 0");
      }
      if (!thread.headEntryId().equals(invocation.requestHeadEntryId())) {
        throw new IllegalArgumentException(
            "requestHeadEntryId must equal the current thread head entry");
      }
      requireValidModelBranch(invocation);
      requireValidModelResultEntry(invocation);
      insertDriverRow(invocation.id(), invocation.threadId(), invocation);
    }

    private void requireValidModelBranch(ModelInvocation invocation) {
      EntryPath requestHeadPath = loadEntryPath(invocation.requestHeadEntryId());
      boolean turnStartOnBasisPath =
          requestHeadPath.entries().stream()
              .anyMatch(entry -> entry.id().equals(invocation.turnStartEntryId()));
      if (!turnStartOnBasisPath) {
        throw new IllegalArgumentException(
            "turnStartEntryId must be on the requestHeadEntryId entry path");
      }
      ThreadState thread =
          findThread(invocation.threadId())
              .orElseThrow(() -> new IllegalArgumentException("thread does not exist"));
      if (!thread.sessionId().equals(requestHeadPath.head().sessionId())) {
        throw new IllegalArgumentException(
            "thread session must match the requestHead path session");
      }
    }

    private void requireUniqueModelTurn(ModelInvocation invocation) {
      for (Object row : driverRows().values()) {
        if (row instanceof ModelInvocation existing
            && existing.threadId().equals(invocation.threadId())
            && existing.turnStartEntryId().equals(invocation.turnStartEntryId())) {
          throw new IllegalArgumentException(
              "model invocation already exists for thread "
                  + invocation.threadId()
                  + " turn "
                  + invocation.turnStartEntryId());
        }
      }
    }

    private void requireValidModelResultEntry(ModelInvocation invocation) {
      UUID resultEntryId = invocation.resultEntryId();
      if (resultEntryId == null) {
        return;
      }
      Entry result =
          findEntry(resultEntryId)
              .orElseThrow(
                  () -> new IllegalArgumentException("entry " + resultEntryId + " does not exist"));
      if (!isModelResultEntry(result)) {
        throw new IllegalArgumentException(
            "model resultEntryId must reference an assistant, assistant-error or"
                + " assistant-aborted entry");
      }
      if (resultEntryId.equals(invocation.requestHeadEntryId())) {
        throw new IllegalArgumentException(
            "model result entry must be a strict descendant of the requestHead head entry");
      }
      EntryPath resultPath = loadEntryPath(resultEntryId);
      boolean onBasisPath =
          resultPath.entries().stream()
              .anyMatch(entry -> entry.id().equals(invocation.requestHeadEntryId()));
      boolean sameTurnStart =
          resultPath.entries().stream()
              .anyMatch(entry -> entry.id().equals(invocation.turnStartEntryId()));
      if (!onBasisPath || !sameTurnStart) {
        throw new IllegalArgumentException(
            "model result entry must be on the requestHead path and in the same turn");
      }
      for (Object row : driverRows().values()) {
        if (row instanceof ModelInvocation other
            && !other.id().equals(invocation.id())
            && Objects.equals(other.resultEntryId(), resultEntryId)) {
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
      Objects.requireNonNull(invocation, "invocation");
      requireMillisecondPrecision(invocation.createdAt());
      requireMillisecondPrecision(invocation.updatedAt());
      requireDriverRowLocked(invocation.id());
      ModelInvocation stored =
          findDriverRow(invocation.id())
              .map(ModelInvocation.class::cast)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "model invocation " + invocation.id() + " does not exist"));
      ModelInvocation.validateTransition(stored, invocation);
      requireValidModelResultEntry(invocation);
      if (stored.resultEntryId() == null && invocation.resultEntryId() != null) {
        ModelAttemptMaterialization.validate(
            stored, invocation, loadEntryPath(invocation.resultEntryId()));
      }
      updateDriverRow(invocation.id(), invocation);
    }

    @Override
    public boolean isGroupMemberCooling(
        String groupName, long groupVersion, String memberModelName, String memberVariant) {
      Instant now = now();
      return driverAttribute(
              new GroupCooldownKey(groupName, groupVersion, memberModelName, memberVariant))
          .map(Instant.class::cast)
          .map(until -> until.isAfter(now))
          .orElse(false);
    }

    @Override
    public void freezeGroupMember(
        String groupName,
        long groupVersion,
        String memberModelName,
        String memberVariant,
        Instant cooldownUntil) {
      GroupCooldownKey key =
          new GroupCooldownKey(groupName, groupVersion, memberModelName, memberVariant);
      Instant existing = driverAttribute(key).map(Instant.class::cast).orElse(null);
      if (existing == null || existing.isBefore(cooldownUntil)) {
        putDriverAttribute(key, cooldownUntil);
      }
    }

    @Override
    public boolean deleteModelInvocation(UUID modelInvocationId) {
      ModelInvocation model =
          findDriverRow(modelInvocationId)
              .map(ModelInvocation.class::cast)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "model invocation " + modelInvocationId + " does not exist"));
      requireDriverRowLocked(modelInvocationId);
      ThreadState thread =
          findThread(model.threadId())
              .orElseThrow(() -> new IllegalArgumentException("thread does not exist"));
      for (Entry entry : loadEntriesBySessionId(thread.sessionId())) {
        for (ToolInvocation tool : loadToolInvocationsByAssistantEntryId(entry.id())) {
          if (tool.threadId().equals(model.threadId())
              && tool.turnStartEntryId().equals(model.turnStartEntryId())) {
            throw new IllegalArgumentException(
                "model invocation " + modelInvocationId + " has tool invocation children");
          }
        }
      }
      return deleteDriverRow(modelInvocationId);
    }
  }

  /** 仅供测试断言使用：当前已提交的 model 行。 */
  public List<ModelInvocation> committedModelInvocations() {
    return super.transaction(
        tx ->
            ((BuiltinTransaction) tx)
                .modelRows().values().stream()
                    .filter(ModelInvocation.class::isInstance)
                    .map(ModelInvocation.class::cast)
                    .toList());
  }
}
