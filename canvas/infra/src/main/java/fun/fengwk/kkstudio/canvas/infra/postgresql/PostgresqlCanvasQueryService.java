package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.canvas.CanvasLink;
import fun.fengwk.kkstudio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.canvas.CanvasStore.NodeRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Canvas document/graph 的一致性读投影。 */
@Repository
public class PostgresqlCanvasQueryService implements CanvasQueryService {

  private final CanvasStore canvasStore;
  private final CanvasResourceRepository resourceRepository;
  private final CanvasFunctionRunRepository runRepository;

  public PostgresqlCanvasQueryService(
      CanvasStore canvasStore,
      CanvasResourceRepository resourceRepository,
      CanvasFunctionRunRepository runRepository) {
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
  public List<CanvasDocument> listDocuments() {
    return canvasStore.listDocuments();
  }

  private CanvasSnapshot toSnapshot(CanvasDocument document, List<CanvasFunctionRun> functionRuns) {
    UUID canvasId = document.id();
    Map<UUID, List<CanvasResource>> resourcesByNode = new HashMap<>();
    for (CanvasResource resource : resourceRepository.findByCanvasId(canvasId)) {
      resourcesByNode
          .computeIfAbsent(resource.ownerNodeId(), ignored -> new ArrayList<>())
          .add(resource);
    }
    Map<UUID, CanvasFunctionRun> runsByNode = new HashMap<>();
    for (CanvasFunctionRun run : functionRuns) {
      runsByNode.put(run.nodeId(), run);
    }
    List<CanvasResourceNode> nodes = new ArrayList<>();
    for (NodeRecord node : canvasStore.listNodes(canvasId)) {
      nodes.add(
          new CanvasResourceNode(
              node.id(),
              node.canvasId(),
              node.name(),
              node.transform(),
              node.groupId(),
              resourcesByNode.getOrDefault(node.id(), List.of()),
              node.modelKey() == null
                  ? null
                  : new CanvasFunction(node.modelKey(), node.functionConfigJson()),
              runsByNode.get(node.id())));
    }
    List<CanvasGroup> groups = canvasStore.listGroups(canvasId);
    List<CanvasLink> links = canvasStore.listLinks(canvasId);
    return new CanvasSnapshot(document, nodes, groups, links);
  }
}
