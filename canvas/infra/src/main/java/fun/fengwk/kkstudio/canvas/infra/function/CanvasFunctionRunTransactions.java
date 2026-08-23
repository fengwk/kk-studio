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
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Function start/checkpoint/terminal/resource swap 的短事务边界（全部事务内锁定 node + document 行）。 */
@Component
public class CanvasFunctionRunTransactions {

  private final CanvasStore canvasStore;
  private final CanvasResourceRepository resourceRepository;
  private final CanvasFunctionRunRepository runRepository;
  private final CanvasFunctionResourcePinRepository refRepository;
  private final CanvasFunctionCatalog registry;
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
      CanvasFunctionCatalog registry,
      CanvasFunctionConfigCodecPort configCodec,
      CanvasFunctionRunStateCodecPort stateCodec,
      CanvasResourceLifecycle resourceLifecycle,
      CanvasFunctionBlobAccess blobAccess,
      Clock clock) {
    this.canvasStore = Objects.requireNonNull(canvasStore, "canvasStore");
    this.resourceRepository = Objects.requireNonNull(resourceRepository, "resourceRepository");
    this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
    this.refRepository = Objects.requireNonNull(refRepository, "refRepository");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.configCodec = Objects.requireNonNull(configCodec, "configCodec");
    this.stateCodec = Objects.requireNonNull(stateCodec, "stateCodec");
    this.resourceLifecycle = Objects.requireNonNull(resourceLifecycle, "resourceLifecycle");
    this.blobAccess = Objects.requireNonNull(blobAccess, "blobAccess");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

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
    if (existing != null && existing.status() == CanvasFunctionRunStatus.RUNNING) {
      throw conflict("another requestId is already RUNNING for this node");
    }

    CanvasFunctionCatalog.RegisteredModel registered = registry.require(node.modelKey());
    CanvasFunctionAdapter adapter = registered.adapter();
    if (!adapter.enabled()) {
      throw new IllegalArgumentException(
          "Canvas Function model is unavailable: " + adapter.unavailableReason());
    }
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
    CanvasFunctionRun running =
        new CanvasFunctionRun(
            nodeId,
            UUID.fromString(validatedRequestId),
            CanvasFunctionRunStatus.RUNNING,
            frozen.stage(),
            stateJson,
            null,
            now);
    if (existing != null) {
      resourceLifecycle.releaseRunPins(canvasId, nodeId, existing.requestId());
    }
    refRepository.addAll(pins(canvasId, nodeId, running.requestId(), manifest, targetResourceId));
    boolean created;
    if (existing == null) {
      runRepository.insertRunning(running);
      created = true;
    } else if (runRepository.replaceTerminalWithRunning(running)) {
      created = false;
    } else {
      throw conflict("terminal FunctionRun was replaced concurrently");
    }
    bumpVersion(document);
    return new CanvasFunctionStartResult(running, created);
  }

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
    if (current.status() != CanvasFunctionRunStatus.RUNNING) {
      return current;
    }
    CanvasFunctionFrozenRun frozen = decode(current);
    CanvasFunctionFrozenRun cancelled =
        stateCodec.checkpoint(frozen, "CANCELLED", frozen.adapterState());
    CanvasFunctionRun terminal =
        terminal(current, CanvasFunctionRunStatus.CANCELLED, cancelled, null);
    if (!runRepository.transitionTerminal(terminal)) {
      throw conflict("FunctionRun changed while cancelling");
    }
    resourceLifecycle.discardUnownedTarget(canvasId, frozen.targetResourceId());
    bumpVersion(document);
    return terminal;
  }

  /**
   * Adapter checkpoint：基于当前 frozen run + stage/adapterState 生成 next state，锁定 document/node 并验证仍是同一
   * RUNNING request，checkpoint CAS 后随 canvas version 与 node patch 在同一事务收敛。 document/node 消失、run 不再是
   * 该请求的 RUNNING 或 CAS 失败一律以 {@link CanvasFunctionInternalCancellation} 终止 adapter，绝不把旧 run 写回。
   */
  @Transactional
  public CanvasFunctionFrozenRun checkpoint(
      UUID canvasId,
      UUID nodeId,
      String requestId,
      String stage,
      Map<String, Object> adapterState) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(nodeId, "nodeId");
    String validatedRequestId = CanvasFunctionRequestIds.validate(requestId);
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
    if (!matchesRunning(current, validatedRequestId)) {
      throw new CanvasFunctionInternalCancellation(
          "FunctionRun checkpoint CAS failed because the run is no longer RUNNING");
    }
    CanvasFunctionFrozenRun next = stateCodec.checkpoint(decode(current), stage, adapterState);
    CanvasFunctionRun updated =
        new CanvasFunctionRun(
            current.nodeId(),
            current.requestId(),
            CanvasFunctionRunStatus.RUNNING,
            next.stage(),
            stateCodec.encode(next),
            null,
            clock.instant());
    if (!runRepository.checkpoint(
        updated.nodeId(),
        updated.requestId(),
        updated.stateJson(),
        updated.stage(),
        updated.updatedAt())) {
      throw new CanvasFunctionInternalCancellation(
          "FunctionRun checkpoint CAS failed because the run is no longer RUNNING");
    }
    bumpVersion(document);
    return next;
  }

  @Transactional
  public boolean completeSuccess(CanvasFunctionFrozenRun frozen, List<UUID> orderedResourceIds) {
    Objects.requireNonNull(frozen, "frozen");
    if (!orderedResourceIds.equals(List.of(frozen.targetResourceId()))) {
      throw new IllegalArgumentException(
          "adapter result must equal the preallocated target Resource id");
    }
    CanvasDocument document = requireDocumentForUpdate(frozen.canvasId());
    NodeRecord node = canvasStore.lockNode(frozen.canvasId(), frozen.nodeId()).orElse(null);
    if (node == null || node.modelKey() == null) {
      resourceLifecycle.discardUnownedTarget(frozen.canvasId(), frozen.targetResourceId());
      return false;
    }
    CanvasFunctionRun current = runRepository.findByNodeIdForUpdate(frozen.nodeId()).orElse(null);
    if (!matchesRunning(current, frozen.requestId().toString())) {
      resourceLifecycle.discardUnownedTarget(frozen.canvasId(), frozen.targetResourceId());
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
    if (!runRepository.transitionTerminal(terminal)) {
      throw new IllegalStateException("FunctionRun success CAS failed after row lock");
    }
    bumpVersion(document);
    return true;
  }

  @Transactional
  public boolean failIfRunning(UUID nodeId, String requestId, String error) {
    Objects.requireNonNull(nodeId, "nodeId");
    Objects.requireNonNull(requestId, "requestId");
    CanvasFunctionRun observed = runRepository.findByNodeId(nodeId).orElse(null);
    if (!matchesRunning(observed, requestId)) {
      return false;
    }
    CanvasFunctionFrozenRun observedFrozen = decode(observed);
    CanvasDocument document = requireDocumentForUpdate(observedFrozen.canvasId());
    NodeRecord node = canvasStore.lockNode(observedFrozen.canvasId(), nodeId).orElse(null);
    if (node == null || node.modelKey() == null) {
      return false;
    }
    CanvasFunctionRun current = runRepository.findByNodeIdForUpdate(nodeId).orElse(null);
    if (!matchesRunning(current, requestId)) {
      return false;
    }
    CanvasFunctionFrozenRun frozen = decode(current);
    CanvasFunctionFrozenRun failed = stateCodec.checkpoint(frozen, "FAILED", frozen.adapterState());
    CanvasFunctionRun terminal = terminal(current, CanvasFunctionRunStatus.FAILED, failed, error);
    if (!runRepository.transitionTerminal(terminal)) {
      throw new IllegalStateException("FunctionRun failure CAS failed after row lock");
    }
    resourceLifecycle.discardUnownedTarget(frozen.canvasId(), frozen.targetResourceId());
    bumpVersion(document);
    return true;
  }

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

  private void bumpVersion(CanvasDocument document) {
    long baseVersion = document.version();
    long newVersion = baseVersion + 1L;
    if (!canvasStore.advanceDocumentVersion(document.id(), baseVersion, newVersion)) {
      throw new IllegalStateException("canvas document version CAS failed under row lock");
    }
  }

  private CanvasFunctionFrozenRun decode(CanvasFunctionRun run) {
    CanvasFunctionModel model = registry.require(stateCodec.modelKey(run.stateJson())).model();
    return stateCodec.decode(run.stateJson(), model);
  }

  private CanvasFunctionRun terminal(
      CanvasFunctionRun current,
      CanvasFunctionRunStatus status,
      CanvasFunctionFrozenRun frozen,
      String error) {
    return new CanvasFunctionRun(
        current.nodeId(),
        current.requestId(),
        status,
        frozen.stage(),
        stateCodec.encode(frozen),
        error,
        clock.instant());
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

  private static boolean matchesRunning(CanvasFunctionRun run, String requestId) {
    return run != null
        && run.status() == CanvasFunctionRunStatus.RUNNING
        && run.requestId().toString().equals(requestId);
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
