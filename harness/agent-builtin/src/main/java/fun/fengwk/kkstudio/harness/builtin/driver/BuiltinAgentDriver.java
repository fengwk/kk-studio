package fun.fengwk.kkstudio.harness.builtin.driver;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.generation.FailureProjection;
import fun.fengwk.kkstudio.harness.api.generation.GenerationProjection;
import fun.fengwk.kkstudio.harness.api.generation.GenerationStatus;
import fun.fengwk.kkstudio.harness.api.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.api.history.AssistantError;
import fun.fengwk.kkstudio.harness.api.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.EntryPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnCapabilities;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.builtin.compaction.AutomaticCompactionPlanner;
import fun.fengwk.kkstudio.harness.builtin.port.BuiltinWorkType;
import fun.fengwk.kkstudio.harness.builtin.port.TurnResolver;
import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelAttemptMaterialization;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupExecution;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupProgress;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.builtin.port.store.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.builtin.processor.ModelAttemptFailureAppender;
import fun.fengwk.kkstudio.harness.builtin.processor.ModelOutcomeAppender;
import fun.fengwk.kkstudio.harness.builtin.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.builtin.request.ResolvedRequestValidator;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.spi.TurnResolveTransientException;
import fun.fengwk.kkstudio.harness.spi.driver.AgentDriver;
import fun.fengwk.kkstudio.harness.spi.driver.CompactionResolution;
import fun.fengwk.kkstudio.harness.spi.driver.DriverActivity;
import fun.fengwk.kkstudio.harness.spi.driver.DriverApplied;
import fun.fengwk.kkstudio.harness.spi.driver.DriverCapabilities;
import fun.fengwk.kkstudio.harness.spi.driver.DriverCommit;
import fun.fengwk.kkstudio.harness.spi.driver.DriverStopResult;
import fun.fengwk.kkstudio.harness.spi.driver.DriverWork;
import fun.fengwk.kkstudio.harness.spi.driver.LockedDriverState;
import fun.fengwk.kkstudio.harness.spi.driver.TurnResolution;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.store.UuidOrder;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.tool.TurnMediaCapabilities;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Builtin Agent Driver：拥有 Builtin 模型状态、Turn 解析与响应物化，Runtime 只经 {@link AgentDriver} 语义方法调用。
 *
 * <p>所有方法都在 Runtime 已开启的事务内执行；模型状态通过 {@link BuiltinStore#in(HarnessStore.Transaction)}
 * 与公共历史写入共享同一物理事务。Provider I/O 不在此类中执行，由 Builtin 注册的 MODEL Work 处理器负责。
 */
public final class BuiltinAgentDriver implements AgentDriver {

  private static final String CANCELLED_MESSAGE = "Cancelled by user";

  private final BuiltinStore store;
  private final TurnResolver resolver;
  private final ModelProcessor processor;
  private final ModelOutcomeAppender appender = new ModelOutcomeAppender();
  private final AutomaticCompactionPlanner compactionPlanner = new AutomaticCompactionPlanner();

  /** 仅供只验证规划与物化的测试构造；不注册 Work 处理器。 */
  public BuiltinAgentDriver(BuiltinStore store, TurnResolver resolver) {
    this(store, resolver, null);
  }

  /** 生产装配：连同 Builtin 的 MODEL Work 处理器、本地取消与活跃检查一起注册。 */
  public BuiltinAgentDriver(BuiltinStore store, TurnResolver resolver, ModelProcessor processor) {
    this.store = Objects.requireNonNull(store, "store");
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.processor = processor;
  }

  @Override
  public List<DriverWork> works() {
    return processor == null
        ? List.of()
        : List.of(new DriverWork(BuiltinWorkType.MODEL, processor::process));
  }

  @Override
  public void cancelWork(UUID executionId) {
    if (processor != null) {
      processor.cancel(executionId);
    }
  }

  @Override
  public boolean hasActiveWork() {
    return processor != null && processor.hasActiveExecution();
  }

  @Override
  public List<WorkTarget> lockThreadStateForDeletion(
      HarnessStore.Transaction tx, List<UUID> threadIds) {
    BuiltinStore.Transaction builtin = store.in(tx);
    List<ModelInvocation> models = new ArrayList<>();
    for (UUID threadId : threadIds) {
      models.addAll(builtin.findModelInvocationsByThread(threadId));
    }
    models.sort(Comparator.comparing(ModelInvocation::id, UuidOrder.COMPARATOR));
    List<WorkTarget> locked = new ArrayList<>(models.size());
    for (ModelInvocation model : models) {
      builtin
          .lockModelInvocation(model.id())
          .ifPresent(value -> locked.add(new WorkTarget(BuiltinWorkType.MODEL, value.id())));
    }
    return List.copyOf(locked);
  }

  @Override
  public void deleteLockedThreadState(
      HarnessStore.Transaction tx, List<UUID> threadIds, List<WorkTarget> lockedWorkTargets) {
    BuiltinStore.Transaction builtin = store.in(tx);
    for (WorkTarget target : lockedWorkTargets) {
      if (BuiltinWorkType.MODEL.equals(target.type())) {
        builtin.deleteModelInvocation(target.id());
      }
    }
  }

  @Override
  public String id() {
    return BuiltinExecution.DRIVER;
  }

  @Override
  public DriverCapabilities capabilities() {
    return new DriverCapabilities(true, true);
  }

  @Override
  public void validateSettings(String settingsJson) {
    try {
      BuiltinExecution.parseSettings(settingsJson);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation("execution", error.getMessage(), error);
    }
  }

  @Override
  public TurnResolution resolveTurn(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath candidatePath,
      CompactionPreparation preparation) {
    TurnResolver.Result result;
    try {
      result = resolver.resolve(thread.id(), rootSessionId(tx, thread), candidatePath, preparation);
    } catch (TurnResolveTransientException transientFailure) {
      throw transientFailure;
    }
    if (result == null) {
      throw new IllegalStateException("turn resolver returned null");
    }
    return switch (result) {
      case TurnResolver.Rejected rejected -> new TurnResolution.Rejected(rejected.error());
      case TurnResolver.CompactionResolved resolved -> new TurnResolution.Compaction(
          new CompactionResolution(
              resolved.execution(),
              resolved.outputBudget(),
              resolved.contextWindow(),
              resolved.maxOutputTokens(),
              resolved.childSettings()));
      case TurnResolver.Resolved resolved -> accepted(thread, candidatePath, resolved);
    };
  }

  /**
   * 执行根会话与 ToolProcessor 的 execution scope 同源：根 Thread 自身即其会话；子 Thread 从不可变 rootThreadId 读取根 Thread
   * 行，缺失时显式失败，绝不按当前 Thread 的会话合成。
   */
  private static UUID rootSessionId(HarnessStore.Transaction tx, ThreadState thread) {
    if (thread.rootThreadId().equals(thread.id())) {
      return thread.sessionId();
    }
    return tx.threads()
        .find(thread.rootThreadId())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "thread " + thread.id() + " lost its execution root " + thread.rootThreadId()))
        .sessionId();
  }

  private TurnResolution accepted(
      ThreadState thread, EntryPath candidatePath, TurnResolver.Resolved resolved) {
    try {
      ResolvedRequestValidator.validate(candidatePath, resolved);
    } catch (RuntimeException contractFailure) {
      throw new IllegalStateException(
          "builtin resolved request violated the contract", contractFailure);
    }
    ModelRequestSpec spec = resolved.spec();
    ThreadConstraints constraints = thread.constraints();
    Integer cap = constraints.maxOutputTokens();
    if (cap != null) {
      spec =
          new ModelRequestSpec(
              spec.providerType(),
              spec.providerConnectionGenerationId(),
              spec.model(),
              spec.variant(),
              spec.modelVariants(),
              Math.min(spec.outputTokens(), cap),
              spec.systemInstruction(),
              spec.toolBindings(),
              spec.subagentBindings(),
              spec.groupExecution());
    }
    // 无 cap 时保留 resolver 的模型上限元数据；有 cap 时不得超过实际请求预算。
    int maxOutputTokens =
        cap == null
            ? resolved.maxOutputTokens()
            : Math.min(resolved.maxOutputTokens(), spec.outputTokens());
    return new AcceptedTurn(
        spec,
        resolved.contextWindow(),
        maxOutputTokens,
        new TurnCapabilities(
            spec.toolBindings(),
            spec.subagentBindings(),
            new TurnMediaCapabilities(
                spec.model().inputModalities(),
                spec.providerType().mediaCapabilities().userModalities(),
                spec.providerType().mediaCapabilities().toolResultModalities())),
        BuiltinExecution.of(
            spec.groupExecution() == null
                ? ModelSelection.model(spec.model().modelName(), spec.variant().id())
                : ModelSelection.group(spec.groupExecution().groupName())));
  }

  @Override
  public LockedDriverState readState(
      HarnessStore.Transaction tx, ThreadState thread, EntryCurrentState state) {
    Optional<Entry> openTurn = state.openTurnStart();
    if (openTurn.isEmpty()) {
      return NoDriverState.INSTANCE;
    }
    return store
        .in(tx)
        .findModelInvocationByTurn(thread.id(), openTurn.get().id())
        .map(model -> project(model, state))
        .orElse(NoDriverState.INSTANCE);
  }

  @Override
  public LockedDriverState lockState(
      HarnessStore.Transaction tx, ThreadState thread, EntryCurrentState state) {
    Optional<Entry> openTurn = state.openTurnStart();
    if (openTurn.isEmpty()) {
      return NoDriverState.INSTANCE;
    }
    BuiltinStore.Transaction builtin = store.in(tx);
    Optional<ModelInvocation> found =
        builtin.findModelInvocationByTurn(thread.id(), openTurn.get().id());
    if (found.isEmpty()) {
      return NoDriverState.INSTANCE;
    }
    ModelInvocation model =
        builtin
            .lockModelInvocation(found.get().id())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "model "
                            + found.get().id()
                            + " could not be locked for thread "
                            + thread.id()));
    return project(model, state);
  }

  private LockedDriverState project(ModelInvocation model, EntryCurrentState state) {
    DriverActivity activity = DriverActivity.NONE;
    if (model.resultEntryId() == null && model.requestHeadEntryId().equals(state.head().id())) {
      activity =
          model.status().isTerminal() ? DriverActivity.RESPONSE_PENDING : DriverActivity.ACTIVE;
    }
    return new BuiltinState(model, activity);
  }

  @Override
  public DriverApplied applyResponse(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      LockedDriverState state,
      DriverCommit commit) {
    BuiltinState builtin = requireBuiltin(state);
    if (builtin.activity() != DriverActivity.RESPONSE_PENDING || builtin.model == null) {
      throw new IllegalStateException(
          "builtin response apply requires a pending terminal generation");
    }
    ModelInvocation model = builtin.model;
    if (!model.threadId().equals(thread.id())
        || !thread.headEntryId().equals(model.requestHeadEntryId())) {
      throw new IllegalStateException(
          "terminal builtin generation preconditions changed under the same transaction");
    }
    ModelOutcomeAppender.Applied applied =
        appender.appendModel(store.in(tx), path, model, commit.now());
    return new DriverApplied(
        applied.headEntryId(),
        applied.finalAnswerEntryId(),
        applied.terminal(),
        applied.continueModel(),
        applied.toolPhase(),
        applied.outcome(),
        applied.toolInvocations());
  }

  @Override
  public void completeToolBatch(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      LockedDriverState state,
      List<ToolInvocation> siblings,
      DriverCommit commit) {
    BuiltinState builtin = optionalBuiltin(state);
    if (builtin == null) {
      return;
    }
    ModelInvocation model = builtin.model;
    if (model == null) {
      return;
    }
    UUID assistantId = path.head().id();
    if (model.resultEntryId() == null || !model.resultEntryId().equals(assistantId)) {
      throw new IllegalStateException("builtin generation does not own the locked tool batch");
    }
    ModelAttemptMaterialization.validateAttached(model, path);
    store.in(tx).deleteModelInvocation(model.id());
  }

  @Override
  public CompactionPreparation prepareAutomaticCompaction(
      ThreadState thread, EntryPath path, CompactionConfig config, boolean hasQueuedMessage) {
    return compactionPlanner.plan(thread, path, config, hasQueuedMessage);
  }

  @Override
  public DriverStopResult stop(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      LockedDriverState state,
      DriverCommit commit,
      UUID stopRequestId) {
    BuiltinState builtin = optionalBuiltin(state);
    if (builtin == null) {
      return new DriverStopResult(null, List.of());
    }
    ModelInvocation model = builtin.model;
    if (model == null) {
      return new DriverStopResult(null, List.of());
    }
    if (builtin.activity() != DriverActivity.ACTIVE) {
      if (model.resultEntryId() != null && model.resultEntryId().equals(path.head().id())) {
        ModelAttemptMaterialization.validateAttached(model, path);
        store.in(tx).deleteModelInvocation(model.id());
      }
      return new DriverStopResult(null, List.of());
    }
    Instant now = commit.now();
    StreamCheckpoint checkpoint = model.streamCheckpoint();
    EntryPayload barrier = stopBarrier(checkpoint);
    ModelInvocationError error =
        new ModelInvocationError(ProviderErrorKind.CANCELLED, CANCELLED_MESSAGE);
    UUID parentId =
        ModelAttemptFailureAppender.append(
            store.in(tx), path.root().sessionId(), path.head().id(), model, false);
    UUID barrierId = commit.nextId();
    commit.insertEntry(new Entry(barrierId, path.root().sessionId(), parentId, barrier, now));
    ModelInvocation cancelled = model.cancel(error, now).attachResultEntry(barrierId, now);
    store.in(tx).updateModelInvocation(cancelled);
    store.in(tx).deleteModelInvocation(model.id());
    return new DriverStopResult(barrierId, List.of(model.id()));
  }

  private static EntryPayload stopBarrier(StreamCheckpoint checkpoint) {
    if (checkpoint == null) {
      return new AssistantErrorPayload(
          new AssistantError(AssistantError.CANCELLED_CODE, CANCELLED_MESSAGE), null);
    }
    List<AgentMessageContent> contents = new ArrayList<>(2);
    if (!checkpoint.thinking().isEmpty()) {
      contents.add(new ThinkingMessageContent(checkpoint.thinking()));
    }
    if (!checkpoint.text().isEmpty()) {
      contents.add(new TextMessageContent(checkpoint.text()));
    }
    if (contents.isEmpty()) {
      return new AssistantErrorPayload(
          new AssistantError(AssistantError.CANCELLED_CODE, CANCELLED_MESSAGE), null);
    }
    return new AssistantAbortedPayload(new AgentMessage(AgentMessageRole.ASSISTANT, contents));
  }

  private static BuiltinState requireBuiltin(LockedDriverState state) {
    BuiltinState builtin = optionalBuiltin(state);
    if (builtin == null) {
      throw new IllegalArgumentException("locked state does not belong to the builtin driver");
    }
    return builtin;
  }

  /** Tool-only 路径允许没有 Builtin generation；其它 Driver 状态仍必须 fail closed。 */
  private static BuiltinState optionalBuiltin(LockedDriverState state) {
    if (state instanceof BuiltinState builtin) {
      return builtin;
    }
    if (state instanceof NoDriverState noState && noState.activity() == DriverActivity.NONE) {
      return null;
    }
    throw new IllegalArgumentException("locked state does not belong to the builtin driver");
  }

  private static GenerationProjection projection(ModelInvocation model) {
    List<FailureProjection> failures = new ArrayList<>(model.failedAttempts().size());
    for (ModelAttemptFailure failure : model.failedAttempts()) {
      failures.add(
          new FailureProjection(
              failure.attempt(),
              failure.sequence(),
              failure.text(),
              failure.thinking(),
              failure.error().kind().name(),
              failure.error().message(),
              failure.failedAt(),
              failure.retryAt()));
    }
    StreamCheckpoint checkpoint = model.streamCheckpoint();
    return new GenerationProjection(
        model.id(),
        model.turnStartEntryId(),
        model.requestHeadEntryId(),
        status(model),
        model.attempt(),
        checkpoint == null ? 0L : checkpoint.sequence(),
        outputContents(checkpoint),
        model.error() == null
            ? null
            : new AssistantError(model.error().kind().name(), model.error().message()),
        model.resultEntryId(),
        model.createdAt(),
        model.updatedAt(),
        failures);
  }

  private static GenerationStatus status(ModelInvocation model) {
    return switch (model.status()) {
      case READY -> model.failedAttempts().isEmpty()
          ? GenerationStatus.READY
          : GenerationStatus.WAITING_RETRY;
      case RUNNING -> GenerationStatus.RUNNING;
      case SUCCEEDED -> GenerationStatus.SUCCEEDED;
      case FAILED -> GenerationStatus.FAILED;
      case CANCELLED -> GenerationStatus.CANCELLED;
      case UNKNOWN -> GenerationStatus.BLOCKED;
    };
  }

  private static List<AgentMessageContent> outputContents(StreamCheckpoint checkpoint) {
    if (checkpoint == null) {
      return List.of();
    }
    List<AgentMessageContent> contents = new ArrayList<>(2);
    if (!checkpoint.thinking().isEmpty()) {
      contents.add(new ThinkingMessageContent(checkpoint.thinking()));
    }
    if (!checkpoint.text().isEmpty()) {
      contents.add(new TextMessageContent(checkpoint.text()));
    }
    return List.copyOf(contents);
  }

  private static final class BuiltinState implements LockedDriverState {

    private final ModelInvocation model;
    private final DriverActivity activity;
    private final GenerationProjection projection;

    private BuiltinState(ModelInvocation model, DriverActivity activity) {
      this.model = model;
      this.activity = activity;
      this.projection = BuiltinAgentDriver.projection(model);
    }

    @Override
    public DriverActivity activity() {
      return activity;
    }

    @Override
    public UUID requestHeadEntryId() {
      return model.requestHeadEntryId();
    }

    @Override
    public UUID resultEntryId() {
      return model.resultEntryId();
    }

    @Override
    public GenerationProjection projection() {
      return projection;
    }

    @Override
    public UUID generationId() {
      return model.id();
    }

    @Override
    public WorkTarget workTarget() {
      // Stop 据此锁定并删除本 generation 的 MODEL Work；tool 阶段 generation 已 attached、
      // activity 不是 ACTIVE，但 Work 仍归属该 generation。
      return new WorkTarget(BuiltinWorkType.MODEL, model.id());
    }
  }

  private static final class NoDriverState implements LockedDriverState {

    private static final NoDriverState INSTANCE = new NoDriverState();

    @Override
    public DriverActivity activity() {
      return DriverActivity.NONE;
    }

    @Override
    public UUID requestHeadEntryId() {
      return null;
    }

    @Override
    public UUID resultEntryId() {
      return null;
    }

    @Override
    public GenerationProjection projection() {
      return null;
    }
  }

  private final class AcceptedTurn implements TurnResolution.Accepted {

    private final ModelRequestSpec spec;
    private final Integer contextWindow;
    private final Integer maxOutputTokens;
    private final TurnCapabilities capabilities;
    private final ExecutionSelection executedSelection;

    private AcceptedTurn(
        ModelRequestSpec spec,
        int contextWindow,
        int maxOutputTokens,
        TurnCapabilities capabilities,
        ExecutionSelection executedSelection) {
      this.spec = spec;
      this.contextWindow = contextWindow;
      this.maxOutputTokens = maxOutputTokens;
      this.capabilities = capabilities;
      this.executedSelection = executedSelection;
    }

    @Override
    public Integer contextWindow() {
      return contextWindow;
    }

    @Override
    public Integer maxOutputTokens() {
      return maxOutputTokens;
    }

    @Override
    public TurnCapabilities capabilities() {
      return capabilities;
    }

    @Override
    public ExecutionSelection executedSelection() {
      return executedSelection;
    }

    @Override
    public void install(
        HarnessStore.Transaction tx,
        UUID threadId,
        UUID turnStartEntryId,
        UUID requestHeadEntryId) {
      UUID invocationId = tx.nextId();
      Instant now = tx.now();
      store
          .in(tx)
          .insertModelInvocation(
              new ModelInvocation(
                  invocationId,
                  threadId,
                  turnStartEntryId,
                  requestHeadEntryId,
                  spec,
                  ModelInvocationStatus.READY,
                  0,
                  null,
                  null,
                  null,
                  null,
                  List.of(),
                  now,
                  now,
                  groupProgress(spec),
                  null));
      tx.work().request(new WorkTarget(BuiltinWorkType.MODEL, invocationId));
    }
  }

  private static ModelGroupProgress groupProgress(ModelRequestSpec spec) {
    ModelGroupExecution group = spec.groupExecution();
    return group == null
        ? null
        : new ModelGroupProgress(group.groupName(), group.groupVersion(), 0, 0);
  }
}
