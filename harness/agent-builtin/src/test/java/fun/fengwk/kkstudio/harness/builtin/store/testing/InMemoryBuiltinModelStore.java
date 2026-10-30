package fun.fengwk.kkstudio.harness.builtin.store.testing;

import fun.fengwk.kkstudio.harness.builtin.history.ModelAttemptMaterialization;
import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessTransaction;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Builtin 私有模型视图的内存实现：与 Runtime 事务共享同一 driver row 快照、数据库时钟与锁阶梯。
 *
 * <p>Model 行与 group 成员冷却都存放在 {@link InMemoryHarnessTransaction} 的 driver row / attribute
 * 区域，因此不持有第二份状态，rollback 与按 Thread 深删除与核心完全一致。
 *
 * <p>Builtin-specific 校验由本类实现：新 ModelInvocation 只能 READY / attempt=0 插入，保持 {@code (thread,
 * turnStartEntryId)} 唯一、requestHeadEntryId 等于 Thread 当前 head、turnStartEntryId 位于 requestHead 的
 * EntryPath 且 owner 匹配；resultEntryId 必须是同一 turn 的 ASSISTANT / ASSISTANT_ERROR / ASSISTANT_ABORTED
 * descendant 且全局唯一； update 走 ModelInvocation transition validation，delete 拒绝仍有 tool invocation
 * children 的模型行。核心 Store 不实现这些约束， 只提供 driver row 快照与 owner 关联。
 */
final class InMemoryBuiltinModelStore {

  /** group 成员冷却身份；typed key 避免字段拼接导致不同成员身份碰撞。 */
  private record GroupCooldownKey(
      String groupName, long groupVersion, String memberModelName, String memberVariant) {}

  private final InMemoryHarnessTransaction tx;

  InMemoryBuiltinModelStore(InMemoryHarnessTransaction tx) {
    this.tx = Objects.requireNonNull(tx, "tx");
  }

  Optional<ModelInvocation> find(UUID id) {
    return tx.findDriverRow(id).map(ModelInvocation.class::cast);
  }

  Optional<ModelInvocation> lock(UUID id) {
    return tx.lockDriverRow(id).map(ModelInvocation.class::cast);
  }

  Optional<ModelInvocation> findByTurn(UUID threadId, UUID turnStartEntryId) {
    for (Object row : tx.driverRows().values()) {
      if (row instanceof ModelInvocation invocation
          && invocation.threadId().equals(threadId)
          && invocation.turnStartEntryId().equals(turnStartEntryId)) {
        return Optional.of(invocation);
      }
    }
    return Optional.empty();
  }

  void insert(ModelInvocation invocation) {
    Objects.requireNonNull(invocation, "invocation");
    HarnessStoreTime.requireMillisecondPrecision(invocation.createdAt());
    HarnessStoreTime.requireMillisecondPrecision(invocation.updatedAt());
    if (tx.findDriverRow(invocation.id()).isPresent()) {
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
    tx.requireThreadLocked(invocation.threadId());
    Entry turnStart =
        tx.entries()
            .find(invocation.turnStartEntryId())
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
    tx.insertDriverRow(invocation.id(), invocation.threadId(), invocation);
  }

  void update(ModelInvocation invocation) {
    Objects.requireNonNull(invocation, "invocation");
    HarnessStoreTime.requireMillisecondPrecision(invocation.createdAt());
    HarnessStoreTime.requireMillisecondPrecision(invocation.updatedAt());
    tx.requireDriverRowLocked(invocation.id());
    ModelInvocation stored =
        tx.findDriverRow(invocation.id())
            .map(ModelInvocation.class::cast)
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
    tx.updateDriverRow(invocation.id(), invocation);
  }

  boolean isCooling(
      String groupName, long groupVersion, String memberModelName, String memberVariant) {
    Instant now = tx.now();
    return tx.driverAttribute(
            new GroupCooldownKey(groupName, groupVersion, memberModelName, memberVariant))
        .map(Instant.class::cast)
        .map(until -> until.isAfter(now))
        .orElse(false);
  }

  void freeze(
      String groupName,
      long groupVersion,
      String memberModelName,
      String memberVariant,
      Instant cooldownUntil) {
    GroupCooldownKey key =
        new GroupCooldownKey(groupName, groupVersion, memberModelName, memberVariant);
    Instant existing = tx.driverAttribute(key).map(Instant.class::cast).orElse(null);
    if (existing == null || existing.isBefore(cooldownUntil)) {
      tx.putDriverAttribute(key, cooldownUntil);
    }
  }

  boolean delete(UUID modelInvocationId) {
    ModelInvocation model =
        tx.findDriverRow(modelInvocationId)
            .map(ModelInvocation.class::cast)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "model invocation " + modelInvocationId + " does not exist"));
    tx.requireDriverRowLocked(modelInvocationId);
    ThreadState thread =
        tx.threads()
            .find(model.threadId())
            .orElseThrow(() -> new IllegalArgumentException("thread does not exist"));
    for (Entry entry : tx.entries().loadBySession(thread.sessionId())) {
      for (ToolInvocation tool : tx.tools().loadByAssistantEntry(entry.id())) {
        if (tool.threadId().equals(model.threadId())
            && tool.turnStartEntryId().equals(model.turnStartEntryId())) {
          throw new IllegalArgumentException(
              "model invocation " + modelInvocationId + " has tool invocation children");
        }
      }
    }
    return tx.deleteDriverRow(modelInvocationId);
  }

  /** 当前快照内已提交的 model 行，按 driver row 的迭代顺序返回。 */
  List<ModelInvocation> rows() {
    List<ModelInvocation> rows = new ArrayList<>();
    for (Object row : tx.driverRows().values()) {
      if (row instanceof ModelInvocation invocation) {
        rows.add(invocation);
      }
    }
    return List.copyOf(rows);
  }

  private void requireValidModelBranch(ModelInvocation invocation) {
    EntryPath requestHeadPath = tx.entries().loadPath(invocation.requestHeadEntryId());
    boolean turnStartOnBasisPath =
        requestHeadPath.entries().stream()
            .anyMatch(entry -> entry.id().equals(invocation.turnStartEntryId()));
    if (!turnStartOnBasisPath) {
      throw new IllegalArgumentException(
          "turnStartEntryId must be on the requestHeadEntryId entry path");
    }
    ThreadState thread =
        tx.threads()
            .find(invocation.threadId())
            .orElseThrow(() -> new IllegalArgumentException("thread does not exist"));
    if (!thread.sessionId().equals(requestHeadPath.head().sessionId())) {
      throw new IllegalArgumentException("thread session must match the requestHead path session");
    }
  }

  private void requireUniqueModelTurn(ModelInvocation invocation) {
    for (Object row : tx.driverRows().values()) {
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
        tx.entries()
            .find(resultEntryId)
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
    EntryPath resultPath = tx.entries().loadPath(resultEntryId);
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
    for (Object row : tx.driverRows().values()) {
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
}
