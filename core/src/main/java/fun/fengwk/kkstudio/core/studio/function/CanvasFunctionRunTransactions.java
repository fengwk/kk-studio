package fun.fengwk.kkstudio.core.studio.function;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasLinkMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasNodeMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasNodeResourceMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasResourceMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasNodeDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasNodeResourceDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasResourceDO;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
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

/** Function start/checkpoint/terminal/resource swap 的短事务边界。 */
@Component
public class CanvasFunctionRunTransactions {

  private final CanvasNodeMapper nodeMapper;
  private final CanvasResourceMapper resourceMapper;
  private final CanvasNodeResourceMapper nodeResourceMapper;
  private final CanvasLinkMapper linkMapper;
  private final CanvasFunctionRunRepository runRepository;
  private final CanvasFunctionModelRegistry registry;
  private final CanvasFunctionConfigCodec configCodec;
  private final CanvasFunctionRunStateCodec stateCodec;
  private final PostgresqlSequenceIdGenerator idGenerator;
  private final Clock clock;

  public CanvasFunctionRunTransactions(
      CanvasNodeMapper nodeMapper,
      CanvasResourceMapper resourceMapper,
      CanvasNodeResourceMapper nodeResourceMapper,
      CanvasLinkMapper linkMapper,
      CanvasFunctionRunRepository runRepository,
      CanvasFunctionModelRegistry registry,
      CanvasFunctionConfigCodec configCodec,
      CanvasFunctionRunStateCodec stateCodec,
      PostgresqlSequenceIdGenerator idGenerator,
      Clock clock) {
    this.nodeMapper = Objects.requireNonNull(nodeMapper, "nodeMapper");
    this.resourceMapper = Objects.requireNonNull(resourceMapper, "resourceMapper");
    this.nodeResourceMapper = Objects.requireNonNull(nodeResourceMapper, "nodeResourceMapper");
    this.linkMapper = Objects.requireNonNull(linkMapper, "linkMapper");
    this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.configCodec = Objects.requireNonNull(configCodec, "configCodec");
    this.stateCodec = Objects.requireNonNull(stateCodec, "stateCodec");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Transactional
  public CanvasFunctionStartResult start(long canvasId, long nodeId, String requestId) {
    requirePositive(canvasId, "canvasId");
    requirePositive(nodeId, "nodeId");
    String validatedRequestId = CanvasFunctionRequestIds.validate(requestId);
    CanvasNodeDO node = nodeMapper.getByIdForUpdate(canvasId, nodeId);
    if (node == null) {
      throw notFound("Canvas Function node not found");
    }
    if (node.getModelKey() == null) {
      throw new IllegalArgumentException("node must be a Canvas Function");
    }

    CanvasFunctionRun existing = runRepository.findByNodeIdForUpdate(nodeId).orElse(null);
    if (existing != null && existing.requestId().equals(validatedRequestId)) {
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
    long targetResourceId = idGenerator.next();
    CanvasFunctionFrozenRun frozen =
        new CanvasFunctionFrozenRun(
            canvasId,
            nodeId,
            node.getName(),
            validatedRequestId,
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
            validatedRequestId,
            CanvasFunctionRunStatus.RUNNING,
            frozen.stage(),
            stateJson,
            null,
            now);
    if (existing == null) {
      runRepository.insertRunning(running);
    } else if (!runRepository.replaceTerminalWithRunning(running)) {
      throw conflict("terminal FunctionRun was replaced concurrently");
    }
    return new CanvasFunctionStartResult(running, true);
  }

  @Transactional
  public CanvasFunctionRun cancel(long canvasId, long nodeId, String requestId) {
    requirePositive(canvasId, "canvasId");
    requirePositive(nodeId, "nodeId");
    String validatedRequestId = CanvasFunctionRequestIds.validate(requestId);
    CanvasNodeDO node = nodeMapper.getByIdForUpdate(canvasId, nodeId);
    if (node == null) {
      throw notFound("Canvas Function node not found");
    }
    CanvasFunctionRun current =
        runRepository
            .findByNodeIdForUpdate(nodeId)
            .orElseThrow(() -> notFound("Canvas Function run not found"));
    if (!current.requestId().equals(validatedRequestId)) {
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
    return terminal;
  }

  @Transactional
  public boolean completeSuccess(CanvasFunctionFrozenRun frozen, List<Long> orderedResourceIds) {
    CanvasNodeDO node = nodeMapper.getByIdForUpdate(frozen.canvasId(), frozen.nodeId());
    if (node == null || node.getModelKey() == null) {
      return false;
    }
    CanvasFunctionRun current = runRepository.findByNodeIdForUpdate(frozen.nodeId()).orElse(null);
    if (!matchesRunning(current, frozen.requestId())) {
      return false;
    }
    if (!orderedResourceIds.equals(List.of(frozen.targetResourceId()))) {
      throw new IllegalArgumentException(
          "adapter result must equal the preallocated target Resource id");
    }
    List<CanvasResourceDO> resources =
        resourceMapper.listByIds(frozen.canvasId(), orderedResourceIds);
    if (resources.size() != orderedResourceIds.size()
        || resources.stream()
            .anyMatch(
                resource ->
                    CanvasResourceKind.valueOf(resource.getKind())
                        != frozen.model().outputKind())) {
      throw new IllegalArgumentException(
          "all adapter results must be materialized with the frozen output kind");
    }

    nodeResourceMapper.deleteByNode(frozen.canvasId(), frozen.nodeId());
    for (int index = 0; index < orderedResourceIds.size(); index++) {
      CanvasNodeResourceDO relation = new CanvasNodeResourceDO();
      relation.setCanvasId(frozen.canvasId());
      relation.setNodeId(frozen.nodeId());
      relation.setResourceIndex(index);
      relation.setResourceId(orderedResourceIds.get(index));
      nodeResourceMapper.insert(relation);
    }
    CanvasFunctionFrozenRun succeeded =
        stateCodec.checkpoint(frozen, "SUCCEEDED", frozen.adapterState());
    CanvasFunctionRun terminal =
        terminal(current, CanvasFunctionRunStatus.SUCCEEDED, succeeded, null);
    if (!runRepository.transitionTerminal(terminal)) {
      throw new IllegalStateException("FunctionRun success CAS failed after row lock");
    }
    return true;
  }

  @Transactional
  public boolean failIfRunning(long nodeId, String requestId, String error) {
    CanvasFunctionRun current = runRepository.findByNodeIdForUpdate(nodeId).orElse(null);
    if (!matchesRunning(current, requestId)) {
      return false;
    }
    CanvasFunctionFrozenRun frozen = decode(current);
    CanvasFunctionFrozenRun failed = stateCodec.checkpoint(frozen, "FAILED", frozen.adapterState());
    return runRepository.transitionTerminal(
        terminal(current, CanvasFunctionRunStatus.FAILED, failed, error));
  }

  private List<CanvasFunctionFrozenReference> freezeManifest(
      long canvasId,
      long targetNodeId,
      CanvasFunctionConfig config,
      CanvasFunctionReferencePolicy policy) {
    List<CanvasFunctionFrozenReference> manifest = new ArrayList<>();
    for (ReferenceSegment reference : configCodec.uniqueReferences(config)) {
      CanvasNodeDO source = nodeMapper.getByGlobalId(reference.nodeId());
      if (source == null || source.getCanvasId() != canvasId) {
        throw new IllegalArgumentException("referenced source node must belong to the same canvas");
      }
      if (linkMapper.exists(canvasId, reference.nodeId(), targetNodeId) != 1) {
        throw new IllegalArgumentException("referenced source node must have a Link to target");
      }
      List<CanvasResourceDO> resources = resourceMapper.listByNode(canvasId, reference.nodeId());
      if (reference.index() >= resources.size()) {
        throw new IllegalArgumentException("reference index is outside source node resources");
      }
      CanvasResourceDO resource = resources.get(reference.index());
      manifest.add(
          new CanvasFunctionFrozenReference(
              reference.nodeId(),
              reference.index(),
              resource.getId(),
              CanvasResourceKind.valueOf(resource.getKind()),
              resource.getName(),
              resource.getMediaType(),
              resource.getSize(),
              resource.getMetadataJson()));
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

  private static boolean matchesRunning(CanvasFunctionRun run, String requestId) {
    return run != null
        && run.status() == CanvasFunctionRunStatus.RUNNING
        && run.requestId().equals(requestId);
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

  private static void requirePositive(long value, String field) {
    if (value <= 0L) {
      throw new IllegalArgumentException(field + " must be > 0");
    }
  }

  private static CanvasFunctionRunException notFound(String message) {
    return new CanvasFunctionRunException(CanvasFunctionRunException.Reason.NOT_FOUND, message);
  }

  private static CanvasFunctionRunException conflict(String message) {
    return new CanvasFunctionRunException(CanvasFunctionRunException.Reason.CONFLICT, message);
  }
}
