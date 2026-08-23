package fun.fengwk.kkstudio.platform.studio;

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
import fun.fengwk.kkstudio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.platform.studio.repo.impl.mapper.CanvasDocumentMapper;
import fun.fengwk.kkstudio.platform.studio.repo.impl.mapper.CanvasGroupMapper;
import fun.fengwk.kkstudio.platform.studio.repo.impl.mapper.CanvasLinkMapper;
import fun.fengwk.kkstudio.platform.studio.repo.impl.mapper.CanvasNodeMapper;
import fun.fengwk.kkstudio.platform.studio.repo.impl.mapper.CanvasResourceMapper;
import fun.fengwk.kkstudio.platform.studio.repo.impl.model.CanvasDocumentDO;
import fun.fengwk.kkstudio.platform.studio.repo.impl.model.CanvasGroupDO;
import fun.fengwk.kkstudio.platform.studio.repo.impl.model.CanvasLinkDO;
import fun.fengwk.kkstudio.platform.studio.repo.impl.model.CanvasNodeDO;
import fun.fengwk.kkstudio.platform.studio.repo.impl.model.CanvasResourceDO;

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

  private final CanvasDocumentMapper documentMapper;
  private final CanvasNodeMapper nodeMapper;
  private final CanvasResourceMapper resourceMapper;
  private final CanvasGroupMapper groupMapper;
  private final CanvasLinkMapper linkMapper;
  private final CanvasFunctionRunRepository runRepository;

  public PostgresqlCanvasQueryService(
      CanvasDocumentMapper documentMapper,
      CanvasNodeMapper nodeMapper,
      CanvasResourceMapper resourceMapper,
      CanvasGroupMapper groupMapper,
      CanvasLinkMapper linkMapper,
      CanvasFunctionRunRepository runRepository) {
    this.documentMapper = Objects.requireNonNull(documentMapper, "documentMapper");
    this.nodeMapper = Objects.requireNonNull(nodeMapper, "nodeMapper");
    this.resourceMapper = Objects.requireNonNull(resourceMapper, "resourceMapper");
    this.groupMapper = Objects.requireNonNull(groupMapper, "groupMapper");
    this.linkMapper = Objects.requireNonNull(linkMapper, "linkMapper");
    this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
  }

  @Override
  public Optional<CanvasSnapshot> findSnapshot(UUID canvasId) {
    Objects.requireNonNull(canvasId, "canvasId");
    while (true) {
      CanvasDocumentDO documentBefore = documentMapper.getById(canvasId);
      if (documentBefore == null) {
        return Optional.empty();
      }
      List<CanvasFunctionRun> runsBefore = runRepository.findByCanvasId(canvasId);
      CanvasSnapshot snapshot = toSnapshot(documentBefore, runsBefore);
      List<CanvasFunctionRun> runsAfter = runRepository.findByCanvasId(canvasId);
      CanvasDocumentDO documentAfter = documentMapper.getById(canvasId);
      if (documentBefore.equals(documentAfter) && runsBefore.equals(runsAfter)) {
        return Optional.of(snapshot);
      }
    }
  }

  @Override
  public List<CanvasDocument> listDocuments() {
    List<CanvasDocument> documents = new ArrayList<>();
    for (CanvasDocumentDO document : documentMapper.listAll()) {
      documents.add(toDocument(document));
    }
    return List.copyOf(documents);
  }

  private CanvasSnapshot toSnapshot(
      CanvasDocumentDO document, List<CanvasFunctionRun> functionRuns) {
    UUID canvasId = document.getId();
    Map<UUID, List<CanvasResource>> resourcesByNode = new HashMap<>();
    for (CanvasResourceDO resource : resourceMapper.listByCanvas(canvasId)) {
      resourcesByNode
          .computeIfAbsent(resource.getOwnerNodeId(), ignored -> new ArrayList<>())
          .add(toResource(resource));
    }
    Map<UUID, CanvasFunctionRun> runsByNode = new HashMap<>();
    for (CanvasFunctionRun run : functionRuns) {
      runsByNode.put(run.nodeId(), run);
    }
    List<CanvasResourceNode> nodes = new ArrayList<>();
    for (CanvasNodeDO node : nodeMapper.listByCanvas(canvasId)) {
      nodes.add(
          new CanvasResourceNode(
              node.getId(),
              node.getCanvasId(),
              node.getName(),
              new CanvasTransform(node.getX(), node.getY(), node.getWidth(), node.getHeight()),
              node.getGroupId(),
              resourcesByNode.getOrDefault(node.getId(), List.of()),
              node.getModelKey() == null
                  ? null
                  : new CanvasFunction(node.getModelKey(), node.getFunctionConfigJson()),
              runsByNode.get(node.getId())));
    }
    List<CanvasGroup> groups = new ArrayList<>();
    for (CanvasGroupDO group : groupMapper.listByCanvas(canvasId)) {
      groups.add(
          new CanvasGroup(
              group.getId(),
              group.getCanvasId(),
              group.getTitle(),
              new CanvasTransform(
                  group.getX(), group.getY(), group.getWidth(), group.getHeight())));
    }
    List<CanvasLink> links = new ArrayList<>();
    for (CanvasLinkDO link : linkMapper.listByCanvas(canvasId)) {
      links.add(new CanvasLink(link.getCanvasId(), link.getSourceNodeId(), link.getTargetNodeId()));
    }
    return new CanvasSnapshot(toDocument(document), nodes, groups, links);
  }

  private static CanvasDocument toDocument(CanvasDocumentDO document) {
    return new CanvasDocument(
        document.getId(),
        document.getTitle(),
        document.getVersion(),
        document.getCreatedAt().toInstant(),
        document.getUpdatedAt().toInstant());
  }

  private static CanvasResource toResource(CanvasResourceDO resource) {
    return new CanvasResource(
        resource.getId(),
        resource.getCanvasId(),
        resource.getOwnerNodeId(),
        resource.getResourceIndex(),
        resource.getBlobId(),
        resource.getName(),
        resource.getTextContent(),
        resource.getCreatedAt().toInstant());
  }
}
