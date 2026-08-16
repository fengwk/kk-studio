package fun.fengwk.kkstudio.core.studio.function;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.core.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.core.studio.realtime.CanvasRealtimeService;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasDocumentMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasLinkMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasNodeMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasResourceMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasDocumentDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasNodeDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasResourceDO;
import fun.fengwk.kkstudio.core.studio.resource.CanvasResourceLifecycle;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionResourceRef;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionResourceRefRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.studio.canvas.CanvasNodePatch;
import fun.fengwk.kkstudio.studio.canvas.CanvasPatch;
import fun.fengwk.kkstudio.studio.canvas.CanvasResource;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.studio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig.ReferenceSegment;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionRunException;

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

  private final CanvasNodeMapper nodeMapper;
  private final CanvasResourceMapper resourceMapper;
  private final CanvasLinkMapper linkMapper;
  private final CanvasDocumentMapper documentMapper;
  private final CanvasFunctionRunRepository runRepository;
  private final CanvasFunctionResourceRefRepository refRepository;
  private final CanvasFunctionModelRegistry registry;
  private final CanvasFunctionConfigCodec configCodec;
  private final CanvasFunctionRunStateCodec stateCodec;
  private final CanvasResourceLifecycle resourceLifecycle;
  private final ObjectProvider<StorageBlobManager> blobManagers;
  private final CanvasRealtimeService realtimeService;
  private final Clock clock;

  public CanvasFunctionRunTransactions(
      CanvasNodeMapper nodeMapper,
      CanvasResourceMapper resourceMapper,
      CanvasLinkMapper linkMapper,
      CanvasDocumentMapper documentMapper,
      CanvasFunctionRunRepository runRepository,
      CanvasFunctionResourceRefRepository refRepository,
      CanvasFunctionModelRegistry registry,
      CanvasFunctionConfigCodec configCodec,
      CanvasFunctionRunStateCodec stateCodec,
      CanvasResourceLifecycle resourceLifecycle,
      ObjectProvider<StorageBlobManager> blobManagers,
      CanvasRealtimeService realtimeService,
      Clock clock) {
    this.nodeMapper = Objects.requireNonNull(nodeMapper, "nodeMapper");
    this.resourceMapper = Objects.requireNonNull(resourceMapper, "resourceMapper");
    this.linkMapper = Objects.requireNonNull(linkMapper, "linkMapper");
    this.documentMapper = Objects.requireNonNull(documentMapper, "documentMapper");
    this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
    this.refRepository = Objects.requireNonNull(refRepository, "refRepository");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.configCodec = Objects.requireNonNull(configCodec, "configCodec");
    this.stateCodec = Objects.requireNonNull(stateCodec, "stateCodec");
    this.resourceLifecycle = Objects.requireNonNull(resourceLifecycle, "resourceLifecycle");
    this.blobManagers = Objects.requireNonNull(blobManagers, "blobManagers");
    this.realtimeService = Objects.requireNonNull(realtimeService, "realtimeService");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Transactional
  public CanvasFunctionStartResult start(UUID canvasId, UUID nodeId, String requestId) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(nodeId, "nodeId");
    String validatedRequestId = CanvasFunctionRequestIds.validate(requestId);
    CanvasDocumentDO document = requireDocumentForUpdate(canvasId);
    CanvasNodeDO node = nodeMapper.getByIdForUpdate(canvasId, nodeId);
    if (node == null) {
      throw notFound("Canvas Function node not found");
    }
    if (node.getModelKey() == null) {
      throw new IllegalArgumentException("node must be a Canvas Function");
    }
    CanvasFunctionRun existing = runRepository.findByNodeIdForUpdate(nodeId).orElse(null);
    if (existing != null && existing.requestId().toString().equals(validatedRequestId)) {
      return new CanvasFunctionStartResult(existing, false);
    }
    if (existing != null && existing.status() == CanvasFunctionRunStatus.RUNNING) {
      throw conflict("another requestId is already RUNNING for this node");
    }

    CanvasFunctionModelRegistry.RegisteredModel registered = registry.require(node.getModelKey());
    CanvasFunctionAdapter adapter = registered.adapter();
    if (!adapter.enabled()) {
      throw new IllegalArgumentException(
          "Canvas Function model is unavailable: " + adapter.unavailableReason());
    }
    CanvasFunctionModel model = registered.model();
    CanvasFunctionConfig config = configCodec.decode(node.getFunctionConfigJson(), model);
    List<CanvasFunctionFrozenReference> manifest =
        freezeManifest(canvasId, nodeId, config, model.referencePolicy());
    UUID targetResourceId = UUID.randomUUID();
    CanvasFunctionFrozenRun frozen =
        new CanvasFunctionFrozenRun(
            canvasId,
            nodeId,
            node.getName(),
            UUID.fromString(validatedRequestId),
            model,
            config,
            manifest,
            outputName(node.getName(), model.outputKind()),
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
    bumpAndPublishNode(document, canvasId, nodeId);
    return new CanvasFunctionStartResult(running, created);
  }

  @Transactional
  public CanvasFunctionRun cancel(UUID canvasId, UUID nodeId, String requestId) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(nodeId, "nodeId");
    String validatedRequestId = CanvasFunctionRequestIds.validate(requestId);
    CanvasDocumentDO document = requireDocumentForUpdate(canvasId);
    CanvasNodeDO node = nodeMapper.getByIdForUpdate(canvasId, nodeId);
    if (node == null) {
      throw notFound("Canvas Function node not found");
    }
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
    bumpAndPublishNode(document, canvasId, nodeId);
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
    CanvasDocumentDO document = documentMapper.getByIdForUpdate(canvasId);
    if (document == null) {
      throw new CanvasFunctionInternalCancellation("Canvas document disappeared during checkpoint");
    }
    CanvasNodeDO node = nodeMapper.getByIdForUpdate(canvasId, nodeId);
    if (node == null || node.getModelKey() == null) {
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
    bumpAndPublishNode(document, canvasId, nodeId);
    return next;
  }

  @Transactional
  public boolean completeSuccess(CanvasFunctionFrozenRun frozen, List<UUID> orderedResourceIds) {
    Objects.requireNonNull(frozen, "frozen");
    if (!orderedResourceIds.equals(List.of(frozen.targetResourceId()))) {
      throw new IllegalArgumentException(
          "adapter result must equal the preallocated target Resource id");
    }
    CanvasDocumentDO document = requireDocumentForUpdate(frozen.canvasId());
    CanvasNodeDO node = nodeMapper.getByIdForUpdate(frozen.canvasId(), frozen.nodeId());
    if (node == null || node.getModelKey() == null) {
      resourceLifecycle.discardUnownedTarget(frozen.canvasId(), frozen.targetResourceId());
      return false;
    }
    CanvasFunctionRun current = runRepository.findByNodeIdForUpdate(frozen.nodeId()).orElse(null);
    if (!matchesRunning(current, frozen.requestId().toString())) {
      resourceLifecycle.discardUnownedTarget(frozen.canvasId(), frozen.targetResourceId());
      return false;
    }
    CanvasResourceDO output = resourceMapper.getById(frozen.canvasId(), frozen.targetResourceId());
    if (output == null
        || output.getOwnerNodeId() != null
        || output.getResourceIndex() != null
        || output.getBlobId() == null) {
      throw new IllegalArgumentException(
          "adapter result must be materialized as an unowned blob Resource");
    }
    StorageBlob outputBlob = requireBlobManager().getBlob(output.getBlobId());
    if (outputBlob == null || kindOf(outputBlob.getMediaType()) != frozen.model().outputKind()) {
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
    bumpAndPublishNode(document, frozen.canvasId(), frozen.nodeId());
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
    CanvasDocumentDO document = requireDocumentForUpdate(observedFrozen.canvasId());
    CanvasNodeDO node = nodeMapper.getByIdForUpdate(observedFrozen.canvasId(), nodeId);
    if (node == null || node.getModelKey() == null) {
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
    bumpAndPublishNode(document, frozen.canvasId(), frozen.nodeId());
    return true;
  }

  private List<CanvasFunctionFrozenReference> freezeManifest(
      UUID canvasId,
      UUID targetNodeId,
      CanvasFunctionConfig config,
      CanvasFunctionReferencePolicy policy) {
    List<CanvasFunctionFrozenReference> manifest = new ArrayList<>();
    for (ReferenceSegment reference : configCodec.uniqueReferences(config)) {
      CanvasNodeDO source = nodeMapper.getById(canvasId, reference.nodeId());
      if (source == null) {
        throw new IllegalArgumentException("referenced source node must belong to the same canvas");
      }
      if (linkMapper.exists(canvasId, reference.nodeId(), targetNodeId) != 1) {
        throw new IllegalArgumentException("referenced source node must have a Link to target");
      }
      List<CanvasResourceDO> resources =
          resourceMapper.listByOwnerNode(canvasId, reference.nodeId());
      if (reference.index() >= resources.size()) {
        throw new IllegalArgumentException("reference index is outside source node resources");
      }
      CanvasResourceDO resource = resources.get(reference.index());
      if (resource.getBlobId() == null) {
        throw new IllegalArgumentException("referenced resource must be a Storage blob");
      }
      StorageBlob blob = requireBlobManager().getBlob(resource.getBlobId());
      if (blob == null) {
        throw new IllegalArgumentException("referenced blob is missing: " + resource.getBlobId());
      }
      manifest.add(
          new CanvasFunctionFrozenReference(
              reference.nodeId(),
              reference.index(),
              resource.getId(),
              resource.getBlobId(),
              kindOf(blob.getMediaType()),
              resource.getName(),
              blob.getMediaType(),
              blob.getSizeBytes(),
              blob.getWidth(),
              blob.getHeight(),
              blob.getDurationMs()));
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

  private List<CanvasFunctionResourceRef> pins(
      UUID canvasId,
      UUID nodeId,
      UUID requestId,
      List<CanvasFunctionFrozenReference> manifest,
      UUID targetResourceId) {
    List<CanvasFunctionResourceRef> refs = new ArrayList<>(manifest.size() + 1);
    for (CanvasFunctionFrozenReference reference : manifest) {
      refs.add(
          new CanvasFunctionResourceRef(
              canvasId,
              nodeId,
              requestId,
              reference.resourceId(),
              CanvasFunctionResourceRef.Role.INPUT));
    }
    refs.add(
        new CanvasFunctionResourceRef(
            canvasId, nodeId, requestId, targetResourceId, CanvasFunctionResourceRef.Role.OUTPUT));
    return List.copyOf(refs);
  }

  private void bumpAndPublishNode(CanvasDocumentDO document, UUID canvasId, UUID nodeId) {
    long baseVersion = document.getVersion();
    long newVersion = baseVersion + 1L;
    if (documentMapper.compareAndSetVersion(canvasId, baseVersion, newVersion) != 1) {
      throw new IllegalStateException("canvas document version CAS failed under row lock");
    }
    CanvasResourceNode node = projectNode(canvasId, nodeId);
    realtimeService.publish(
        canvasId,
        new CanvasPatch(
            baseVersion,
            newVersion,
            List.of(),
            List.of(new CanvasNodePatch.Upsert(node)),
            List.of()));
  }

  private CanvasResourceNode projectNode(UUID canvasId, UUID nodeId) {
    CanvasNodeDO node = nodeMapper.getById(canvasId, nodeId);
    if (node == null) {
      throw new IllegalStateException("node disappeared under document row lock: " + nodeId);
    }
    List<CanvasResource> resources = new ArrayList<>();
    for (CanvasResourceDO resource : resourceMapper.listByOwnerNode(canvasId, nodeId)) {
      resources.add(
          new CanvasResource(
              resource.getId(),
              resource.getCanvasId(),
              resource.getOwnerNodeId(),
              resource.getResourceIndex(),
              resource.getBlobId(),
              resource.getName(),
              resource.getTextContent(),
              resource.getCreatedAt().toInstant()));
    }
    CanvasFunction function =
        node.getModelKey() == null
            ? null
            : new CanvasFunction(node.getModelKey(), node.getFunctionConfigJson());
    return new CanvasResourceNode(
        node.getId(),
        node.getCanvasId(),
        node.getName(),
        new CanvasTransform(node.getX(), node.getY(), node.getWidth(), node.getHeight()),
        node.getGroupId(),
        resources,
        function,
        runRepository.findByNodeId(nodeId).orElse(null));
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

  private CanvasDocumentDO requireDocumentForUpdate(UUID canvasId) {
    CanvasDocumentDO document = documentMapper.getByIdForUpdate(canvasId);
    if (document == null) {
      throw notFound("Canvas document not found");
    }
    return document;
  }

  private StorageBlobManager requireBlobManager() {
    StorageBlobManager blobManager = blobManagers.getIfAvailable();
    if (blobManager == null) {
      throw new IllegalStateException("global blob storage is unavailable");
    }
    return blobManager;
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
