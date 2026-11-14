package fun.fengwk.kkstudio.canvas.infra.function;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasFunctionUnknownResolution;
import fun.fengwk.kkstudio.canvas.CanvasFunctionVersion;
import fun.fengwk.kkstudio.canvas.NodeRecord;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenOutput;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.infra.platform.PlatformCanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.infra.platform.PluginCanvasFunctionCatalogSource;
import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasResourceLifecycle;
import fun.fengwk.kkstudio.canvas.infra.postgresql.PostgresqlCanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.infra.postgresql.PostgresqlCanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.infra.postgresql.PostgresqlCanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.infra.postgresql.PostgresqlCanvasStore;
import fun.fengwk.kkstudio.common.error.DomainException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Canvas Function 状态机的短事务边界。
 *
 * <p>外部计算不持有数据库事务。本类负责启动、提交意图、提交确认、检查点、人工核查解除、取消与终态收敛，并在同一事务内维护 Run、资源 pin 与画布版本；针对同一画布的写入按
 * document、node、run 的固定顺序加锁。
 *
 * <p>Worker 写回必须同时匹配 {@code requestId} 与 {@code leaseToken}；租约失效后不能再更新状态。启动时冻结输入资源并写入 INPUT pin；输出
 * Resource 行与 OUTPUT pin 由宿主物化事务同事务写入。成功时预分配的目标资源接管节点所有权；失败或确定取消时释放本次 Run 的 pin 并丢弃未挂接输出。
 * 外部提交结果不明时收敛为 UNKNOWN，保留 pin 与冻结计划，只能由人工核查后继续查询原任务或确认失败/取消。
 *
 * <p>锁序、行构造与状态写入下沉到 {@link CanvasFunctionRunPersistence}，启动时的冻结计划下沉到 {@link
 * CanvasFunctionRunPlan}，输出槽位校验与 核查文本规则分别由 {@link CanvasFunctionRunOutputs} 与 {@link
 * CanvasFunctionRunTexts} 承担；本类保留全部事务入口。
 */
@Component
public class CanvasFunctionRunTransactions {

  /** 目录版本围栏失败的唯一消息：客户端应刷新目录，而不是重试或退回当前同名定义。 */
  private static final String CATALOG_CHANGED_MESSAGE =
      "Canvas Function catalog changed; refresh the directory before starting this function";

  private final PostgresqlCanvasStore canvasStore;
  private final PostgresqlCanvasFunctionRunRepository runRepository;
  private final PostgresqlCanvasFunctionResourcePinRepository refRepository;
  private final PluginCanvasFunctionCatalogSource catalogSource;
  private final CanvasFunctionArgsCodec argsCodec;
  private final CanvasResourceLifecycle resourceLifecycle;
  private final CanvasFunctionRunPersistence persistence;
  private final CanvasFunctionRunPlan plan;
  private final CanvasFunctionRunOutputs outputs;
  private final CanvasFunctionRunStateCodec stateCodec;
  private final Clock clock;

  public CanvasFunctionRunTransactions(
      PostgresqlCanvasStore canvasStore,
      PostgresqlCanvasResourceRepository resourceRepository,
      PostgresqlCanvasFunctionRunRepository runRepository,
      PostgresqlCanvasFunctionResourcePinRepository refRepository,
      PluginCanvasFunctionCatalogSource catalogSource,
      CanvasFunctionArgsCodec argsCodec,
      CanvasFunctionRunStateCodec stateCodec,
      CanvasResourceLifecycle resourceLifecycle,
      PlatformCanvasFunctionBlobAccess blobAccess,
      Clock clock) {
    this.canvasStore = Objects.requireNonNull(canvasStore, "canvasStore");
    this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
    this.refRepository = Objects.requireNonNull(refRepository, "refRepository");
    this.catalogSource = Objects.requireNonNull(catalogSource, "catalogSource");
    this.argsCodec = Objects.requireNonNull(argsCodec, "argsCodec");
    this.resourceLifecycle = Objects.requireNonNull(resourceLifecycle, "resourceLifecycle");
    this.stateCodec = Objects.requireNonNull(stateCodec, "stateCodec");
    // 毫秒精度是 Run 时间的既有契约（列类型 timestamptz(3)）：同一事务内多次 clock.instant() 必须可比，
    // 且本类与持久化协作者必须共用同一个已截断时钟，否则 terminal/checkpoint 会把纳秒写进库。
    this.clock = Clock.tick(Objects.requireNonNull(clock, "clock"), Duration.ofMillis(1));
    this.persistence =
        new CanvasFunctionRunPersistence(canvasStore, runRepository, stateCodec, this.clock);
    this.plan = new CanvasFunctionRunPlan(canvasStore, resourceRepository, blobAccess);
    this.outputs = new CanvasFunctionRunOutputs(resourceRepository, blobAccess);
  }

  /**
   * 启动新的 FunctionRun；相同 {@code requestId} 已存在时直接返回。
   *
   * <p>事务内从一次目录租用冻结完整函数定义，校验客户端 {@code expectedVersion} 的定义 fingerprint（函数已移除同样是版本冲突），
   * 然后才冻结输入清单、创建输入 pin 并构造 {@code PENDING} 提交事实，同时将画布版本推进一次。未安装的插件函数无法解析，因此保持节点内容只读且不会产生新的队列事实。
   *
   * <p>目录租用一直持有到真实事务的 {@code afterCompletion}（提交与回滚都释放），而不是在方法返回前关闭：外部提交前的 preflight 与
   * 冻结计划必须始终锚定同一目录实例。
   */
  @Transactional
  public CanvasFunctionStartResult start(
      UUID canvasId, UUID nodeId, String requestId, CanvasFunctionVersion expectedVersion) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(nodeId, "nodeId");
    Objects.requireNonNull(expectedVersion, "expectedVersion");
    UUID validatedRequestId = CanvasFunctionRequestIds.validate(requestId);
    CanvasDocument document = persistence.requireDocumentForUpdate(canvasId);
    NodeRecord node =
        canvasStore
            .lockNode(canvasId, nodeId)
            .orElseThrow(() -> CanvasFunctionRunErrors.notFound("Canvas Function node not found"));
    if (node.function() == null) {
      throw DomainException.validation("nodeId", "node must be a Canvas Function");
    }
    CanvasFunctionRun existing = runRepository.findByNodeIdForUpdate(nodeId).orElse(null);
    // 精确 requestId 重放优先于版本校验：即使 expectedVersion 已过时也返回已接受结果，不再取租用、pin 或提交。
    if (existing != null && existing.requestId().equals(validatedRequestId)) {
      return new CanvasFunctionStartResult(existing, false);
    }
    if (existing != null
        && (existing.status() == CanvasFunctionRunStatus.READY
            || existing.status() == CanvasFunctionRunStatus.RUNNING
            || existing.status() == CanvasFunctionRunStatus.UNKNOWN)) {
      throw CanvasFunctionRunErrors.conflict(
          "another requestId is already active or awaiting verification for this node");
    }
    CanvasFunctionFrozenRun frozen = freeze(canvasId, node, validatedRequestId, expectedVersion);
    CanvasFunctionRun ready = readyRun(nodeId, validatedRequestId, frozen);
    if (existing != null) {
      resourceLifecycle.releaseRunPins(canvasId, nodeId, existing.requestId());
    }
    if (existing == null) {
      runRepository.insertReady(ready);
    } else if (!runRepository.replaceTerminalWithReady(ready)) {
      throw CanvasFunctionRunErrors.conflict("terminal FunctionRun was replaced concurrently");
    }
    // pin 引用真实 Run 行，必须在其之后写入，才能由 fk_canvas_pin_run 保证 pin 不会脱离 Run 存活。
    // 这里只 pin 已存在的输入资源：预分配的输出目标此时还没有 Resource 行，OUTPUT pin 由物化事务与 Resource 同事务写入。
    refRepository.addAll(
        CanvasFunctionRunPlan.inputPins(canvasId, nodeId, ready.requestId(), frozen.manifest()));
    persistence.bumpVersion(document);
    return new CanvasFunctionStartResult(ready, true);
  }

  /**
   * 在这一次目录租用内冻结定义、校验版本围栏、构造 {@code PENDING} 提交事实并做 preflight。
   *
   * <p>租用注册到事务 {@code afterCompletion} 后一直持有，因此 preflight 与冻结计划始终锚定同一目录实例；整个启动只取一次租用。
   */
  private CanvasFunctionFrozenRun freeze(
      UUID canvasId,
      NodeRecord node,
      UUID validatedRequestId,
      CanvasFunctionVersion expectedVersion) {
    PluginCanvasFunctionCatalogSource.Lease lease = catalogSource.acquire();
    registerLeaseReleaseAfterCompletion(lease);
    // 版本围栏：函数已被停用或移除、或定义与客户端看到的不一致，都是版本冲突；先于 pin、Run 写入与任何外部请求。
    CanvasFunctionCatalog.RegisteredFunction registered =
        lease
            .catalog()
            .find(node.function().name())
            .orElseThrow(() -> CanvasFunctionRunErrors.conflict(CATALOG_CHANGED_MESSAGE));
    CanvasFunctionBinding binding = new CanvasFunctionBinding(registered.function());
    CanvasFunctionDefinition definition = binding.definition();
    requireExpectedFingerprint(definition, expectedVersion);
    registered.requireAvailable();
    CanvasFunctionAdapter adapter = registered.adapter();
    JsonObject args = argsCodec.decode(node.function().argsJson(), definition);
    List<CanvasFunctionFrozenReference> manifest =
        plan.freezeManifest(canvasId, node.function().name(), args, definition.referencePolicy());
    List<CanvasFunctionFrozenOutput> frozenOutputs =
        CanvasFunctionRunPlan.freezeOutputs(node.name(), definition);
    CanvasFunctionFrozenRun frozen =
        new CanvasFunctionFrozenRun(
            canvasId,
            node.id(),
            node.name(),
            validatedRequestId,
            binding,
            args,
            manifest,
            frozenOutputs,
            CanvasFunctionSubmitState.PENDING,
            "QUEUED",
            Map.of());
    adapter.preflight(frozen);
    return frozen;
  }

  /** 客户端看到的函数定义必须与即将执行的定义一致，失败给固定 409 消息。 */
  private static void requireExpectedFingerprint(
      CanvasFunctionDefinition definition, CanvasFunctionVersion expectedVersion) {
    if (!expectedVersion
        .definitionFingerprint()
        .equals(CanvasFunctionCatalog.definitionFingerprint(definition))) {
      throw CanvasFunctionRunErrors.conflict(CATALOG_CHANGED_MESSAGE);
    }
  }

  /** 注册成功后由事务负责释放租用；提交与回滚之前都保持同一冻结目录可用。 */
  private static void registerLeaseReleaseAfterCompletion(
      PluginCanvasFunctionCatalogSource.Lease lease) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      lease.close();
      throw new IllegalStateException(
          "Canvas Function start must run inside an active transaction so the catalog lease is held"
              + " until afterCompletion");
    }
    try {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              lease.close();
            }
          });
    } catch (RuntimeException | Error error) {
      // 尚未转交事务；释放失败不得遮蔽原始异常。
      try (lease) {
        throw error;
      }
    }
  }

  /** READY 行：冻结计划决定 stage 与 stateJson，attempt 从 0 开始。 */
  private CanvasFunctionRun readyRun(
      UUID nodeId, UUID validatedRequestId, CanvasFunctionFrozenRun frozen) {
    Instant now = clock.instant();
    return new CanvasFunctionRun(
        nodeId,
        validatedRequestId,
        CanvasFunctionRunStatus.READY,
        0,
        frozen.stage(),
        stateCodec.initial(frozen),
        null,
        now,
        now);
  }

  /**
   * 在外部提交前持久化提交意图。
   *
   * <p>之后任何崩溃都会让 Run 以 {@code SUBMITTING} 恢复，从而进入人工核查路径，绝不会被自动重新提交。
   */
  @Transactional
  public CanvasFunctionFrozenRun beginSubmit(CanvasFunctionFrozenRun run, UUID leaseToken) {
    return advanceSubmit(run, leaseToken, CanvasFunctionSubmitState.SUBMITTING);
  }

  /** 外部提交已被确认后持久化可查询的任务身份，使恢复路径只查询原任务。 */
  @Transactional
  public CanvasFunctionFrozenRun confirmSubmitted(CanvasFunctionFrozenRun run, UUID leaseToken) {
    return advanceSubmit(run, leaseToken, CanvasFunctionSubmitState.SUBMITTED);
  }

  /** 将活跃 Run 收敛为 CANCELLED，并清理未挂接输出和本次 Run 的 pin。 */
  @Transactional
  public CanvasFunctionRun cancel(UUID canvasId, UUID nodeId, String requestId) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(nodeId, "nodeId");
    UUID validatedRequestId = CanvasFunctionRequestIds.validate(requestId);
    CanvasDocument document = persistence.requireDocumentForUpdate(canvasId);
    canvasStore
        .lockNode(canvasId, nodeId)
        .orElseThrow(() -> CanvasFunctionRunErrors.notFound("Canvas Function node not found"));
    CanvasFunctionRun current =
        runRepository
            .findByNodeIdForUpdate(nodeId)
            .orElseThrow(() -> CanvasFunctionRunErrors.notFound("Canvas Function run not found"));
    if (!current.requestId().equals(validatedRequestId)) {
      throw CanvasFunctionRunErrors.conflict("requestId does not match the current FunctionRun");
    }
    if (current.status() == CanvasFunctionRunStatus.UNKNOWN) {
      throw CanvasFunctionRunErrors.conflict("UNKNOWN FunctionRun must be resolved, not cancelled");
    }
    if (current.status() != CanvasFunctionRunStatus.READY
        && current.status() != CanvasFunctionRunStatus.RUNNING) {
      return current;
    }
    CanvasFunctionFrozenRun frozen = persistence.decode(current);
    CanvasFunctionFrozenRun cancelled =
        stateCodec.transition(frozen, frozen.submitState(), "CANCELLED", frozen.adapterState());
    CanvasFunctionRun terminal =
        persistence.terminal(current, CanvasFunctionRunStatus.CANCELLED, cancelled, null);
    if (!runRepository.cancelActive(terminal)) {
      throw CanvasFunctionRunErrors.conflict("FunctionRun changed while cancelling");
    }
    // 先释放 pin（含物化后的 OUTPUT pin）再回收无 owner 目标行，满足 pin→resource 的删除限制。
    resourceLifecycle.releaseRunPins(canvasId, nodeId, current.requestId());
    resourceLifecycle.discardUnownedTargets(canvasId, frozen.outputResourceIds());
    persistence.bumpVersion(document);
    return terminal;
  }

  /**
   * 人工核查 UNKNOWN Run 后解除：继续查询原任务，或确认失败/取消。
   *
   * <p>核查事实必须非空并会被持久化；RESUME 只会把提交事实推进为 {@code SUBMITTED} 并重新进入 READY，因此不会重新提交外部任务。
   */
  @Transactional
  public CanvasFunctionRun resolve(
      UUID canvasId,
      UUID nodeId,
      String requestId,
      CanvasFunctionUnknownResolution resolution,
      String verification) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(nodeId, "nodeId");
    if (resolution == null) {
      throw DomainException.validation("resolution", "resolution is required");
    }
    UUID validatedRequestId = CanvasFunctionRequestIds.validate(requestId);
    String validatedVerification = CanvasFunctionRunTexts.validateVerification(verification);
    CanvasDocument document = persistence.requireDocumentForUpdate(canvasId);
    canvasStore
        .lockNode(canvasId, nodeId)
        .orElseThrow(() -> CanvasFunctionRunErrors.notFound("Canvas Function node not found"));
    CanvasFunctionRun current =
        runRepository
            .findByNodeIdForUpdate(nodeId)
            .orElseThrow(() -> CanvasFunctionRunErrors.notFound("Canvas Function run not found"));
    if (!current.requestId().equals(validatedRequestId)) {
      throw CanvasFunctionRunErrors.conflict("requestId does not match the current FunctionRun");
    }
    if (current.status() != CanvasFunctionRunStatus.UNKNOWN) {
      throw CanvasFunctionRunErrors.conflict("only an UNKNOWN FunctionRun can be resolved");
    }
    CanvasFunctionFrozenRun frozen = persistence.decode(current);
    return switch (resolution) {
      case RESUME -> resumeUnknown(document, current, frozen);
      case FAILED -> resolveUnknownTerminal(
          document, current, frozen, CanvasFunctionRunStatus.FAILED, validatedVerification);
      case CANCELLED -> resolveUnknownTerminal(
          document, current, frozen, CanvasFunctionRunStatus.CANCELLED, validatedVerification);
    };
  }

  /**
   * 写入运行阶段快照。
   *
   * <p>文档、节点或租约不再匹配时抛出 {@link CanvasFunctionInternalCancellation}，通知当前 Worker 停止写回。
   */
  @Transactional
  public CanvasFunctionFrozenRun checkpoint(
      UUID canvasId,
      UUID nodeId,
      UUID requestId,
      UUID leaseToken,
      String stage,
      Map<String, Object> adapterState) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(nodeId, "nodeId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(adapterState, "adapterState");
    CanvasDocument document = persistence.requireDocumentForUpdate(canvasId);
    persistence.requireLiveFunctionNode(canvasId, nodeId);
    CanvasFunctionRun current = persistence.requireClaim(nodeId, requestId, leaseToken);
    CanvasFunctionFrozenRun next =
        stateCodec.checkpoint(persistence.decode(current), stage, adapterState);
    persistence.persistRunningState(current, next, leaseToken);
    persistence.bumpVersion(document);
    return next;
  }

  /** 提交结果不明：收敛为 UNKNOWN，保留 pin 与冻结计划，退出自动调度。 */
  @Transactional
  public boolean markUnknown(UUID nodeId, UUID requestId, UUID leaseToken, String reason) {
    Objects.requireNonNull(nodeId, "nodeId");
    Objects.requireNonNull(requestId, "requestId");
    String validatedReason = CanvasFunctionRunTexts.validateUnknownReason(reason);
    CanvasFunctionRun observed = runRepository.findByNodeId(nodeId).orElse(null);
    if (!persistence.matchesClaim(observed, requestId, leaseToken)) {
      return false;
    }
    CanvasFunctionFrozenRun observedFrozen = persistence.decode(observed);
    CanvasDocument document = persistence.requireDocumentForUpdate(observedFrozen.canvasId());
    if (!persistence.functionNodePresent(observedFrozen.canvasId(), nodeId)) {
      return false;
    }
    CanvasFunctionRun current = persistence.requireClaim(nodeId, requestId, leaseToken);
    CanvasFunctionFrozenRun frozen = persistence.decode(current);
    CanvasFunctionFrozenRun unknown =
        stateCodec.transition(frozen, frozen.submitState(), "UNKNOWN", frozen.adapterState());
    CanvasFunctionRun terminal =
        persistence.terminal(current, CanvasFunctionRunStatus.UNKNOWN, unknown, validatedReason);
    if (!runRepository.markUnknown(terminal, leaseToken)) {
      throw new IllegalStateException("FunctionRun UNKNOWN CAS failed after row lock");
    }
    // UNKNOWN 保留本次 pin 与预分配目标：自动调度已退出，未核查前不能按普通失败清理。
    persistence.bumpVersion(document);
    return true;
  }

  /**
   * 将 Run 收敛为 SUCCEEDED。
   *
   * <p>输出必须是启动时预分配的目标资源，且媒体类型与冻结函数定义一致；发布是原子的，不允许部分数组。
   */
  @Transactional
  public boolean completeSuccess(
      CanvasFunctionFrozenRun frozen, UUID leaseToken, List<UUID> orderedResourceIds) {
    Objects.requireNonNull(frozen, "frozen");
    if (!orderedResourceIds.equals(frozen.outputResourceIds())) {
      throw new IllegalArgumentException(
          "adapter result must equal the frozen output plan, in order");
    }
    CanvasDocument document = persistence.requireDocumentForUpdate(frozen.canvasId());
    NodeRecord node = canvasStore.lockNode(frozen.canvasId(), frozen.nodeId()).orElse(null);
    if (node == null || node.function() == null) {
      return false;
    }
    CanvasFunctionRun current = runRepository.findByNodeIdForUpdate(frozen.nodeId()).orElse(null);
    if (!persistence.matchesClaim(current, frozen.requestId(), leaseToken)) {
      return false;
    }
    for (CanvasFunctionFrozenOutput planned : frozen.outputs()) {
      outputs.requireMaterializedOutput(frozen, planned);
    }
    resourceLifecycle.replaceOwnedWithTargets(
        frozen.canvasId(), frozen.nodeId(), orderedResourceIds);
    CanvasFunctionFrozenRun succeeded =
        stateCodec.transition(frozen, frozen.submitState(), "SUCCEEDED", frozen.adapterState());
    CanvasFunctionRun terminal =
        persistence.terminal(current, CanvasFunctionRunStatus.SUCCEEDED, succeeded, null);
    if (!runRepository.transitionTerminal(terminal, leaseToken)) {
      throw new IllegalStateException("FunctionRun success CAS failed after row lock");
    }
    resourceLifecycle.releaseRunPins(frozen.canvasId(), frozen.nodeId(), frozen.requestId());
    persistence.bumpVersion(document);
    return true;
  }

  /** 在租约仍有效时将 Run 收敛为 FAILED，并清理未挂接输出和本次 Run 的 pin。 */
  @Transactional
  public boolean failIfRunning(UUID nodeId, UUID requestId, UUID leaseToken, String error) {
    Objects.requireNonNull(nodeId, "nodeId");
    Objects.requireNonNull(requestId, "requestId");
    CanvasFunctionRun observed = runRepository.findByNodeId(nodeId).orElse(null);
    if (!persistence.matchesClaim(observed, requestId, leaseToken)) {
      return false;
    }
    CanvasFunctionFrozenRun observedFrozen = persistence.decode(observed);
    CanvasDocument document = persistence.requireDocumentForUpdate(observedFrozen.canvasId());
    if (!persistence.functionNodePresent(observedFrozen.canvasId(), nodeId)) {
      return false;
    }
    CanvasFunctionRun current = persistence.requireClaim(nodeId, requestId, leaseToken);
    CanvasFunctionFrozenRun frozen = persistence.decode(current);
    CanvasFunctionFrozenRun failed =
        stateCodec.transition(frozen, frozen.submitState(), "FAILED", frozen.adapterState());
    CanvasFunctionRun terminal =
        persistence.terminal(current, CanvasFunctionRunStatus.FAILED, failed, error);
    if (!runRepository.transitionTerminal(terminal, leaseToken)) {
      throw new IllegalStateException("FunctionRun failure CAS failed after row lock");
    }
    resourceLifecycle.releaseRunPins(frozen.canvasId(), frozen.nodeId(), frozen.requestId());
    resourceLifecycle.discardUnownedTargets(frozen.canvasId(), frozen.outputResourceIds());
    persistence.bumpVersion(document);
    return true;
  }

  /** 推进外部提交事实；CAS 与 checkpoint 一致，旧租约只能得到内部取消。 */
  private CanvasFunctionFrozenRun advanceSubmit(
      CanvasFunctionFrozenRun run, UUID leaseToken, CanvasFunctionSubmitState nextState) {
    Objects.requireNonNull(run, "run");
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(nextState, "nextState");
    CanvasDocument document = persistence.requireDocumentForUpdate(run.canvasId());
    persistence.requireLiveFunctionNode(run.canvasId(), run.nodeId());
    CanvasFunctionRun current = persistence.requireClaim(run.nodeId(), run.requestId(), leaseToken);
    CanvasFunctionFrozenRun observed = persistence.decode(current);
    if (observed.submitState() != CanvasFunctionSubmitState.PENDING
        && nextState == CanvasFunctionSubmitState.SUBMITTING) {
      throw new IllegalStateException("submit intent must be persisted once per FunctionRun");
    }
    if (observed.submitState() != CanvasFunctionSubmitState.SUBMITTING
        && nextState == CanvasFunctionSubmitState.SUBMITTED) {
      throw new IllegalStateException("submission must be confirmed only after its intent");
    }
    CanvasFunctionFrozenRun next =
        stateCodec.transition(observed, nextState, nextState.name(), observed.adapterState());
    persistence.persistRunningState(current, next, leaseToken);
    persistence.bumpVersion(document);
    return next;
  }

  private CanvasFunctionRun resumeUnknown(
      CanvasDocument document, CanvasFunctionRun current, CanvasFunctionFrozenRun frozen) {
    CanvasFunctionFrozenRun resumed =
        stateCodec.transition(
            frozen, CanvasFunctionSubmitState.SUBMITTED, "SUBMITTED", frozen.adapterState());
    Instant now = clock.instant();
    CanvasFunctionRun ready =
        new CanvasFunctionRun(
            current.nodeId(),
            current.requestId(),
            CanvasFunctionRunStatus.READY,
            current.attempt(),
            resumed.stage(),
            stateCodec.encode(resumed),
            null,
            now,
            current.createdAt());
    if (!runRepository.resumeUnknown(ready)) {
      throw CanvasFunctionRunErrors.conflict("FunctionRun changed while resolving");
    }
    // RESUME 只重新调度查询原任务，pin 与预分配目标保持不动。
    persistence.bumpVersion(document);
    return ready;
  }

  private CanvasFunctionRun resolveUnknownTerminal(
      CanvasDocument document,
      CanvasFunctionRun current,
      CanvasFunctionFrozenRun frozen,
      CanvasFunctionRunStatus status,
      String verification) {
    CanvasFunctionFrozenRun resolved =
        stateCodec.transition(frozen, frozen.submitState(), status.name(), frozen.adapterState());
    CanvasFunctionRun terminal = persistence.terminal(current, status, resolved, verification);
    if (!runRepository.resolveUnknownTerminal(terminal)) {
      throw CanvasFunctionRunErrors.conflict("FunctionRun changed while resolving");
    }
    resourceLifecycle.releaseRunPins(frozen.canvasId(), frozen.nodeId(), frozen.requestId());
    resourceLifecycle.discardUnownedTargets(frozen.canvasId(), frozen.outputResourceIds());
    persistence.bumpVersion(document);
    return terminal;
  }
}
