package fun.fengwk.kkstudio.harness.builtin.driver;

import fun.fengwk.kkstudio.harness.builtin.compaction.AutomaticCompactionPlanner;
import fun.fengwk.kkstudio.harness.builtin.history.ModelAttemptMaterialization;
import fun.fengwk.kkstudio.harness.builtin.model.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.builtin.model.ModelGroupExecution;
import fun.fengwk.kkstudio.harness.builtin.model.ModelGroupProgress;
import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.builtin.model.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.builtin.port.TurnResolver;
import fun.fengwk.kkstudio.harness.builtin.processor.ModelAttemptFailureAppender;
import fun.fengwk.kkstudio.harness.builtin.processor.ModelOutcomeAppender;
import fun.fengwk.kkstudio.harness.builtin.request.ResolvedRequestValidator;
import fun.fengwk.kkstudio.harness.builtin.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.driver.AgentDriver;
import fun.fengwk.kkstudio.harness.runtime.driver.CompactionResolution;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverActivity;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverApplied;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverCommit;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverStopResult;
import fun.fengwk.kkstudio.harness.runtime.driver.FailureProjection;
import fun.fengwk.kkstudio.harness.runtime.driver.GenerationProjection;
import fun.fengwk.kkstudio.harness.runtime.driver.GenerationStatus;
import fun.fengwk.kkstudio.harness.runtime.driver.LockedDriverState;
import fun.fengwk.kkstudio.harness.runtime.driver.TurnResolution;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnCapabilities;
import fun.fengwk.kkstudio.harness.runtime.history.TurnMediaCapabilities;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolveTransientException;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Builtin Agent Driver：拥有 Builtin 模型状态、Turn 解析与响应物化，Runtime 只经 {@link AgentDriver} 语义方法调用。
 *
 * <p>所有方法都在 Runtime 已开启的事务内执行；模型状态通过 {@link BuiltinStore#in(HarnessStore.Transaction)}
 * 与公共历史写入共享同一物理事务。Provider I/O 不在此类中执行，由现有 MODEL Work 路径负责。
 */
public final class BuiltinAgentDriver implements AgentDriver {

  private static final String CANCELLED_MESSAGE = "Cancelled by user";

  private final BuiltinStore store;
  private final TurnResolver resolver;
  private final ModelOutcomeAppender appender = new ModelOutcomeAppender();
  private final AutomaticCompactionPlanner compactionPlanner = new AutomaticCompactionPlanner();

  public BuiltinAgentDriver(BuiltinStore store, TurnResolver resolver) {
    this.store = Objects.requireNonNull(store, "store");
    this.resolver = Objects.requireNonNull(resolver, "resolver");
  }

  @Override
  public TurnResolution resolveTurn(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath candidatePath,
      CompactionPreparation preparation) {
    TurnResolver.Result result;
    try {
      result = resolver.resolve(thread.id(), candidatePath, preparation);
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
              resolved.executionModel(),
              resolved.outputBudget(),
              resolved.contextWindow(),
              resolved.maxOutputTokens(),
              resolved.childSettings()));
      case TurnResolver.Resolved resolved -> accepted(thread, candidatePath, resolved);
    };
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
    if (cap != null || constraints.promptCacheDisabled()) {
      spec =
          new ModelRequestSpec(
              spec.providerType(),
              spec.providerConnectionGenerationId(),
              spec.model(),
              spec.variant(),
              cap == null ? spec.outputTokens() : Math.min(spec.outputTokens(), cap),
              spec.systemInstruction(),
              spec.toolBindings(),
              spec.subagentBindings(),
              constraints.promptCacheDisabled() ? ProviderCacheControl.none() : spec.cacheControl(),
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
        spec.groupExecution() == null
            ? ModelSelection.model(spec.model().modelName(), spec.variant().id())
            : ModelSelection.group(spec.groupExecution().groupName()));
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
    public UUID executionId() {
      // Runtime 也以该身份定位 Stop 时必须清掉的 MODEL Work；tool phase 中 generation 已 attached，
      // activity 不再是 ACTIVE，但 Work 仍归属该 generation。
      return model.id();
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
    private final ModelSelection executedSelection;

    private AcceptedTurn(
        ModelRequestSpec spec,
        int contextWindow,
        int maxOutputTokens,
        TurnCapabilities capabilities,
        ModelSelection executedSelection) {
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
    public ModelSelection executedSelection() {
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
                  null,
                  groupProgress(spec)));
      tx.work().request(new WorkTarget(WorkTargetType.MODEL, invocationId));
    }
  }

  private static ModelGroupProgress groupProgress(ModelRequestSpec spec) {
    ModelGroupExecution group = spec.groupExecution();
    return group == null
        ? null
        : new ModelGroupProgress(group.groupName(), group.groupVersion(), 0, 0);
  }
}
