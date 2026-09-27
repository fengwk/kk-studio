package fun.fengwk.kkstudio.canvas.infra.function;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePin;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.CanvasResourceLifecycle;
import fun.fengwk.kkstudio.canvas.CanvasResourceReference;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.canvas.CanvasStore.NodeRecord;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionArgsCodecPort;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBlobAccess.BlobFacts;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunException;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunStateCodecPort;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionUnknownResolution;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
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
 */
@Component
public class CanvasFunctionRunTransactions {

  private static final int MAX_UNKNOWN_REASON_LENGTH = 1024;
  private static final int MAX_VERIFICATION_LENGTH = 1024;

  private final CanvasStore canvasStore;
  private final CanvasResourceRepository resourceRepository;
  private final CanvasFunctionRunRepository runRepository;
  private final CanvasFunctionResourcePinRepository refRepository;
  private final CanvasFunctionCatalog catalog;
  private final CanvasFunctionArgsCodecPort argsCodec;
  private final CanvasFunctionRunStateCodecPort stateCodec;
  private final CanvasResourceLifecycle resourceLifecycle;
  private final CanvasFunctionBlobAccess blobAccess;
  private final Clock clock;

  public CanvasFunctionRunTransactions(
      CanvasStore canvasStore,
      CanvasResourceRepository resourceRepository,
      CanvasFunctionRunRepository runRepository,
      CanvasFunctionResourcePinRepository refRepository,
      CanvasFunctionCatalog catalog,
      CanvasFunctionArgsCodecPort argsCodec,
      CanvasFunctionRunStateCodecPort stateCodec,
      CanvasResourceLifecycle resourceLifecycle,
      CanvasFunctionBlobAccess blobAccess,
      Clock clock) {
    this.canvasStore = Objects.requireNonNull(canvasStore, "canvasStore");
    this.resourceRepository = Objects.requireNonNull(resourceRepository, "resourceRepository");
    this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
    this.refRepository = Objects.requireNonNull(refRepository, "refRepository");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.argsCodec = Objects.requireNonNull(argsCodec, "argsCodec");
    this.stateCodec = Objects.requireNonNull(stateCodec, "stateCodec");
    this.resourceLifecycle = Objects.requireNonNull(resourceLifecycle, "resourceLifecycle");
    this.blobAccess = Objects.requireNonNull(blobAccess, "blobAccess");
    this.clock = Clock.tick(Objects.requireNonNull(clock, "clock"), Duration.ofMillis(1));
  }

  /**
   * 启动新的 FunctionRun；相同 {@code requestId} 已存在时直接返回。
   *
   * <p>事务内冻结输入清单、创建输入 pin 并构造 {@code PENDING} 提交事实，同时将画布版本推进一次。未安装的插件函数无法解析，因此保持节点内容只读且 不会产生新的队列事实。
   */
  @Transactional
  public CanvasFunctionStartResult start(UUID canvasId, UUID nodeId, String requestId) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(nodeId, "nodeId");
    String validatedRequestId = CanvasFunctionRequestIds.validate(requestId);
    CanvasDocument document = requireDocumentForUpdate(canvasId);
    NodeRecord node =
        canvasStore
            .lockNode(canvasId, nodeId)
            .orElseThrow(() -> notFound("Canvas Function node not found"));
    if (node.function() == null) {
      throw new IllegalArgumentException("node must be a Canvas Function");
    }
    CanvasFunctionRun existing = runRepository.findByNodeIdForUpdate(nodeId).orElse(null);
    if (existing != null && existing.requestId().toString().equals(validatedRequestId)) {
      return new CanvasFunctionStartResult(existing, false);
    }
    if (existing != null
        && (existing.status() == CanvasFunctionRunStatus.READY
            || existing.status() == CanvasFunctionRunStatus.RUNNING
            || existing.status() == CanvasFunctionRunStatus.UNKNOWN)) {
      throw conflict("another requestId is already active or awaiting verification for this node");
    }

    CanvasFunctionCatalog.RegisteredFunction registered = catalog.require(node.function().name());
    registered.requireAvailable();
    CanvasFunctionAdapter adapter = registered.adapter();
    CanvasFunctionDefinition definition = registered.function();
    JsonObject args = argsCodec.decode(node.function().argsJson(), definition);
    List<CanvasFunctionFrozenReference> manifest =
        freezeManifest(
            canvasId, nodeId, node.function().name(), args, definition.referencePolicy());
    UUID targetResourceId = UUID.randomUUID();
    CanvasFunctionFrozenRun frozen =
        new CanvasFunctionFrozenRun(
            canvasId,
            nodeId,
            node.name(),
            UUID.fromString(validatedRequestId),
            definition,
            args,
            manifest,
            outputName(node.name(), definition.outputKind()),
            targetResourceId,
            CanvasFunctionSubmitState.PENDING,
            "QUEUED",
            Map.of());
    adapter.preflight(frozen);
    String stateJson = stateCodec.initial(frozen);
    Instant now = clock.instant();
    CanvasFunctionRun ready =
        new CanvasFunctionRun(
            nodeId,
            UUID.fromString(validatedRequestId),
            CanvasFunctionRunStatus.READY,
            0,
            now,
            null,
            null,
            frozen.stage(),
            stateJson,
            null,
            now,
            now);
    if (existing != null) {
      resourceLifecycle.releaseRunPins(canvasId, nodeId, existing.requestId());
    }
    if (existing == null) {
      runRepository.insertReady(ready);
    } else if (!runRepository.replaceTerminalWithReady(ready)) {
      throw conflict("terminal FunctionRun was replaced concurrently");
    }
    // pin 引用真实 Run 行，必须在其之后写入，才能由 fk_canvas_pin_run 保证 pin 不会脱离 Run 存活。
    // 这里只 pin 已存在的输入资源：预分配的输出目标此时还没有 Resource 行，OUTPUT pin 由物化事务与 Resource 同事务写入。
    refRepository.addAll(inputPins(canvasId, nodeId, ready.requestId(), manifest));
    bumpVersion(document);
    return new CanvasFunctionStartResult(ready, true);
  }

  /**
   * 在外部提交前持久化提交意图。
   *
   * <p>之后任何崩溃都会让 Run 以 {@code SUBMITTING} 恢复，从而进入人工核查路径，绝不会被自动重新提交。
   */
  @Transactional
  public CanvasFunctionFrozenRun beginSubmit(CanvasFunctionFrozenRun run, String leaseToken) {
    return advanceSubmit(run, leaseToken, CanvasFunctionSubmitState.SUBMITTING);
  }

  /** 外部提交已被确认后持久化可查询的任务身份，使恢复路径只查询原任务。 */
  @Transactional
  public CanvasFunctionFrozenRun confirmSubmitted(CanvasFunctionFrozenRun run, String leaseToken) {
    return advanceSubmit(run, leaseToken, CanvasFunctionSubmitState.SUBMITTED);
  }

  /** 将活跃 Run 收敛为 CANCELLED，并清理未挂接输出和本次 Run 的 pin。 */
  @Transactional
  public CanvasFunctionRun cancel(UUID canvasId, UUID nodeId, String requestId) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(nodeId, "nodeId");
    String validatedRequestId = CanvasFunctionRequestIds.validate(requestId);
    CanvasDocument document = requireDocumentForUpdate(canvasId);
    canvasStore
        .lockNode(canvasId, nodeId)
        .orElseThrow(() -> notFound("Canvas Function node not found"));
    CanvasFunctionRun current =
        runRepository
            .findByNodeIdForUpdate(nodeId)
            .orElseThrow(() -> notFound("Canvas Function run not found"));
    if (!current.requestId().toString().equals(validatedRequestId)) {
      throw conflict("requestId does not match the current FunctionRun");
    }
    if (current.status() == CanvasFunctionRunStatus.UNKNOWN) {
      throw conflict("UNKNOWN FunctionRun must be resolved, not cancelled");
    }
    if (current.status() != CanvasFunctionRunStatus.READY
        && current.status() != CanvasFunctionRunStatus.RUNNING) {
      return current;
    }
    CanvasFunctionFrozenRun frozen = decode(current);
    CanvasFunctionFrozenRun cancelled =
        stateCodec.transition(frozen, frozen.submitState(), "CANCELLED", frozen.adapterState());
    CanvasFunctionRun terminal =
        terminal(current, CanvasFunctionRunStatus.CANCELLED, cancelled, null);
    if (!runRepository.cancelActive(terminal)) {
      throw conflict("FunctionRun changed while cancelling");
    }
    // 先释放 pin（含物化后的 OUTPUT pin）再回收无 owner 目标行，满足 pin→resource 的删除限制。
    resourceLifecycle.releaseRunPins(canvasId, nodeId, current.requestId());
    resourceLifecycle.discardUnownedTarget(canvasId, frozen.targetResourceId());
    bumpVersion(document);
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
    Objects.requireNonNull(resolution, "resolution");
    String validatedRequestId = CanvasFunctionRequestIds.validate(requestId);
    String validatedVerification = validateVerification(verification);
    CanvasDocument document = requireDocumentForUpdate(canvasId);
    canvasStore
        .lockNode(canvasId, nodeId)
        .orElseThrow(() -> notFound("Canvas Function node not found"));
    CanvasFunctionRun current =
        runRepository
            .findByNodeIdForUpdate(nodeId)
            .orElseThrow(() -> notFound("Canvas Function run not found"));
    if (!current.requestId().toString().equals(validatedRequestId)) {
      throw conflict("requestId does not match the current FunctionRun");
    }
    if (current.status() != CanvasFunctionRunStatus.UNKNOWN) {
      throw conflict("only an UNKNOWN FunctionRun can be resolved");
    }
    CanvasFunctionFrozenRun frozen = decode(current);
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
      String requestId,
      String leaseToken,
      String stage,
      Map<String, Object> adapterState) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(nodeId, "nodeId");
    String validatedRequestId = CanvasFunctionRequestIds.validate(requestId);
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(adapterState, "adapterState");
    CanvasDocument document = requireDocumentForUpdate(canvasId);
    requireLiveFunctionNode(canvasId, nodeId);
    CanvasFunctionRun current = requireClaim(nodeId, validatedRequestId, leaseToken);
    CanvasFunctionFrozenRun next = stateCodec.checkpoint(decode(current), stage, adapterState);
    persistRunningState(current, next, leaseToken);
    bumpVersion(document);
    return next;
  }

  /** 提交结果不明：收敛为 UNKNOWN，保留 pin 与冻结计划，退出自动调度。 */
  @Transactional
  public boolean markUnknown(UUID nodeId, String requestId, String leaseToken, String reason) {
    Objects.requireNonNull(nodeId, "nodeId");
    Objects.requireNonNull(requestId, "requestId");
    String validatedReason = validateUnknownReason(reason);
    CanvasFunctionRun observed = runRepository.findByNodeId(nodeId).orElse(null);
    if (!matchesClaim(observed, requestId, leaseToken)) {
      return false;
    }
    CanvasFunctionFrozenRun observedFrozen = decode(observed);
    CanvasDocument document = requireDocumentForUpdate(observedFrozen.canvasId());
    if (!functionNodePresent(observedFrozen.canvasId(), nodeId)) {
      return false;
    }
    CanvasFunctionRun current = requireClaim(nodeId, requestId, leaseToken);
    CanvasFunctionFrozenRun frozen = decode(current);
    CanvasFunctionFrozenRun unknown =
        stateCodec.transition(frozen, frozen.submitState(), "UNKNOWN", frozen.adapterState());
    CanvasFunctionRun terminal =
        terminal(current, CanvasFunctionRunStatus.UNKNOWN, unknown, validatedReason);
    if (!runRepository.markUnknown(terminal, leaseToken)) {
      throw new IllegalStateException("FunctionRun UNKNOWN CAS failed after row lock");
    }
    // UNKNOWN 保留本次 pin 与预分配目标：自动调度已退出，未核查前不能按普通失败清理。
    bumpVersion(document);
    return true;
  }

  /**
   * 将 Run 收敛为 SUCCEEDED。
   *
   * <p>输出必须是启动时预分配的目标资源，且媒体类型与冻结函数定义一致；发布是原子的，不允许部分数组。
   */
  @Transactional
  public boolean completeSuccess(
      CanvasFunctionFrozenRun frozen, String leaseToken, List<UUID> orderedResourceIds) {
    Objects.requireNonNull(frozen, "frozen");
    if (!orderedResourceIds.equals(List.of(frozen.targetResourceId()))) {
      throw new IllegalArgumentException(
          "adapter result must equal the preallocated target Resource id");
    }
    CanvasDocument document = requireDocumentForUpdate(frozen.canvasId());
    NodeRecord node = canvasStore.lockNode(frozen.canvasId(), frozen.nodeId()).orElse(null);
    if (node == null || node.function() == null) {
      return false;
    }
    CanvasFunctionRun current = runRepository.findByNodeIdForUpdate(frozen.nodeId()).orElse(null);
    if (!matchesClaim(current, frozen.requestId().toString(), leaseToken)) {
      return false;
    }
    CanvasResource output =
        resourceRepository.findById(frozen.canvasId(), frozen.targetResourceId()).orElse(null);
    if (output == null
        || output.ownerNodeId() != null
        || output.resourceIndex() != null
        || output.blobId() == null) {
      throw new IllegalArgumentException(
          "adapter result must be materialized as an unowned blob Resource");
    }
    BlobFacts outputBlob = blobAccess.findFacts(output.blobId()).orElse(null);
    if (outputBlob == null || kindOf(outputBlob.mediaType()) != frozen.definition().outputKind()) {
      throw new IllegalArgumentException(
          "adapter result blob kind must match the frozen Function output kind");
    }
    resourceLifecycle.replaceOwnedWithTarget(
        frozen.canvasId(), frozen.nodeId(), frozen.targetResourceId());
    CanvasFunctionFrozenRun succeeded =
        stateCodec.transition(frozen, frozen.submitState(), "SUCCEEDED", frozen.adapterState());
    CanvasFunctionRun terminal =
        terminal(current, CanvasFunctionRunStatus.SUCCEEDED, succeeded, null);
    if (!runRepository.transitionTerminal(terminal, leaseToken)) {
      throw new IllegalStateException("FunctionRun success CAS failed after row lock");
    }
    resourceLifecycle.releaseRunPins(frozen.canvasId(), frozen.nodeId(), frozen.requestId());
    bumpVersion(document);
    return true;
  }

  /** 在租约仍有效时将 Run 收敛为 FAILED，并清理未挂接输出和本次 Run 的 pin。 */
  @Transactional
  public boolean failIfRunning(UUID nodeId, String requestId, String leaseToken, String error) {
    Objects.requireNonNull(nodeId, "nodeId");
    Objects.requireNonNull(requestId, "requestId");
    CanvasFunctionRun observed = runRepository.findByNodeId(nodeId).orElse(null);
    if (!matchesClaim(observed, requestId, leaseToken)) {
      return false;
    }
    CanvasFunctionFrozenRun observedFrozen = decode(observed);
    CanvasDocument document = requireDocumentForUpdate(observedFrozen.canvasId());
    if (!functionNodePresent(observedFrozen.canvasId(), nodeId)) {
      return false;
    }
    CanvasFunctionRun current = requireClaim(nodeId, requestId, leaseToken);
    CanvasFunctionFrozenRun frozen = decode(current);
    CanvasFunctionFrozenRun failed =
        stateCodec.transition(frozen, frozen.submitState(), "FAILED", frozen.adapterState());
    CanvasFunctionRun terminal = terminal(current, CanvasFunctionRunStatus.FAILED, failed, error);
    if (!runRepository.transitionTerminal(terminal, leaseToken)) {
      throw new IllegalStateException("FunctionRun failure CAS failed after row lock");
    }
    resourceLifecycle.releaseRunPins(frozen.canvasId(), frozen.nodeId(), frozen.requestId());
    resourceLifecycle.discardUnownedTarget(frozen.canvasId(), frozen.targetResourceId());
    bumpVersion(document);
    return true;
  }

  /** 推进外部提交事实；CAS 与 checkpoint 一致，旧租约只能得到内部取消。 */
  private CanvasFunctionFrozenRun advanceSubmit(
      CanvasFunctionFrozenRun run, String leaseToken, CanvasFunctionSubmitState nextState) {
    Objects.requireNonNull(run, "run");
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(nextState, "nextState");
    CanvasDocument document = requireDocumentForUpdate(run.canvasId());
    requireLiveFunctionNode(run.canvasId(), run.nodeId());
    CanvasFunctionRun current = requireClaim(run.nodeId(), run.requestId().toString(), leaseToken);
    CanvasFunctionFrozenRun observed = decode(current);
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
    persistRunningState(current, next, leaseToken);
    bumpVersion(document);
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
            now,
            null,
            null,
            resumed.stage(),
            stateCodec.encode(resumed),
            null,
            now,
            current.createdAt());
    if (!runRepository.resumeUnknown(ready)) {
      throw conflict("FunctionRun changed while resolving");
    }
    // RESUME 只重新调度查询原任务，pin 与预分配目标保持不动。
    bumpVersion(document);
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
    CanvasFunctionRun terminal = terminal(current, status, resolved, verification);
    if (!runRepository.resolveUnknownTerminal(terminal)) {
      throw conflict("FunctionRun changed while resolving");
    }
    resourceLifecycle.releaseRunPins(frozen.canvasId(), frozen.nodeId(), frozen.requestId());
    resourceLifecycle.discardUnownedTarget(frozen.canvasId(), frozen.targetResourceId());
    bumpVersion(document);
    return terminal;
  }

  /** 冻结并校验本次运行使用的输入资源清单。 */
  private List<CanvasFunctionFrozenReference> freezeManifest(
      UUID canvasId,
      UUID targetNodeId,
      String functionName,
      JsonObject args,
      CanvasFunctionReferencePolicy policy) {
    List<CanvasFunctionFrozenReference> manifest = new ArrayList<>();
    for (CanvasResourceReference reference : new CanvasFunction(functionName, args).references()) {
      if (canvasStore.findNode(canvasId, reference.nodeId()).isEmpty()) {
        throw new IllegalArgumentException("referenced source node must belong to the same canvas");
      }
      List<CanvasResource> resources =
          resourceRepository.findByOwnerNode(canvasId, reference.nodeId());
      if (reference.index() >= resources.size()) {
        throw new IllegalArgumentException("reference index is outside source node resources");
      }
      CanvasResource resource = resources.get(reference.index());
      if (resource.blobId() == null) {
        throw new IllegalArgumentException("referenced resource must be a Storage blob");
      }
      BlobFacts blob = blobAccess.findFacts(resource.blobId()).orElse(null);
      if (blob == null) {
        throw new IllegalArgumentException("referenced blob is missing: " + resource.blobId());
      }
      manifest.add(
          new CanvasFunctionFrozenReference(
              reference.nodeId(),
              reference.index(),
              resource.id(),
              resource.blobId(),
              kindOf(blob.mediaType()),
              resource.name(),
              blob.mediaType(),
              blob.sizeBytes(),
              blob.width(),
              blob.height(),
              blob.durationMs()));
    }
    validateReferencePolicy(manifest, policy);
    return List.copyOf(manifest);
  }

  private static void validateReferencePolicy(
      List<CanvasFunctionFrozenReference> manifest, CanvasFunctionReferencePolicy policy) {
    if (manifest.size() > policy.maxReferences()) {
      throw new IllegalArgumentException(
          "reference manifest exceeds maxReferences=" + policy.maxReferences());
    }
    Map<CanvasResourceKind, Integer> counts = new EnumMap<>(CanvasResourceKind.class);
    for (CanvasFunctionFrozenReference reference : manifest) {
      if (!policy.allowedKinds().contains(reference.kind())) {
        throw new IllegalArgumentException(
            "reference kind is not allowed by function: " + reference.kind());
      }
      int count = counts.merge(reference.kind(), 1, Integer::sum);
      if (count > policy.maxFor(reference.kind())) {
        throw new IllegalArgumentException(
            "reference kind exceeds function limit: " + reference.kind());
      }
    }
  }

  private List<CanvasFunctionResourcePin> inputPins(
      UUID canvasId, UUID nodeId, UUID requestId, List<CanvasFunctionFrozenReference> manifest) {
    List<CanvasFunctionResourcePin> refs = new ArrayList<>(manifest.size());
    for (CanvasFunctionFrozenReference reference : manifest) {
      refs.add(
          new CanvasFunctionResourcePin(
              canvasId,
              nodeId,
              requestId,
              reference.resourceId(),
              CanvasFunctionResourcePin.Role.INPUT));
    }
    return List.copyOf(refs);
  }

  /** 在已持有文档行锁的事务内推进同步位置，失败视为持久化不变量破坏。 */
  private void bumpVersion(CanvasDocument document) {
    if (!canvasStore.advanceRevision(
        document.id(), document.revision(), document.revision() + 1L)) {
      throw new IllegalStateException("canvas revision CAS failed under row lock");
    }
  }

  private CanvasFunctionFrozenRun decode(CanvasFunctionRun run) {
    CanvasFunctionDefinition definition =
        catalog.require(stateCodec.functionName(run.stateJson())).function();
    return stateCodec.decode(run.stateJson(), definition);
  }

  private void persistRunningState(
      CanvasFunctionRun current, CanvasFunctionFrozenRun next, String leaseToken) {
    CanvasFunctionRun updated =
        new CanvasFunctionRun(
            current.nodeId(),
            current.requestId(),
            CanvasFunctionRunStatus.RUNNING,
            current.attempt(),
            null,
            current.leaseToken(),
            current.leaseUntil(),
            next.stage(),
            stateCodec.encode(next),
            null,
            clock.instant(),
            current.createdAt());
    if (!runRepository.checkpoint(
        updated.nodeId(),
        updated.requestId(),
        leaseToken,
        updated.stateJson(),
        updated.stage(),
        updated.updatedAt())) {
      throw new CanvasFunctionInternalCancellation(
          "FunctionRun checkpoint CAS failed because the run is no longer RUNNING");
    }
  }

  private void requireLiveFunctionNode(UUID canvasId, UUID nodeId) {
    NodeRecord node = canvasStore.lockNode(canvasId, nodeId).orElse(null);
    if (node == null || node.function() == null) {
      throw new CanvasFunctionInternalCancellation(
          "Canvas Function node disappeared during FunctionRun write");
    }
  }

  private boolean functionNodePresent(UUID canvasId, UUID nodeId) {
    NodeRecord node = canvasStore.lockNode(canvasId, nodeId).orElse(null);
    return node != null && node.function() != null;
  }

  private CanvasFunctionRun requireClaim(UUID nodeId, String requestId, String leaseToken) {
    CanvasFunctionRun run = runRepository.findByNodeIdForUpdate(nodeId).orElse(null);
    if (!matchesClaim(run, requestId, leaseToken)) {
      throw new CanvasFunctionInternalCancellation(
          "FunctionRun is no longer RUNNING under the current lease");
    }
    return run;
  }

  /** 以冻结执行状态构造终态记录，并清空可调度时间与租约字段。 */
  private CanvasFunctionRun terminal(
      CanvasFunctionRun current,
      CanvasFunctionRunStatus status,
      CanvasFunctionFrozenRun frozen,
      String error) {
    return new CanvasFunctionRun(
        current.nodeId(),
        current.requestId(),
        status,
        current.attempt(),
        null,
        null,
        null,
        frozen.stage(),
        stateCodec.encode(frozen),
        error,
        clock.instant(),
        current.createdAt());
  }

  private CanvasDocument requireDocumentForUpdate(UUID canvasId) {
    return canvasStore
        .lockDocument(canvasId)
        .orElseThrow(() -> notFound("Canvas document not found"));
  }

  private static String validateUnknownReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("unknown reason must not be blank");
    }
    String stripped = stripControlCharacters(reason.strip());
    if (stripped.isEmpty()) {
      throw new IllegalArgumentException("unknown reason must not be blank");
    }
    return stripped.length() > MAX_UNKNOWN_REASON_LENGTH
        ? stripped.substring(0, MAX_UNKNOWN_REASON_LENGTH)
        : stripped;
  }

  private static String validateVerification(String verification) {
    if (verification == null || verification.isBlank()) {
      throw new IllegalArgumentException("verification must not be blank");
    }
    String stripped = stripControlCharacters(verification.strip());
    if (stripped.isEmpty()) {
      throw new IllegalArgumentException("verification must not be blank");
    }
    return stripped.length() > MAX_VERIFICATION_LENGTH
        ? stripped.substring(0, MAX_VERIFICATION_LENGTH)
        : stripped;
  }

  private static String stripControlCharacters(String value) {
    StringBuilder builder = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      builder.append(Character.isISOControl(character) ? ' ' : character);
    }
    return builder.toString().strip();
  }

  private static CanvasResourceKind kindOf(String mediaType) {
    if (mediaType == null) {
      throw new IllegalArgumentException("blob mediaType must not be null");
    }
    if (mediaType.startsWith("image/")) {
      return CanvasResourceKind.IMAGE;
    }
    if (mediaType.startsWith("video/")) {
      return CanvasResourceKind.VIDEO;
    }
    if (mediaType.startsWith("audio/")) {
      return CanvasResourceKind.AUDIO;
    }
    if (mediaType.startsWith("text/")) {
      return CanvasResourceKind.TEXT;
    }
    throw new IllegalArgumentException("unsupported blob mediaType: " + mediaType);
  }

  private static boolean matchesClaim(CanvasFunctionRun run, String requestId, String leaseToken) {
    return run != null
        && run.status() == CanvasFunctionRunStatus.RUNNING
        && run.requestId().toString().equals(requestId)
        && Objects.equals(run.leaseToken(), leaseToken);
  }

  private static String outputName(String nodeName, CanvasResourceKind outputKind) {
    String extension =
        switch (outputKind) {
          case IMAGE -> ".png";
          case VIDEO -> ".mp4";
          case AUDIO, TEXT -> throw new IllegalArgumentException(
              "unsupported Function output kind");
        };
    int maxBaseLength = 256 - extension.length();
    String base =
        nodeName.length() <= maxBaseLength ? nodeName : nodeName.substring(0, maxBaseLength);
    return base + extension;
  }

  private static CanvasFunctionRunException notFound(String message) {
    return new CanvasFunctionRunException(CanvasFunctionRunException.Reason.NOT_FOUND, message);
  }

  private static CanvasFunctionRunException conflict(String message) {
    return new CanvasFunctionRunException(CanvasFunctionRunException.Reason.CONFLICT, message);
  }
}
