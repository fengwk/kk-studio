package fun.fengwk.kkstudio.canvas.infra.function;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePin;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.CanvasResourceLifecycle;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.canvas.CanvasStore.NodeRecord;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBlobAccess.BlobFacts;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig.ReferenceSegment;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfigCodecPort;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunException;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunStateCodecPort;

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
 * <p>外部计算不持有数据库事务。本类只负责启动、检查点、取消和终态收敛，并在同一事务内维护 Run、资源 pin 与画布版本。针对同一画布的写入按 document、node 的固定顺序加锁。
 *
 * <p>Worker 写回必须同时匹配 {@code requestId} 和 {@code leaseToken}；租约失效后不能再更新状态。启动时会冻结输入资源并创建 INPUT/OUTPUT
 * pin。成功时预分配的输出资源接管节点所有权，失败或取消时丢弃未挂接输出；所有终态都会释放本次 Run 的 pin。
 */
@Component
public class CanvasFunctionRunTransactions {

  private final CanvasStore canvasStore;
  private final CanvasResourceRepository resourceRepository;
  private final CanvasFunctionRunRepository runRepository;
  private final CanvasFunctionResourcePinRepository refRepository;
  private final CanvasFunctionCatalog catalog;
  private final CanvasFunctionConfigCodecPort configCodec;
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
      CanvasFunctionConfigCodecPort configCodec,
      CanvasFunctionRunStateCodecPort stateCodec,
      CanvasResourceLifecycle resourceLifecycle,
      CanvasFunctionBlobAccess blobAccess,
      Clock clock) {
    this.canvasStore = Objects.requireNonNull(canvasStore, "canvasStore");
    this.resourceRepository = Objects.requireNonNull(resourceRepository, "resourceRepository");
    this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
    this.refRepository = Objects.requireNonNull(refRepository, "refRepository");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.configCodec = Objects.requireNonNull(configCodec, "configCodec");
    this.stateCodec = Objects.requireNonNull(stateCodec, "stateCodec");
    this.resourceLifecycle = Objects.requireNonNull(resourceLifecycle, "resourceLifecycle");
    this.blobAccess = Objects.requireNonNull(blobAccess, "blobAccess");
    this.clock = Clock.tick(Objects.requireNonNull(clock, "clock"), Duration.ofMillis(1));
  }

  /**
   * 启动新的 FunctionRun；相同 {@code requestId} 已存在时直接返回。
   *
   * <p>事务内冻结输入清单、创建输入和输出 pin，并将画布版本推进一次。
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
    if (node.modelKey() == null) {
      throw new IllegalArgumentException("node must be a Canvas Function");
    }
    CanvasFunctionRun existing = runRepository.findByNodeIdForUpdate(nodeId).orElse(null);
    if (existing != null && existing.requestId().toString().equals(validatedRequestId)) {
      return new CanvasFunctionStartResult(existing, false);
    }
    if (existing != null
        && (existing.status() == CanvasFunctionRunStatus.READY
            || existing.status() == CanvasFunctionRunStatus.RUNNING)) {
      throw conflict("another requestId is already active for this node");
    }

    CanvasFunctionCatalog.RegisteredModel registered = catalog.require(node.modelKey());
    registered.requireAvailable();
    CanvasFunctionAdapter adapter = registered.adapter();
    CanvasFunctionModel model = registered.model();
    CanvasFunctionConfig config = configCodec.decode(node.functionConfigJson(), model);
    List<CanvasFunctionFrozenReference> manifest =
        freezeManifest(canvasId, nodeId, config, model.referencePolicy());
    UUID targetResourceId = UUID.randomUUID();
    CanvasFunctionFrozenRun frozen =
        new CanvasFunctionFrozenRun(
            canvasId,
            nodeId,
            node.name(),
            UUID.fromString(validatedRequestId),
            model,
            config,
            manifest,
            outputName(node.name(), model.outputKind()),
            targetResourceId,
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
    refRepository.addAll(pins(canvasId, nodeId, ready.requestId(), manifest, targetResourceId));
    boolean created;
    if (existing == null) {
      runRepository.insertReady(ready);
      created = true;
    } else if (runRepository.replaceTerminalWithReady(ready)) {
      created = false;
    } else {
      throw conflict("terminal FunctionRun was replaced concurrently");
    }
    bumpVersion(document);
    return new CanvasFunctionStartResult(ready, created);
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
    if (current.status() != CanvasFunctionRunStatus.READY
        && current.status() != CanvasFunctionRunStatus.RUNNING) {
      return current;
    }
    CanvasFunctionFrozenRun frozen = decode(current);
    CanvasFunctionFrozenRun cancelled =
        stateCodec.checkpoint(frozen, "CANCELLED", frozen.adapterState());
    CanvasFunctionRun terminal =
        terminal(current, CanvasFunctionRunStatus.CANCELLED, cancelled, null);
    if (!runRepository.cancelActive(terminal)) {
      throw conflict("FunctionRun changed while cancelling");
    }
    resourceLifecycle.discardUnownedTarget(canvasId, frozen.targetResourceId());
    resourceLifecycle.releaseRunPins(canvasId, nodeId, current.requestId());
    bumpVersion(document);
    return terminal;
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
    CanvasDocument document = canvasStore.lockDocument(canvasId).orElse(null);
    if (document == null) {
      throw new CanvasFunctionInternalCancellation("Canvas document disappeared during checkpoint");
    }
    NodeRecord node = canvasStore.lockNode(canvasId, nodeId).orElse(null);
    if (node == null || node.modelKey() == null) {
      throw new CanvasFunctionInternalCancellation(
          "Canvas Function node disappeared during checkpoint");
    }
    CanvasFunctionRun current = runRepository.findByNodeIdForUpdate(nodeId).orElse(null);
    if (!matchesClaim(current, validatedRequestId, leaseToken)) {
      throw new CanvasFunctionInternalCancellation(
          "FunctionRun checkpoint CAS failed because the run is no longer RUNNING");
    }
    CanvasFunctionFrozenRun next = stateCodec.checkpoint(decode(current), stage, adapterState);
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
    bumpVersion(document);
    return next;
  }

  /**
   * 将 Run 收敛为 SUCCEEDED。
   *
   * <p>输出必须是启动时预分配的未挂接资源，且媒体类型与冻结模型一致。
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
    if (node == null || node.modelKey() == null) {
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
    if (outputBlob == null || kindOf(outputBlob.mediaType()) != frozen.model().outputKind()) {
      throw new IllegalArgumentException(
          "adapter result blob kind must match the frozen Function output kind");
    }
    resourceLifecycle.replaceOwnedWithTarget(
        frozen.canvasId(), frozen.nodeId(), frozen.targetResourceId());
    CanvasFunctionFrozenRun succeeded =
        stateCodec.checkpoint(frozen, "SUCCEEDED", frozen.adapterState());
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
    NodeRecord node = canvasStore.lockNode(observedFrozen.canvasId(), nodeId).orElse(null);
    if (node == null || node.modelKey() == null) {
      return false;
    }
    CanvasFunctionRun current = runRepository.findByNodeIdForUpdate(nodeId).orElse(null);
    if (!matchesClaim(current, requestId, leaseToken)) {
      return false;
    }
    CanvasFunctionFrozenRun frozen = decode(current);
    CanvasFunctionFrozenRun failed = stateCodec.checkpoint(frozen, "FAILED", frozen.adapterState());
    CanvasFunctionRun terminal = terminal(current, CanvasFunctionRunStatus.FAILED, failed, error);
    if (!runRepository.transitionTerminal(terminal, leaseToken)) {
      throw new IllegalStateException("FunctionRun failure CAS failed after row lock");
    }
    resourceLifecycle.discardUnownedTarget(frozen.canvasId(), frozen.targetResourceId());
    resourceLifecycle.releaseRunPins(frozen.canvasId(), frozen.nodeId(), frozen.requestId());
    bumpVersion(document);
    return true;
  }

  /** 冻结并校验本次运行使用的输入资源清单。 */
  private List<CanvasFunctionFrozenReference> freezeManifest(
      UUID canvasId,
      UUID targetNodeId,
      CanvasFunctionConfig config,
      CanvasFunctionReferencePolicy policy) {
    List<CanvasFunctionFrozenReference> manifest = new ArrayList<>();
    for (ReferenceSegment reference : configCodec.uniqueReferences(config)) {
      if (canvasStore.findNode(canvasId, reference.nodeId()).isEmpty()) {
        throw new IllegalArgumentException("referenced source node must belong to the same canvas");
      }
      if (!canvasStore.linkExists(canvasId, reference.nodeId(), targetNodeId)) {
        throw new IllegalArgumentException("referenced source node must have a Link to target");
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
            "reference kind is not allowed by model: " + reference.kind());
      }
      int count = counts.merge(reference.kind(), 1, Integer::sum);
      if (count > policy.maxFor(reference.kind())) {
        throw new IllegalArgumentException(
            "reference kind exceeds model limit: " + reference.kind());
      }
    }
  }

  private List<CanvasFunctionResourcePin> pins(
      UUID canvasId,
      UUID nodeId,
      UUID requestId,
      List<CanvasFunctionFrozenReference> manifest,
      UUID targetResourceId) {
    List<CanvasFunctionResourcePin> refs = new ArrayList<>(manifest.size() + 1);
    for (CanvasFunctionFrozenReference reference : manifest) {
      refs.add(
          new CanvasFunctionResourcePin(
              canvasId,
              nodeId,
              requestId,
              reference.resourceId(),
              CanvasFunctionResourcePin.Role.INPUT));
    }
    refs.add(
        new CanvasFunctionResourcePin(
            canvasId, nodeId, requestId, targetResourceId, CanvasFunctionResourcePin.Role.OUTPUT));
    return List.copyOf(refs);
  }

  /** 在已持有文档行锁的事务内执行版本 CAS，失败视为持久化不变量破坏。 */
  private void bumpVersion(CanvasDocument document) {
    long baseVersion = document.version();
    long newVersion = baseVersion + 1L;
    if (!canvasStore.advanceDocumentVersion(document.id(), baseVersion, newVersion)) {
      throw new IllegalStateException("canvas document version CAS failed under row lock");
    }
  }

  private CanvasFunctionFrozenRun decode(CanvasFunctionRun run) {
    CanvasFunctionModel model = catalog.require(stateCodec.modelKey(run.stateJson())).model();
    return stateCodec.decode(run.stateJson(), model);
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
