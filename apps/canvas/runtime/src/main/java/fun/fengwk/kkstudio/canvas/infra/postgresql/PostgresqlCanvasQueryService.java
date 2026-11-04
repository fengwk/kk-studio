package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.canvas.CanvasReference;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.canvas.CanvasResourceReference;
import fun.fengwk.kkstudio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.canvas.NodeRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Canvas document/graph 的一致性读投影。
 *
 * <p>连线不落库：快照中的 {@link CanvasReference} 由各节点 args 的严格引用形状投影而来，即使插件未安装也能展示已有连线。 读取期间 document 或 Run
 * 发生变化时重读，避免返回跨越并发提交的混合状态。
 */
@Repository
public class PostgresqlCanvasQueryService implements CanvasQueryService {

  private final PostgresqlCanvasStore canvasStore;
  private final PostgresqlCanvasResourceRepository resourceRepository;
  private final PostgresqlCanvasFunctionRunRepository runRepository;

  public PostgresqlCanvasQueryService(
      PostgresqlCanvasStore canvasStore,
      PostgresqlCanvasResourceRepository resourceRepository,
      PostgresqlCanvasFunctionRunRepository runRepository) {
    this.canvasStore = Objects.requireNonNull(canvasStore, "canvasStore");
    this.resourceRepository = Objects.requireNonNull(resourceRepository, "resourceRepository");
    this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
  }

  @Override
  public Optional<CanvasSnapshot> findSnapshot(UUID canvasId) {
    Objects.requireNonNull(canvasId, "canvasId");
    while (true) {
      CanvasDocument documentBefore = canvasStore.findDocument(canvasId).orElse(null);
      if (documentBefore == null) {
        return Optional.empty();
      }
      List<CanvasFunctionRun> runsBefore = runRepository.findByCanvasId(canvasId);
      CanvasSnapshot snapshot = toSnapshot(documentBefore, runsBefore);
      List<CanvasFunctionRun> runsAfter = runRepository.findByCanvasId(canvasId);
      CanvasDocument documentAfter = canvasStore.findDocument(canvasId).orElse(null);
      if (documentBefore.equals(documentAfter) && runsBefore.equals(runsAfter)) {
        return Optional.of(snapshot);
      }
    }
  }

  @Override
  public Optional<CanvasDocument> findDocument(UUID canvasId) {
    Objects.requireNonNull(canvasId, "canvasId");
    return canvasStore.findDocument(canvasId);
  }

  @Override
  public Optional<CanvasResource> findResource(UUID canvasId, UUID resourceId) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(resourceId, "resourceId");
    if (canvasStore.findDocument(canvasId).isEmpty()) {
      return Optional.empty();
    }
    return resourceRepository
        .findById(canvasId, resourceId)
        .filter(resource -> resource.ownerNodeId() != null);
  }

  @Override
  public List<CanvasDocument> listDocuments() {
    return canvasStore.listDocuments();
  }

  private CanvasSnapshot toSnapshot(CanvasDocument document, List<CanvasFunctionRun> functionRuns) {
    UUID canvasId = document.id();
    Map<UUID, List<CanvasResource>> resourcesByNode = new HashMap<>();
    for (CanvasResource resource : resourceRepository.findByCanvasId(canvasId)) {
      if (resource.ownerNodeId() == null) {
        continue;
      }
      resourcesByNode
          .computeIfAbsent(resource.ownerNodeId(), ignored -> new ArrayList<>())
          .add(resource);
    }
    Map<UUID, CanvasFunctionRun> runsByNode = new HashMap<>();
    for (CanvasFunctionRun run : functionRuns) {
      runsByNode.put(run.nodeId(), run);
    }
    List<CanvasResourceNode> nodes = new ArrayList<>();
    LinkedHashSet<CanvasReference> references = new LinkedHashSet<>();
    for (NodeRecord node : canvasStore.listNodes(canvasId)) {
      List<CanvasResource> resources =
          new ArrayList<>(resourcesByNode.getOrDefault(node.id(), List.of()));
      resources.sort(Comparator.comparingInt(CanvasResource::resourceIndex));
      nodes.add(
          new CanvasResourceNode(
              node.id(),
              node.canvasId(),
              node.name(),
              node.transform(),
              node.groupId(),
              resources,
              node.function(),
              runsByNode.get(node.id())));
      if (node.function() != null) {
        for (CanvasResourceReference reference : node.function().references()) {
          if (!reference.nodeId().equals(node.id())) {
            references.add(
                new CanvasReference(canvasId, reference.nodeId(), node.id(), reference.index()));
          }
        }
      }
    }
    List<CanvasGroup> groups = canvasStore.listGroups(canvasId);
    return new CanvasSnapshot(document, nodes, groups, List.copyOf(references));
  }
}
