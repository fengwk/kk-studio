package fun.fengwk.kkstudio.harness.builtin.driver;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.SystemPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnCapabilities;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.BuiltinWorkType;
import fun.fengwk.kkstudio.harness.builtin.port.TurnResolver;
import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelAttemptMaterialization;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.builtin.processor.ModelOutcomeAppender;
import fun.fengwk.kkstudio.harness.builtin.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.builtin.request.ResolvedRequestValidator;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Builtin Agent Driver：拥有 Builtin 模型状态、Turn 解析与响应物化，Runtime 只经 {@link AgentDriver} 语义方法调用。
 *
 * <p>所有方法都在 Runtime 已开启的事务内执行；模型状态通过 {@link BuiltinStore#in(HarnessStore.Transaction)}
 * 与公共历史写入共享同一物理事务。Provider I/O 不在此类中执行，由 Builtin 注册的 MODEL Work 处理器负责。system change 计算、turn
 * 安装、状态投影与 Stop 收敛分别是同包协作者。
 */
public final class BuiltinAgentDriver implements AgentDriver {

  private final BuiltinStore store;
  private final TurnResolver resolver;
  private final ModelProcessor processor;
  private final ModelOutcomeAppender appender = new ModelOutcomeAppender();

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
      UUID rootSessionId = rootSessionId(tx, thread);
      result =
          thread.kind() == ThreadKind.COMPACTION && preparation == null
              ? resolver.resolveCompactionBranch(thread.id(), rootSessionId, candidatePath)
              : resolver.resolve(thread.id(), rootSessionId, candidatePath, preparation);
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
      case TurnResolver.CompactionRequired required -> new TurnResolution.CompactionRequired(
          required.conversationContextWindow());
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
    ModelRequestSpec spec = capOutputTokens(resolved.spec(), thread.constraints());
    // 无 cap 时保留 resolver 的模型上限元数据；有 cap 时不得超过实际请求预算。
    int maxOutputTokens =
        thread.constraints().maxOutputTokens() == null
            ? resolved.maxOutputTokens()
            : Math.min(resolved.maxOutputTokens(), spec.outputTokens());
    return new BuiltinAcceptedTurn(
        store,
        spec,
        resolved.contextWindow(),
        maxOutputTokens,
        new TurnCapabilities(spec.subagentBindings(), mediaCapabilities(spec)),
        BuiltinExecution.of(executedSelection(spec)),
        systemChange(thread, candidatePath, spec));
  }

  /** Thread 级输出上限只收紧请求预算，不改变冻结的模型身份。 */
  private static ModelRequestSpec capOutputTokens(
      ModelRequestSpec spec, ThreadConstraints constraints) {
    Integer cap = constraints.maxOutputTokens();
    if (cap == null || cap >= spec.outputTokens()) {
      return spec;
    }
    return new ModelRequestSpec(
        spec.providerType(),
        spec.model(),
        spec.variant(),
        Math.min(spec.outputTokens(), cap),
        spec.systemInstruction(),
        spec.toolBindings(),
        spec.subagentBindings(),
        spec.groupExecution(),
        spec.systemSections());
  }

  private static TurnMediaCapabilities mediaCapabilities(ModelRequestSpec spec) {
    return new TurnMediaCapabilities(
        spec.model().inputModalities(),
        spec.providerType().mediaCapabilities().userModalities(),
        spec.providerType().mediaCapabilities().toolResultModalities());
  }

  private static ModelSelection executedSelection(ModelRequestSpec spec) {
    return spec.groupExecution() == null
        ? ModelSelection.model(spec.model().modelName(), spec.variant().id())
        : ModelSelection.group(spec.groupExecution().groupName());
  }

  static SystemPayload systemChange(ThreadState thread, EntryPath path, ModelRequestSpec spec) {
    return BuiltinSystemContext.change(thread, path, spec);
  }

  @Override
  public LockedDriverState readState(
      HarnessStore.Transaction tx, ThreadState thread, EntryCurrentState state) {
    return BuiltinState.read(store, tx, thread, state);
  }

  @Override
  public LockedDriverState lockState(
      HarnessStore.Transaction tx, ThreadState thread, EntryCurrentState state) {
    return BuiltinState.lock(store, tx, thread, state);
  }

  @Override
  public DriverApplied applyResponse(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      LockedDriverState state,
      DriverCommit commit) {
    BuiltinState builtin = requireBuiltin(state);
    if (builtin.activity() != DriverActivity.RESPONSE_PENDING) {
      throw new IllegalStateException(
          "builtin response apply requires a pending terminal generation");
    }
    ModelInvocation model = builtin.model();
    if (!model.threadId().equals(thread.id())
        || !thread.headEntryId().equals(model.requestHeadEntryId())) {
      throw new IllegalStateException(
          "terminal builtin generation preconditions changed under the same transaction");
    }
    ModelOutcomeAppender.Applied applied =
        appender.appendModel(
            store.in(tx), path, model, commit.now(), thread.kind() == ThreadKind.COMPACTION);
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
    ModelInvocation model = builtin.model();
    UUID assistantId = path.head().id();
    if (model.resultEntryId() == null || !model.resultEntryId().equals(assistantId)) {
      throw new IllegalStateException("builtin generation does not own the locked tool batch");
    }
    ModelAttemptMaterialization.validateAttached(model, path);
    store.in(tx).deleteModelInvocation(model.id());
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
    return new BuiltinStop(store, tx).stop(path, builtin, commit);
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
}
