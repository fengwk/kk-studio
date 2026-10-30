package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
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
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.store.PendingEnvironmentWaitRow;
import fun.fengwk.kkstudio.harness.runtime.store.PendingToolInvocationRow;
import fun.fengwk.kkstudio.harness.runtime.store.ToolStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * ToolInvocation 聚合原语：新行只能以 READY（或用于 sibling 静态拒绝的 unattached FAILED）、attempt=0、approval=null 插入。
 *
 * <p>模拟 {@code (assistantEntryId, callIndex)} 唯一、durable {@code call} 与 Assistant 中按 callIndex 提取的
 * ToolCall 精确一致、 turn / branch 引用完整，以及结果 Entry 与 invocation 的一对一引用。行不持有结果引用，Outcome 由 batch apply
 * 追加为 ToolResult MESSAGE Entry 后删除行。
 */
final class InMemoryToolStore implements ToolStore {

  private final InMemoryHarnessTransaction tx;
  private final InMemoryState state;

  InMemoryToolStore(InMemoryHarnessTransaction tx) {
    this.tx = tx;
    this.state = tx.state();
  }

  @Override
  public Optional<ToolInvocation> find(UUID id) {
    tx.checkOpen();
    return Optional.ofNullable(state.toolInvocations.get(id));
  }

  @Override
  public Optional<ToolInvocation> lock(UUID id) {
    tx.checkOpen();
    ToolInvocation invocation = state.toolInvocations.get(id);
    if (invocation != null) {
      tx.locks().lockTool(invocation);
    }
    return Optional.ofNullable(invocation);
  }

  @Override
  public List<ToolInvocation> loadByAssistantEntry(UUID assistantEntryId) {
    tx.checkOpen();
    return state.toolInvocations.values().stream()
        .filter(invocation -> invocation.assistantEntryId().equals(assistantEntryId))
        .sorted(Comparator.comparingInt(ToolInvocation::callIndex))
        .toList();
  }

  @Override
  public List<ToolInvocation> lockByAssistantEntry(UUID assistantEntryId) {
    tx.checkOpen();
    InMemoryHarnessLocks locks = tx.locks();
    locks.requireCanLockRank(InMemoryHarnessLocks.LockRank.TOOL);
    List<ToolInvocation> invocations = loadByAssistantEntry(assistantEntryId);
    locks.requireCanLockTools(invocations);
    for (ToolInvocation invocation : invocations) {
      locks.lockTool(invocation);
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
    List<PendingToolInvocationRow> rows = new ArrayList<>();
    for (ToolInvocation invocation : state.toolInvocations.values()) {
      if (invocation.status() != ToolInvocationStatus.WAITING_INPUT
          && invocation.status() != ToolInvocationStatus.WAITING_APPROVAL) {
        continue;
      }
      if (!isAfterCursor(invocation, afterCreatedAt, afterId)) {
        continue;
      }
      ThreadState thread = state.threads.get(invocation.threadId());
      if (thread == null) {
        throw new IllegalStateException(
            "pending tool invocation " + invocation.id() + " lost its owning thread");
      }
      rows.add(new PendingToolInvocationRow(invocation, thread.id(), thread.sessionId()));
    }
    rows.sort(
        Comparator.comparing((PendingToolInvocationRow row) -> row.invocation().createdAt())
            .thenComparing(row -> row.invocation().id(), UuidOrder.COMPARATOR));
    return List.copyOf(rows.size() > limit ? rows.subList(0, limit) : rows);
  }

  private static boolean isAfterCursor(
      ToolInvocation invocation, Instant afterCreatedAt, UUID afterId) {
    int timeComparison = invocation.createdAt().compareTo(afterCreatedAt);
    if (timeComparison != 0) {
      return timeComparison > 0;
    }
    return UuidOrder.COMPARATOR.compare(invocation.id(), afterId) > 0;
  }

  @Override
  public List<PendingEnvironmentWaitRow> listPendingEnvironmentWaits(
      Instant afterRepresentativeCreatedAt, UUID afterRepresentativeInvocationId, int limit) {
    tx.checkOpen();
    Instant now = tx.now();
    Objects.requireNonNull(afterRepresentativeCreatedAt, "afterRepresentativeCreatedAt");
    Objects.requireNonNull(afterRepresentativeInvocationId, "afterRepresentativeInvocationId");
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    Map<EnvironmentWaitGroupKey, EnvironmentWaitAccumulator> groups = new HashMap<>();
    for (ToolInvocation invocation : state.toolInvocations.values()) {
      if (invocation.status() != ToolInvocationStatus.READY) {
        continue;
      }
      Work work = state.works.get(new WorkTarget(WorkTargetType.TOOL, invocation.id()));
      if (work == null) {
        continue;
      }
      EnvironmentId environmentId = work.requiredEnvironmentId();
      if (environmentId == null || work.availableAt().isAfter(now)) {
        continue;
      }
      if (work.leaseToken() != null && work.leaseUntil().isAfter(now)) {
        continue;
      }
      if (tx.hasReadyEnvironmentLease(environmentId, now)) {
        continue;
      }
      ThreadState thread = state.threads.get(invocation.threadId());
      if (thread == null) {
        throw new IllegalStateException(
            "pending environment tool invocation " + invocation.id() + " lost its owning thread");
      }
      UUID rootThreadId = thread.rootThreadId();
      groups
          .computeIfAbsent(
              new EnvironmentWaitGroupKey(rootThreadId, environmentId),
              key -> new EnvironmentWaitAccumulator())
          .add(invocation.createdAt(), invocation.id());
    }
    List<PendingEnvironmentWaitRow> rows = new ArrayList<>(groups.size());
    for (Map.Entry<EnvironmentWaitGroupKey, EnvironmentWaitAccumulator> group : groups.entrySet()) {
      PendingEnvironmentWaitRow row = group.getValue().toRow(group.getKey());
      if (isAfterRepresentativeCursor(
          row, afterRepresentativeCreatedAt, afterRepresentativeInvocationId)) {
        rows.add(row);
      }
    }
    rows.sort(
        Comparator.comparing(PendingEnvironmentWaitRow::representativeCreatedAt)
            .thenComparing(
                PendingEnvironmentWaitRow::representativeInvocationId, UuidOrder.COMPARATOR));
    return List.copyOf(rows.size() > limit ? rows.subList(0, limit) : rows);
  }

  @Override
  public List<EnvironmentToolWaitRow> listEnvironmentWaits(Collection<UUID> invocationIds) {
    tx.checkOpen();
    Instant now = tx.now();
    Objects.requireNonNull(invocationIds, "invocationIds");
    if (invocationIds.isEmpty()) {
      return List.of();
    }
    List<EnvironmentToolWaitRow> rows = new ArrayList<>(invocationIds.size());
    for (UUID invocationId : invocationIds) {
      Objects.requireNonNull(invocationId, "invocationId");
      ToolInvocation invocation = state.toolInvocations.get(invocationId);
      if (invocation == null) {
        continue;
      }
      Work work = state.works.get(new WorkTarget(WorkTargetType.TOOL, invocationId));
      EnvironmentId environmentId = work == null ? null : work.requiredEnvironmentId();
      Instant freshnessAt = null;
      if (invocation.status() == ToolInvocationStatus.READY && environmentId != null) {
        freshnessAt =
            earliestOf(laterThan(work.availableAt(), now), laterThan(work.leaseUntil(), now));
      }
      // 已有内存 READY 环境谓词只有 boolean、没有 deadline；仍真实计算本调用的 Work 时间边界。
      rows.add(
          new EnvironmentToolWaitRow(
              invocationId,
              environmentId,
              isWaitingForEnvironment(invocation, work, now),
              tx.environmentName(environmentId),
              freshnessAt));
    }
    return List.copyOf(rows);
  }

  @Override
  public Optional<Instant> findNextEnvironmentWaitChange() {
    tx.checkOpen();
    Instant now = tx.now();
    Instant earliest = null;
    for (ToolInvocation invocation : state.toolInvocations.values()) {
      if (invocation.status() != ToolInvocationStatus.READY) {
        continue;
      }
      Work work = state.works.get(new WorkTarget(WorkTargetType.TOOL, invocation.id()));
      if (work == null || work.requiredEnvironmentId() == null) {
        continue;
      }
      earliest = earliestOf(earliest, laterThan(work.availableAt(), now));
      earliest =
          earliestOf(
              earliest, laterThan(work.leaseToken() != null ? work.leaseUntil() : null, now));
    }
    // 内存实现的 READY 环境租约只是布尔谓词，没有 deadline，因此无法在这里表达「环境租约自然到期」这一类变更；
    // 生产实现（数据库）用 environment_connection.lease_until 覆盖它。
    return Optional.ofNullable(earliest);
  }

  private boolean isWaitingForEnvironment(ToolInvocation invocation, Work work, Instant now) {
    if (invocation.status() != ToolInvocationStatus.READY || work == null) {
      return false;
    }
    EnvironmentId environmentId = work.requiredEnvironmentId();
    if (environmentId == null || work.availableAt().isAfter(now)) {
      return false;
    }
    if (work.leaseToken() != null && work.leaseUntil().isAfter(now)) {
      return false;
    }
    return !tx.hasReadyEnvironmentLease(environmentId, now);
  }

  private static Instant laterThan(Instant candidate, Instant now) {
    return candidate != null && candidate.isAfter(now) ? candidate : null;
  }

  private static Instant earliestOf(Instant current, Instant candidate) {
    if (candidate == null) {
      return current;
    }
    return current == null || candidate.isBefore(current) ? candidate : current;
  }

  @Override
  public void insert(List<ToolInvocation> invocations) {
    tx.checkOpen();
    List<ToolInvocation> copied =
        List.copyOf(invocations).stream().sorted(InMemoryHarnessLocks.TOOL_LOCK_ORDER).toList();
    Set<UUID> ids = new HashSet<>();
    Set<ToolCallIndexKey> callIndexes = new HashSet<>();
    for (ToolInvocation invocation : copied) {
      HarnessStoreTime.requireMillisecondPrecision(invocation.createdAt());
      HarnessStoreTime.requireMillisecondPrecision(invocation.updatedAt());
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
      InMemoryHarnessTransaction.requireAbsent(
          state.toolInvocations, invocation.id(), "tool invocation");
      // sibling 静态拒绝允许 unattached FAILED。初始均为 attempt=0、approval=null，
      // effects 由 record 不变量保证为空。
      if ((invocation.status() != ToolInvocationStatus.READY
              && invocation.status() != ToolInvocationStatus.FAILED)
          || invocation.attempt() != 0
          || invocation.approval() != null) {
        throw new IllegalArgumentException(
            "new tool invocations must be READY or unattached FAILED with attempt 0 and no approval");
      }
      requireUniqueToolOrdinal(invocation);
      requireValidToolReferences(invocation);
    }
    InMemoryHarnessLocks locks = tx.locks();
    locks.requireCanLockTools(copied);
    for (ToolInvocation invocation : copied) {
      state.toolInvocations.put(invocation.id(), invocation);
      locks.lockTool(invocation);
    }
  }

  private void requireUniqueToolOrdinal(ToolInvocation invocation) {
    for (ToolInvocation existing : state.toolInvocations.values()) {
      if (existing.assistantEntryId().equals(invocation.assistantEntryId())
          && existing.callIndex() == invocation.callIndex()) {
        throw new IllegalArgumentException(
            "tool invocation callIndex "
                + invocation.callIndex()
                + " already used on assistant entry "
                + invocation.assistantEntryId());
      }
    }
  }

  private void requireValidToolReferences(ToolInvocation invocation) {
    ThreadState thread = state.threads.get(invocation.threadId());
    if (thread == null) {
      throw new IllegalArgumentException("thread " + invocation.threadId() + " does not exist");
    }
    if (invocation.binding() == null && invocation.status() != ToolInvocationStatus.FAILED) {
      throw new IllegalArgumentException("unbound tool invocations must be terminal FAILED slots");
    }
    Entry turnStart = tx.requireExistingEntry(invocation.turnStartEntryId());
    if (!(turnStart.payload() instanceof TurnStartPayload start)) {
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

  /**
   * ToolInvocation 的 durable call/binding 必须与 Assistant MESSAGE 中按 callIndex 提取的 ToolCall（id /
   * toolName / rendererKey / argumentsJson）精确一致。
   */
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

  @Override
  public void update(List<ToolInvocation> invocations) {
    tx.checkOpen();
    List<ToolInvocation> copied =
        List.copyOf(invocations).stream().sorted(InMemoryHarnessLocks.TOOL_LOCK_ORDER).toList();
    Set<UUID> ids = new HashSet<>();
    for (ToolInvocation invocation : copied) {
      HarnessStoreTime.requireMillisecondPrecision(invocation.createdAt());
      HarnessStoreTime.requireMillisecondPrecision(invocation.updatedAt());
      if (!ids.add(invocation.id())) {
        throw new IllegalArgumentException("duplicate tool invocation id " + invocation.id());
      }
      tx.locks().requireLocked(InMemoryHarnessLocks.LockKey.tool(invocation.id()));
      ToolInvocation stored = state.toolInvocations.get(invocation.id());
      if (stored == null) {
        throw new IllegalArgumentException(
            "tool invocation " + invocation.id() + " does not exist");
      }
      ToolInvocation.validateTransition(stored, invocation);
    }
    for (ToolInvocation invocation : copied) {
      state.toolInvocations.put(invocation.id(), invocation);
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
    for (UUID toolInvocationId : copied) {
      if (!state.toolInvocations.containsKey(toolInvocationId)) {
        throw new IllegalArgumentException(
            "tool invocation " + toolInvocationId + " does not exist");
      }
      tx.locks().requireLocked(InMemoryHarnessLocks.LockKey.tool(toolInvocationId));
    }
    int deleted = 0;
    for (UUID toolInvocationId : copied) {
      state.toolInvocations.remove(toolInvocationId);
      deleted++;
    }
    return copied.size();
  }

  /** 环境等待分组键：{@code (真实执行根, 冻结的所需环境)}。 */
  private record EnvironmentWaitGroupKey(UUID rootThreadId, EnvironmentId environmentId) {}

  /** 组内累积：最早的 {@code (createdAt, id)} 代表与调用数。 */
  private static final class EnvironmentWaitAccumulator {
    private Instant representativeCreatedAt;
    private UUID representativeInvocationId;
    private int count;

    void add(Instant createdAt, UUID invocationId) {
      count++;
      if (representativeCreatedAt == null
          || createdAt.isBefore(representativeCreatedAt)
          || (createdAt.equals(representativeCreatedAt)
              && UuidOrder.COMPARATOR.compare(invocationId, representativeInvocationId) < 0)) {
        representativeCreatedAt = createdAt;
        representativeInvocationId = invocationId;
      }
    }

    PendingEnvironmentWaitRow toRow(EnvironmentWaitGroupKey key) {
      return new PendingEnvironmentWaitRow(
          key.rootThreadId(),
          key.environmentId(),
          representativeCreatedAt,
          representativeInvocationId,
          count);
    }
  }

  private static boolean isAfterRepresentativeCursor(
      PendingEnvironmentWaitRow row, Instant afterCreatedAt, UUID afterId) {
    int timeComparison = row.representativeCreatedAt().compareTo(afterCreatedAt);
    if (timeComparison != 0) {
      return timeComparison > 0;
    }
    return UuidOrder.COMPARATOR.compare(row.representativeInvocationId(), afterId) > 0;
  }

  private record ToolCallIndexKey(UUID assistantEntryId, int callIndex) {}
}
