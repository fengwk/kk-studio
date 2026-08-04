package fun.fengwk.kkstudio.core.ai.runtime.thread.reconcile;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.core.ai.runtime.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.core.ai.runtime.execution.EnvironmentToolActivationQueue;
import fun.fengwk.kkstudio.core.ai.runtime.execution.ExecutionActivationStore;
import fun.fengwk.kkstudio.harness.runtime.continuation.ContinuationRef;
import fun.fengwk.kkstudio.harness.runtime.entry.AssistantErrorEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryType;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.RuntimeEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.execution.Lease;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.model.codec.ModelInvocationRequestJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.plan.ModelInvocationPlan;
import fun.fengwk.kkstudio.harness.runtime.model.plan.ModelInvocationPlanner;
import fun.fengwk.kkstudio.harness.runtime.model.plan.PlanningFailure;
import fun.fengwk.kkstudio.harness.runtime.model.plan.PlanningResult;
import fun.fengwk.kkstudio.harness.runtime.model.plan.TurnExecutionResolver;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ToolCallVisibility;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderResponseJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.port.HarnessIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ArtifactMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.HarnessThread;
import fun.fengwk.kkstudio.harness.runtime.thread.InputStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.RuntimeEntryInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ApplyOutcome;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ModelCreationOutcome;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.QuiesceOutcome;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.SuspendOutcome;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadOwnership;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconcileSnapshot;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconcileSnapshot.PrimaryWork;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconcileSnapshot.PrimaryWork.ApplyTerminalModel;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconcileSnapshot.PrimaryWork.ApplyTerminalToolBatch;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconcileSnapshot.PrimaryWork.CreateModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconcileSnapshot.PrimaryWork.SuspendForBlocker;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconcileTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.TurnInputBatch;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageDraft;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL 最终 schema 的 {@link ThreadReconcileTransactions} 实现。
 *
 * <p>每个变更先锁 Thread，再读取或修改 Invocation；完整 epoch/token lease fence 在 SQL 中重验。ModelInvocation 创建前按 turn
 * 携带的名称引用解析当前定义，并把结果冻结为持久化 request。
 */
@Service
public class PostgresqlThreadReconcileTransactions implements ThreadReconcileTransactions {

  private static final int MODEL_CREATION_MAX_ATTEMPTS = 3;
  private static final ModelInvocationRequestJsonCodec REQUEST_CODEC =
      new ModelInvocationRequestJsonCodec();
  private static final ProviderResponseJsonCodec RESPONSE_CODEC = new ProviderResponseJsonCodec();
  private static final ModelInvocationErrorJsonCodec MODEL_ERROR_CODEC =
      new ModelInvocationErrorJsonCodec();
  private static final ToolInvocationErrorJsonCodec TOOL_ERROR_CODEC =
      new ToolInvocationErrorJsonCodec();
  private static final RuntimeEntryPayloadJsonCodec ENTRY_CODEC =
      new RuntimeEntryPayloadJsonCodec();
  private static final ThreadInputPayloadJsonCodec INPUT_CODEC = new ThreadInputPayloadJsonCodec();
  private static final ToolDescriptorJsonCodec TOOL_DESCRIPTOR_CODEC =
      new ToolDescriptorJsonCodec();

  private final ThreadReconcileMapper mapper;
  private final HarnessIdGenerator ids;
  private final ModelInvocationPlanner planner;
  private final Duration leaseDuration;
  private final ExecutionActivationStore executionActivationStore;
  private final EnvironmentToolActivationQueue environmentToolActivationQueue;
  private final TransactionTemplate modelCreationTransaction;

  @Autowired
  public PostgresqlThreadReconcileTransactions(
      ThreadReconcileMapper mapper,
      HarnessIdGenerator ids,
      HarnessRuntimeProperties properties,
      ExecutionActivationStore executionActivationStore,
      EnvironmentToolActivationQueue environmentToolActivationQueue,
      TurnExecutionResolver executionResolver,
      PlatformTransactionManager transactionManager) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.planner =
        new ModelInvocationPlanner(Objects.requireNonNull(executionResolver, "executionResolver"));
    this.leaseDuration =
        Objects.requireNonNull(properties, "properties").getThreadReconcileLeaseDuration();
    this.executionActivationStore =
        Objects.requireNonNull(executionActivationStore, "executionActivationStore");
    this.environmentToolActivationQueue =
        Objects.requireNonNull(environmentToolActivationQueue, "environmentToolActivationQueue");
    this.modelCreationTransaction =
        repeatableReadTransaction(Objects.requireNonNull(transactionManager, "transactionManager"));
    if (leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("thread reconcile lease duration must be positive");
    }
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Optional<ThreadOwnership> claim(long threadId, String processorToken, Instant now) {
    requirePositive(threadId, "threadId");
    requireToken(processorToken);
    Instant observedAt = persisted(now);
    OwnedThreadRow thread = mapper.lockThread(threadId);
    if (!claimable(thread, observedAt)) {
      return Optional.empty();
    }
    if (executionActivationStore
        .lockDue(ExecutionTargetKind.THREAD, threadId, observedAt)
        .isEmpty()) {
      return Optional.empty();
    }
    Instant leaseUntil = observedAt.plus(leaseDuration);
    Long epoch =
        mapper.claim(threadId, processorToken, timestamp(leaseUntil), timestamp(observedAt));
    if (epoch == null) {
      return Optional.empty();
    }
    requireActivationAffected(
        executionActivationStore.rescheduleLocked(ExecutionTargetKind.THREAD, threadId, leaseUntil),
        "reschedule thread watchdog");
    return Optional.of(new ThreadOwnership(threadId, epoch, processorToken));
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public boolean renew(ThreadOwnership ownership, Instant now) {
    Objects.requireNonNull(ownership, "ownership");
    Instant observedAt = persisted(now);
    OwnedThreadRow thread = mapper.lockThread(ownership.threadId());
    if (!ownedBy(thread, ownership, observedAt)) {
      return false;
    }
    Instant leaseUntil = observedAt.plus(leaseDuration);
    if (executionActivationStore.lock(ExecutionTargetKind.THREAD, ownership.threadId()).isEmpty()) {
      throw new IllegalStateException(
          "owned thread is missing its watchdog activation: " + ownership.threadId());
    }
    if (mapper.renew(
            ownership.threadId(),
            ownership.executionEpoch(),
            ownership.processorToken(),
            timestamp(leaseUntil),
            timestamp(observedAt))
        != 1) {
      return false;
    }
    requireActivationAffected(
        executionActivationStore.rescheduleLocked(
            ExecutionTargetKind.THREAD, ownership.threadId(), leaseUntil),
        "reschedule thread watchdog");
    return true;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Optional<ThreadReconcileSnapshot> loadOwnedSnapshot(
      ThreadOwnership ownership, Instant now) {
    OwnedThreadRow thread = owned(ownership, now);
    if (thread == null) {
      return Optional.empty();
    }
    TerminalModelInvocationRow terminal =
        mapper.findTerminalModel(
            thread.getId(), thread.getHeadEntryId(), ownership.executionEpoch());
    Optional<PrimaryWork> primaryWork;
    if (terminal != null) {
      primaryWork = Optional.of(new ApplyTerminalModel(terminal.getId()));
    } else {
      primaryWork = loadReadyToolsOrBlockerOrPlan(thread, ownership);
    }
    List<ThreadInput> queued = toInputs(mapper.listQueuedInputs(thread.getId()));
    return Optional.of(
        new ThreadReconcileSnapshot(ownership, toThread(thread), primaryWork, queued));
  }

  /**
   * 在没有 terminal Model 时，按工具批次、blocker、模型计划的顺序选择唯一主要动作。
   *
   * <p>仅当 terminal model 不存在时调用。工具批次检查同前逻辑：全部 terminal 且未 applied 时为 ready-to-apply batch；否则检查
   * blocker；均不存在时检查 plan。
   */
  private Optional<PrimaryWork> loadReadyToolsOrBlockerOrPlan(
      OwnedThreadRow thread, ThreadOwnership ownership) {
    List<ToolInvocationRow> siblings =
        mapper.listToolSiblings(
            thread.getId(), thread.getHeadEntryId(), ownership.executionEpoch());
    boolean someToolsApplied =
        siblings.stream().anyMatch(sibling -> sibling.getAppliedAt() != null);
    if (someToolsApplied && siblings.stream().anyMatch(sibling -> sibling.getAppliedAt() == null)) {
      throw new IllegalStateException("tool sibling batch has partially applied terminal state");
    }
    boolean readyTools =
        !siblings.isEmpty()
            && !someToolsApplied
            && siblings.stream().allMatch(PostgresqlThreadReconcileTransactions::terminal);
    if (readyTools) {
      return Optional.of(new ApplyTerminalToolBatch(thread.getHeadEntryId()));
    }
    Optional<ContinuationRef> blocker = blocker(thread, ownership.executionEpoch());
    if (blocker.isPresent()) {
      return Optional.of(new SuspendForBlocker(blocker.get()));
    }
    return hasResponseDebt(thread) ? Optional.of(new CreateModelInvocation()) : Optional.empty();
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ApplyOutcome applyTerminalModel(
      ThreadOwnership ownership, long modelInvocationId, Instant now) {
    OwnedThreadRow thread = owned(ownership, now);
    if (thread == null) {
      return ApplyOutcome.LOST_OWNERSHIP;
    }
    TerminalModelInvocationRow invocation =
        mapper.findTerminalModel(
            thread.getId(), thread.getHeadEntryId(), ownership.executionEpoch());
    if (invocation == null || invocation.getId() != modelInvocationId) {
      return ApplyOutcome.LOST_OWNERSHIP;
    }
    EntryPayload payload;
    long sourceHeadEntryId = invocation.getSourceHeadEntryId();
    long entryId;
    if ("SUCCEEDED".equals(invocation.getStatus())) {
      ModelInvocationRequest request = REQUEST_CODEC.decode(invocation.getRequestJson());
      ProviderResponse response = RESPONSE_CODEC.decode(invocation.getResultJson());
      entryId = ids.nextEntryId();
      Optional<ProviderToolCall> unavailableCall =
          ToolCallVisibility.firstUnavailable(request.providerRequest(), response);
      if (unavailableCall.isPresent()) {
        payload =
            new AssistantErrorEntryPayload(
                new ModelInvocationError(
                    ProviderErrorKind.INVALID_REQUEST,
                    ToolCallVisibility.unavailableMessage(
                        unavailableCall.orElseThrow().name(),
                        ToolCallVisibility.availableToolNames(request.providerRequest()))));
        insertEntry(thread, entryId, payload, now);
      } else {
        payload = assistantPayload(response);
        insertEntry(thread, entryId, payload, now);
        insertUsage(
            thread, entryId, ModelUsageDraft.from(request.providerRequest(), response), now);
        materializeTools(
            thread, entryId, modelInvocationId, ownership.executionEpoch(), response, request, now);
      }
      advance(thread, ownership, entryId, now);
    } else {
      payload = new AssistantErrorEntryPayload(modelError(invocation));
      entryId = ids.nextEntryId();
      insertEntry(thread, entryId, payload, now);
      advance(thread, ownership, entryId, now);
    }
    if (mapper.applyModel(
            modelInvocationId,
            ownership.threadId(),
            sourceHeadEntryId,
            entryId,
            ownership.executionEpoch(),
            ownership.processorToken(),
            timestamp(now))
        != 1) {
      throw new IllegalStateException("terminal model disappeared while applying");
    }
    return ApplyOutcome.PROGRESSED;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ApplyOutcome applyTerminalToolResults(
      ThreadOwnership ownership, long assistantEntryId, Instant now) {
    OwnedThreadRow thread = owned(ownership, now);
    if (thread == null || thread.getHeadEntryId() != assistantEntryId) {
      return ApplyOutcome.LOST_OWNERSHIP;
    }
    List<ToolInvocationRow> tools =
        mapper.listToolSiblings(thread.getId(), assistantEntryId, ownership.executionEpoch());
    if (tools.isEmpty()) {
      return ApplyOutcome.LOST_OWNERSHIP;
    }
    boolean someToolsApplied = tools.stream().anyMatch(tool -> tool.getAppliedAt() != null);
    if (someToolsApplied) {
      if (tools.stream().allMatch(tool -> tool.getAppliedAt() != null)) {
        return ApplyOutcome.LOST_OWNERSHIP;
      }
      throw new IllegalStateException("tool sibling batch has partially applied terminal state");
    }
    if (tools.stream().anyMatch(tool -> !terminal(tool))) {
      return ApplyOutcome.LOST_OWNERSHIP;
    }
    long parent = assistantEntryId;
    for (ToolInvocationRow tool : tools) {
      ToolDescriptor descriptor = TOOL_DESCRIPTOR_CODEC.decode(tool.getDescriptorJson());
      ToolResultMessageContent content = toolResult(tool, descriptor);
      long entryId = ids.nextEntryId();
      EntryPayload payload =
          new MessageEntryPayload(
              new AgentMessage(AgentMessageRole.TOOL, List.of(content)), null, null);
      if (mapper.insertEntry(
              entryId,
              thread.getSessionId(),
              parent,
              payload.type().name(),
              ENTRY_CODEC.encode(payload),
              timestamp(now))
          != 1) {
        throw new IllegalStateException("cannot insert tool result entry");
      }
      parent = entryId;
    }
    advance(thread, ownership, parent, now);
    if (mapper.applyTools(
            thread.getId(),
            assistantEntryId,
            parent,
            ownership.executionEpoch(),
            ownership.processorToken(),
            timestamp(now))
        != tools.size()) {
      throw new IllegalStateException("terminal tool siblings changed while applying");
    }
    return ApplyOutcome.PROGRESSED;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public SuspendOutcome suspendAndRecheck(
      ThreadOwnership ownership, ContinuationRef expectedBlocker, Instant now) {
    OwnedThreadRow thread = owned(ownership, now);
    if (thread == null) {
      return SuspendOutcome.LOST_OWNERSHIP;
    }
    Optional<ContinuationRef> current = blocker(thread, ownership.executionEpoch());
    // 预期 blocker 本身不是立即工作。只有 blocker 被替换或消失、出现终态兄弟，或 mailbox 有新工作时，
    // 当前激活才需要继续推进。
    if (!expectedBlocker.equals(current.orElse(null))
        || hasWorkExceptExpected(thread, expectedBlocker)) {
      return SuspendOutcome.WORK_AVAILABLE;
    }
    return release(ownership, false, now)
        ? SuspendOutcome.SUSPENDED
        : SuspendOutcome.LOST_OWNERSHIP;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ApplyOutcome harvestBatch(ThreadOwnership ownership, TurnInputBatch batch, Instant now) {
    OwnedThreadRow thread = owned(ownership, now);
    if (thread == null) {
      return ApplyOutcome.LOST_OWNERSHIP;
    }
    List<ThreadInput> durable = toInputs(mapper.listQueuedInputs(thread.getId()));
    if (!sameBatchPrefix(batch, durable)) {
      return ApplyOutcome.LOST_OWNERSHIP;
    }
    long parent = thread.getHeadEntryId();
    for (ThreadInput input : batch.inputs()) {
      EntryPayload payload = inputPayload(input);
      long entryId = ids.nextEntryId();
      if (mapper.insertEntry(
                  entryId,
                  thread.getSessionId(),
                  parent,
                  payload.type().name(),
                  ENTRY_CODEC.encode(payload),
                  timestamp(now))
              != 1
          || mapper.applyInput(
                  input.id(),
                  input.threadId(),
                  input.sequence(),
                  input.type().name(),
                  timestamp(now))
              != 1) {
        throw new IllegalStateException("cannot atomically harvest queued input");
      }
      parent = entryId;
    }
    advance(thread, ownership, parent, now);
    return ApplyOutcome.PROGRESSED;
  }

  /**
   * 使用同一个 repeatable-read 快照读取 resolver 所需的 Agent、Provider 和 Model，避免冻结的 request
   * 组合从未共同提交过的目录版本。PostgreSQL serialization failure 在回滚事务外重试；每次重试都会重新检查所有权、路径和当前定义。
   */
  @Override
  public ModelCreationOutcome createModelInvocationAndRelease(
      ThreadOwnership ownership, Instant now) {
    RuntimeException lastSerializationFailure = null;
    for (int attempt = 1; attempt <= MODEL_CREATION_MAX_ATTEMPTS; attempt++) {
      try {
        return Objects.requireNonNull(
            modelCreationTransaction.execute(
                ignored -> createModelInvocationAndReleaseInTransaction(ownership, now)));
      } catch (RuntimeException error) {
        if (!isSerializationFailure(error)) {
          throw error;
        }
        lastSerializationFailure = error;
      }
    }
    throw Objects.requireNonNull(lastSerializationFailure);
  }

  private ModelCreationOutcome createModelInvocationAndReleaseInTransaction(
      ThreadOwnership ownership, Instant now) {
    OwnedThreadRow thread = owned(ownership, now);
    if (thread == null) {
      return new ModelCreationOutcome.LostOwnership();
    }
    PlanningResult planningResult = plan(thread);
    if (planningResult instanceof PlanningResult.NoDebt) {
      throw new IllegalStateException("response debt disappeared while creating model invocation");
    }
    if (planningResult instanceof PlanningResult.Failed failed) {
      appendPlanningFailure(thread, ownership, failed.failure(), now);
      return new ModelCreationOutcome.PlanningFailureApplied();
    }
    ModelInvocationPlan plan = ((PlanningResult.Planned) planningResult).plan();
    long id = ids.nextModelInvocationId();
    if (mapper.insertModelInvocation(
            id,
            thread.getId(),
            thread.getHeadEntryId(),
            ownership.executionEpoch(),
            REQUEST_CODEC.encode(plan.request()),
            timestamp(now))
        != 1) {
      throw new IllegalStateException("cannot create model invocation");
    }
    executionActivationStore.schedule(
        ExecutionTargetKind.MODEL_INVOCATION, id, null, persisted(now));
    if (!release(ownership, false, now)) {
      throw new IllegalStateException("cannot release created model invocation");
    }
    return new ModelCreationOutcome.Created(
        new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, id));
  }

  private static TransactionTemplate repeatableReadTransaction(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    return transaction;
  }

  private static boolean isSerializationFailure(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof CannotSerializeTransactionException) {
        return true;
      }
      if (current instanceof SQLException sqlException
          && "40001".equals(sqlException.getSQLState())) {
        return true;
      }
    }
    return false;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public QuiesceOutcome quiesceAndRecheck(ThreadOwnership ownership, Instant now) {
    OwnedThreadRow thread = owned(ownership, now);
    if (thread == null) {
      return QuiesceOutcome.LOST_OWNERSHIP;
    }
    if (hasAnyWork(thread) || hasResponseDebt(thread)) {
      return QuiesceOutcome.WORK_AVAILABLE;
    }
    return release(ownership, false, now)
        ? QuiesceOutcome.QUIESCENT
        : QuiesceOutcome.LOST_OWNERSHIP;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void bestEffortRelease(ThreadOwnership ownership, Instant now) {
    release(Objects.requireNonNull(ownership, "ownership"), true, now);
  }

  private OwnedThreadRow owned(ThreadOwnership ownership, Instant now) {
    Objects.requireNonNull(ownership, "ownership");
    Instant observedAt = persisted(now);
    OwnedThreadRow thread = mapper.lockThread(ownership.threadId());
    return ownedBy(thread, ownership, observedAt) ? thread : null;
  }

  private PlanningResult plan(OwnedThreadRow thread) {
    return planAt(thread, thread.getHeadEntryId());
  }

  private PlanningResult planAt(OwnedThreadRow thread, long headEntryId) {
    return planner.plan(
        thread.getSessionId(),
        headEntryId,
        thread.getEnvironmentName(),
        path(thread.getSessionId(), headEntryId));
  }

  private boolean hasResponseDebt(OwnedThreadRow thread) {
    return planner.hasResponseDebt(
        thread.getHeadEntryId(), path(thread.getSessionId(), thread.getHeadEntryId()));
  }

  private List<SessionEntry> path(long sessionId, long headEntryId) {
    return mapper.loadPath(sessionId, headEntryId).stream()
        .map(
            row ->
                new SessionEntry(
                    row.getId(),
                    row.getParentEntryId(),
                    ENTRY_CODEC.decode(
                        EntryType.valueOf(row.getEntryType()), row.getPayloadJson())))
        .toList();
  }

  private Optional<ContinuationRef> blocker(OwnedThreadRow thread, long epoch) {
    InvocationBlockerRow model =
        mapper.findModelBlocker(thread.getId(), thread.getHeadEntryId(), epoch);
    if (model != null) {
      return Optional.of(ref(thread.getId(), ExecutionTargetKind.MODEL_INVOCATION, model.getId()));
    }
    InvocationBlockerRow tool =
        mapper.findToolBlocker(thread.getId(), thread.getHeadEntryId(), epoch);
    return tool == null
        ? Optional.empty()
        : Optional.of(ref(thread.getId(), ExecutionTargetKind.TOOL_INVOCATION, tool.getId()));
  }

  private boolean hasAnyWork(OwnedThreadRow thread) {
    return mapper.findTerminalModel(
                thread.getId(), thread.getHeadEntryId(), thread.getExecutionEpoch())
            != null
        || mapper
            .listToolSiblings(thread.getId(), thread.getHeadEntryId(), thread.getExecutionEpoch())
            .stream()
            .anyMatch(sibling -> sibling.getAppliedAt() == null)
        || blocker(thread, thread.getExecutionEpoch()).isPresent()
        || !mapper.listQueuedInputs(thread.getId()).isEmpty();
  }

  private boolean hasWorkExceptExpected(OwnedThreadRow thread, ContinuationRef expected) {
    if (mapper.findTerminalModel(
            thread.getId(), thread.getHeadEntryId(), thread.getExecutionEpoch())
        != null) {
      return true;
    }
    List<ToolInvocationRow> siblings =
        mapper.listToolSiblings(
            thread.getId(), thread.getHeadEntryId(), thread.getExecutionEpoch());
    if (!siblings.isEmpty()
        && siblings.stream().allMatch(PostgresqlThreadReconcileTransactions::terminal)
        && siblings.stream().anyMatch(sibling -> sibling.getAppliedAt() == null)) {
      return true;
    }
    return !expected.equals(blocker(thread, thread.getExecutionEpoch()).orElse(null));
  }

  private void materializeTools(
      OwnedThreadRow thread,
      long assistantEntryId,
      long modelInvocationId,
      long epoch,
      ProviderResponse response,
      ModelInvocationRequest request,
      Instant now) {
    Instant persistedNow = persisted(now);
    LinkedHashSet<String> environmentNames = new LinkedHashSet<>();
    for (int ordinal = 0; ordinal < response.toolCalls().size(); ordinal++) {
      var call = response.toolCalls().get(ordinal);
      ToolBinding binding =
          request.toolBindings().stream()
              .filter(candidate -> candidate.descriptor().name().equals(call.name()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          ToolCallVisibility.unavailableMessage(
                              call.name(),
                              request.toolBindings().stream()
                                  .map(candidateBinding -> candidateBinding.descriptor().name())
                                  .toList())));
      long invocationId = ids.nextToolInvocationId();
      if (mapper.insertToolInvocation(
              invocationId,
              thread.getId(),
              thread.getSessionId(),
              assistantEntryId,
              modelInvocationId,
              ordinal,
              call.id(),
              TOOL_DESCRIPTOR_CODEC.encode(binding.descriptor()),
              call.argumentsJson(),
              binding.environmentName(),
              epoch,
              request.yoloEnabled(),
              timestamp(now))
          != 1) {
        throw new IllegalStateException("cannot materialize tool invocation");
      }
      switch (binding.type()) {
        case ENVIRONMENT:
          requireActivationAffected(
              executionActivationStore.park(
                  ExecutionTargetKind.TOOL_INVOCATION,
                  invocationId,
                  binding.environmentName(),
                  persistedNow),
              "park tool invocation");
          environmentNames.add(binding.environmentName());
          break;
        case PLATFORM:
          requireActivationAffected(
              executionActivationStore.schedule(
                  ExecutionTargetKind.TOOL_INVOCATION, invocationId, null, persistedNow),
              "schedule tool invocation");
          break;
      }
    }
    for (String environmentName : environmentNames) {
      environmentToolActivationQueue.activateOldestTool(environmentName, persistedNow);
    }
  }

  private static EntryPayload assistantPayload(ProviderResponse response) {
    List<AgentMessageContent> contents = new ArrayList<>();
    if (!response.thinking().isEmpty()) {
      contents.add(new ThinkingMessageContent(response.thinking()));
    }
    if (!response.text().isEmpty()) {
      contents.add(new TextMessageContent(response.text()));
    }
    response
        .toolCalls()
        .forEach(
            call ->
                contents.add(
                    new ToolCallMessageContent(call.id(), call.name(), call.argumentsJson())));
    if (contents.isEmpty()) {
      contents.add(new TextMessageContent(""));
    }
    return new MessageEntryPayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, contents),
        null,
        new AssistantMessageMetadata(response.stopReason(), response.usage(), response.cost()));
  }

  private static ModelInvocationError modelError(TerminalModelInvocationRow invocation) {
    if ("CANCELLED".equals(invocation.getStatus())) {
      return new ModelInvocationError(ProviderErrorKind.CANCELLED, "model invocation cancelled");
    }
    return MODEL_ERROR_CODEC.decode(
        Objects.requireNonNull(invocation.getErrorJson(), "terminal model error"));
  }

  private static ToolResultMessageContent toolResult(
      ToolInvocationRow row, ToolDescriptor descriptor) {
    if ("SUCCEEDED".equals(row.getStatus())) {
      ToolResult result = ToolResultJsonCodec.decode(row.getResultJson());
      if (!row.getToolCallId().equals(result.toolCallId())) {
        throw new IllegalStateException("tool result toolCallId does not match invocation");
      }
      return new ToolResultMessageContent(
          result.toolCallId(),
          descriptor.name(),
          contents(result.contents()),
          result.error(),
          result.detailsJson());
    }
    ToolInvocationError error =
        "CANCELLED".equals(row.getStatus())
            ? new ToolInvocationError("CANCELLED", "tool invocation cancelled")
            : TOOL_ERROR_CODEC.decode(
                Objects.requireNonNull(row.getErrorJson(), "terminal tool error"));
    return new ToolResultMessageContent(
        row.getToolCallId(),
        descriptor.name(),
        List.of(new TextMessageContent(error.message())),
        true,
        TOOL_ERROR_CODEC.encode(error));
  }

  private static List<AgentMessageContent> contents(List<ToolContent> contents) {
    List<AgentMessageContent> mapped = new ArrayList<>();
    for (ToolContent content : contents) {
      if (content instanceof TextToolContent text) {
        mapped.add(new TextMessageContent(text.text()));
      } else if (content instanceof JsonToolContent json) {
        mapped.add(new JsonMessageContent(json.json()));
      } else if (content instanceof ArtifactToolContent artifact) {
        mapped.add(
            new ArtifactMessageContent(
                artifact.artifact().artifactId(), artifact.artifact().mediaType(), null));
      } else {
        throw new IllegalArgumentException("unsupported tool content: " + content.getClass());
      }
    }
    return mapped.isEmpty() ? List.of(new TextMessageContent("")) : List.copyOf(mapped);
  }

  private static List<ThreadInput> toInputs(List<QueuedThreadInputRow> rows) {
    return rows.stream().map(PostgresqlThreadReconcileTransactions::toInput).toList();
  }

  private static ThreadInput toInput(QueuedThreadInputRow row) {
    ThreadInputType type = ThreadInputType.valueOf(row.getInputType());
    return new ThreadInput(
        row.getId(),
        row.getThreadId(),
        row.getSequence(),
        type,
        INPUT_CODEC.decode(type, row.getPayloadJson()),
        row.getIdempotencyKey(),
        InputStatus.valueOf(row.getStatus()),
        row.getCreatedAt().toInstant(),
        row.getAppliedAt() == null ? null : row.getAppliedAt().toInstant());
  }

  private static EntryPayload inputPayload(ThreadInput input) {
    if (input.payload() instanceof RuntimeEntryInputPayload entry) {
      return entry.payload();
    }
    throw new IllegalArgumentException(
        "unsupported typed input payload: " + input.payload().getClass());
  }

  private static HarnessThread toThread(OwnedThreadRow row) {
    Lease lease =
        row.getProcessorToken() == null
            ? null
            : new Lease(row.getProcessorToken(), row.getProcessorUntil().toInstant());
    return new HarnessThread(
        row.getId(),
        row.getHeadEntryId(),
        row.getEnvironmentName(),
        row.getInputSequence(),
        row.isRunnable(),
        row.getExecutionEpoch(),
        row.getRevision(),
        lease,
        row.getCreatedAt().toInstant(),
        row.getUpdatedAt().toInstant());
  }

  private void insertEntry(OwnedThreadRow thread, long id, EntryPayload payload, Instant now) {
    if (mapper.insertEntry(
            id,
            thread.getSessionId(),
            thread.getHeadEntryId(),
            payload.type().name(),
            ENTRY_CODEC.encode(payload),
            timestamp(now))
        != 1) {
      throw new IllegalStateException("cannot insert entry");
    }
  }

  private void appendPlanningFailure(
      OwnedThreadRow thread, ThreadOwnership ownership, PlanningFailure failure, Instant now) {
    Objects.requireNonNull(failure, "failure");
    long entryId = ids.nextEntryId();
    EntryPayload payload =
        new AssistantErrorEntryPayload(
            new ModelInvocationError(
                ProviderErrorKind.INVALID_REQUEST,
                failure.kind().name() + ": " + failure.message()));
    insertEntry(thread, entryId, payload, now);
    advance(thread, ownership, entryId, now);
  }

  private void insertUsage(
      OwnedThreadRow thread, long assistantEntryId, ModelUsageDraft draft, Instant now) {
    if (mapper.insertModelUsage(
            thread.getSessionId(), thread.getId(), assistantEntryId, draft, timestamp(now))
        != 1) {
      throw new IllegalStateException("cannot insert model usage");
    }
  }

  private void advance(
      OwnedThreadRow thread, ThreadOwnership ownership, long newHead, Instant now) {
    if (mapper.advanceHead(
            thread.getId(),
            thread.getHeadEntryId(),
            newHead,
            ownership.executionEpoch(),
            ownership.processorToken(),
            timestamp(now))
        != 1) {
      throw new IllegalStateException("cannot advance fenced thread head");
    }
  }

  private boolean release(ThreadOwnership ownership, boolean runnable, Instant now) {
    Instant observedAt = persisted(now);
    if (executionActivationStore.lock(ExecutionTargetKind.THREAD, ownership.threadId()).isEmpty()) {
      throw new IllegalStateException(
          "owned thread is missing its watchdog activation: " + ownership.threadId());
    }
    if (mapper.release(
            ownership.threadId(),
            ownership.executionEpoch(),
            ownership.processorToken(),
            runnable,
            timestamp(observedAt))
        != 1) {
      return false;
    }
    if (runnable) {
      requireActivationAffected(
          executionActivationStore.rescheduleLocked(
              ExecutionTargetKind.THREAD, ownership.threadId(), observedAt),
          "reactivate thread activation");
    } else {
      requireActivationAffected(
          executionActivationStore.deleteLocked(ExecutionTargetKind.THREAD, ownership.threadId()),
          "delete thread activation");
    }
    return true;
  }

  private static boolean claimable(OwnedThreadRow thread, Instant now) {
    return thread != null
        && thread.isRunnable()
        && thread.getHeadEntryId() > 0
        && (thread.getProcessorToken() == null
            || (thread.getProcessorUntil() != null
                && !thread.getProcessorUntil().isAfter(timestamp(now))));
  }

  private static boolean ownedBy(OwnedThreadRow thread, ThreadOwnership ownership, Instant now) {
    return thread != null
        && thread.isRunnable()
        && thread.getExecutionEpoch() == ownership.executionEpoch()
        && ownership.processorToken().equals(thread.getProcessorToken())
        && thread.getProcessorUntil() != null
        && thread.getProcessorUntil().isAfter(timestamp(now));
  }

  private static boolean terminal(ToolInvocationRow row) {
    return switch (row.getStatus()) {
      case "SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN" -> true;
      default -> false;
    };
  }

  private static ContinuationRef ref(long threadId, ExecutionTargetKind kind, long id) {
    return new ContinuationRef(
        new ExecutionTarget(ExecutionTargetKind.THREAD, threadId), new ExecutionTarget(kind, id));
  }

  private static boolean sameBatchPrefix(TurnInputBatch expected, List<ThreadInput> actual) {
    if (expected.threadId() <= 0 || actual.size() < expected.inputs().size()) {
      return false;
    }
    for (int index = 0; index < expected.inputs().size(); index++) {
      ThreadInput expectedInput = expected.inputs().get(index);
      ThreadInput actualInput = actual.get(index);
      if (expectedInput.threadId() != expected.threadId()
          || actualInput.threadId() != expected.threadId()
          || !expectedInput.equals(actualInput)) {
        return false;
      }
    }
    return true;
  }

  private static Instant persisted(Instant instant) {
    return Objects.requireNonNull(instant, "now").truncatedTo(ChronoUnit.MILLIS);
  }

  private static OffsetDateTime timestamp(Instant instant) {
    return OffsetDateTime.ofInstant(persisted(instant), ZoneOffset.UTC);
  }

  private static void requireActivationAffected(int affected, String operation) {
    if (affected != 1) {
      throw new IllegalStateException(operation + " affected " + affected + " rows");
    }
  }

  private static void requirePositive(long value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireToken(String value) {
    if (value == null || value.isBlank() || value.length() > 128) {
      throw new IllegalArgumentException("processor token must be non-blank and <= 128 chars");
    }
  }
}
