package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.canvas.CanvasLink;
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.canvas.CanvasTransform;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@link CanvasStore} 的 PostgreSQL/MyBatis 实现。 */
@Repository
public class PostgresqlCanvasStore implements CanvasStore {

  private final CanvasDocumentMapper documentMapper;
  private final CanvasNodeMapper nodeMapper;
  private final CanvasGroupMapper groupMapper;
  private final CanvasLinkMapper linkMapper;
  private final CanvasCommandDedupMapper commandDedupMapper;

  public PostgresqlCanvasStore(
      CanvasDocumentMapper documentMapper,
      CanvasNodeMapper nodeMapper,
      CanvasGroupMapper groupMapper,
      CanvasLinkMapper linkMapper,
      CanvasCommandDedupMapper commandDedupMapper) {
    this.documentMapper = Objects.requireNonNull(documentMapper, "documentMapper");
    this.nodeMapper = Objects.requireNonNull(nodeMapper, "nodeMapper");
    this.groupMapper = Objects.requireNonNull(groupMapper, "groupMapper");
    this.linkMapper = Objects.requireNonNull(linkMapper, "linkMapper");
    this.commandDedupMapper = Objects.requireNonNull(commandDedupMapper, "commandDedupMapper");
  }

  @Override
  public CanvasDocument addDocument(UUID canvasId, String title) {
    CanvasDocumentDO document = new CanvasDocumentDO();
    document.setId(canvasId);
    document.setTitle(title);
    document.setVersion(0L);
    if (documentMapper.insert(document) != 1) {
      throw new IllegalStateException("insert canvas document failed");
    }
    CanvasDocumentDO persisted = documentMapper.getById(canvasId);
    if (persisted == null) {
      throw new IllegalStateException("canvas document disappeared after insert");
    }
    return toDomain(persisted);
  }

  @Override
  public Optional<CanvasDocument> findDocument(UUID canvasId) {
    return Optional.ofNullable(documentMapper.getById(canvasId))
        .map(PostgresqlCanvasStore::toDomain);
  }

  @Override
  public Optional<CanvasDocument> lockDocument(UUID canvasId) {
    return Optional.ofNullable(documentMapper.getByIdForUpdate(canvasId))
        .map(PostgresqlCanvasStore::toDomain);
  }

  @Override
  public Optional<CanvasDocument> lockDocumentForKeyShare(UUID canvasId) {
    return Optional.ofNullable(documentMapper.getByIdForKeyShare(canvasId))
        .map(PostgresqlCanvasStore::toDomain);
  }

  @Override
  public List<CanvasDocument> listDocuments() {
    List<CanvasDocument> documents = new ArrayList<>();
    for (CanvasDocumentDO document : documentMapper.listAll()) {
      documents.add(toDomain(document));
    }
    return List.copyOf(documents);
  }

  @Override
  public boolean advanceDocumentVersion(UUID canvasId, long expectedVersion, long newVersion) {
    return documentMapper.compareAndSetVersion(canvasId, expectedVersion, newVersion) == 1;
  }

  @Override
  public boolean deleteDocument(UUID canvasId) {
    return documentMapper.deleteById(canvasId) == 1;
  }

  @Override
  public void addNode(NodeRecord node) {
    if (nodeMapper.insert(toData(node)) != 1) {
      throw new IllegalStateException("insert canvas node failed: " + node.id());
    }
  }

  @Override
  public Optional<NodeRecord> findNode(UUID canvasId, UUID nodeId) {
    return Optional.ofNullable(nodeMapper.getById(canvasId, nodeId))
        .map(PostgresqlCanvasStore::toDomain);
  }

  @Override
  public Optional<NodeRecord> lockNode(UUID canvasId, UUID nodeId) {
    return Optional.ofNullable(nodeMapper.getByIdForUpdate(canvasId, nodeId))
        .map(PostgresqlCanvasStore::toDomain);
  }

  @Override
  public List<NodeRecord> listNodes(UUID canvasId) {
    List<NodeRecord> nodes = new ArrayList<>();
    for (CanvasNodeDO node : nodeMapper.listByCanvas(canvasId)) {
      nodes.add(toDomain(node));
    }
    return List.copyOf(nodes);
  }

  @Override
  public boolean updateNodeTransform(NodeRecord node) {
    return nodeMapper.updateTransform(toData(node)) == 1;
  }

  @Override
  public boolean renameNode(UUID canvasId, UUID nodeId, String name) {
    CanvasNodeDO node = new CanvasNodeDO();
    node.setCanvasId(canvasId);
    node.setId(nodeId);
    node.setName(name);
    return nodeMapper.updateName(node) == 1;
  }

  @Override
  public boolean updateNodeFunction(
      UUID canvasId, UUID nodeId, String modelKey, String functionConfigJson) {
    CanvasNodeDO node = new CanvasNodeDO();
    node.setCanvasId(canvasId);
    node.setId(nodeId);
    node.setModelKey(modelKey);
    node.setFunctionConfigJson(functionConfigJson);
    return nodeMapper.updateFunction(node) == 1;
  }

  @Override
  public boolean attachNodeToGroupIfUngrouped(UUID canvasId, UUID nodeId, UUID groupId) {
    CanvasNodeDO node = new CanvasNodeDO();
    node.setCanvasId(canvasId);
    node.setId(nodeId);
    node.setGroupId(groupId);
    return nodeMapper.attachGroupIfUngrouped(node) == 1;
  }

  @Override
  public boolean detachNodeFromGroup(UUID canvasId, UUID groupId, UUID nodeId) {
    return nodeMapper.detachGroupMember(canvasId, groupId, nodeId) == 1;
  }

  @Override
  public int detachAllNodesFromGroup(UUID canvasId, UUID groupId) {
    return nodeMapper.detachAllGroupMembers(canvasId, groupId);
  }

  @Override
  public int moveGroupNodes(UUID canvasId, UUID groupId, double deltaX, double deltaY) {
    return nodeMapper.moveGroupMembers(canvasId, groupId, deltaX, deltaY);
  }

  @Override
  public boolean deleteNode(UUID canvasId, UUID nodeId) {
    return nodeMapper.deleteById(canvasId, nodeId) == 1;
  }

  @Override
  public void addGroup(CanvasGroup group) {
    if (groupMapper.insert(toData(group)) != 1) {
      throw new IllegalStateException("insert canvas group failed: " + group.id());
    }
  }

  @Override
  public Optional<CanvasGroup> findGroup(UUID canvasId, UUID groupId) {
    return Optional.ofNullable(groupMapper.getById(canvasId, groupId))
        .map(PostgresqlCanvasStore::toDomain);
  }

  @Override
  public List<CanvasGroup> listGroups(UUID canvasId) {
    List<CanvasGroup> groups = new ArrayList<>();
    for (CanvasGroupDO group : groupMapper.listByCanvas(canvasId)) {
      groups.add(toDomain(group));
    }
    return List.copyOf(groups);
  }

  @Override
  public boolean moveGroup(CanvasGroup group) {
    return groupMapper.updatePosition(toData(group)) == 1;
  }

  @Override
  public boolean renameGroup(UUID canvasId, UUID groupId, String title) {
    CanvasGroupDO group = new CanvasGroupDO();
    group.setCanvasId(canvasId);
    group.setId(groupId);
    group.setTitle(title);
    return groupMapper.updateTitle(group) == 1;
  }

  @Override
  public boolean deleteGroup(UUID canvasId, UUID groupId) {
    return groupMapper.deleteById(canvasId, groupId) == 1;
  }

  @Override
  public void addLink(CanvasLink link) {
    if (linkMapper.insert(toData(link)) != 1) {
      throw new IllegalStateException(
          "insert canvas link failed: " + link.sourceNodeId() + " -> " + link.targetNodeId());
    }
  }

  @Override
  public List<CanvasLink> listLinks(UUID canvasId) {
    List<CanvasLink> links = new ArrayList<>();
    for (CanvasLinkDO link : linkMapper.listByCanvas(canvasId)) {
      links.add(toDomain(link));
    }
    return List.copyOf(links);
  }

  @Override
  public boolean linkExists(UUID canvasId, UUID sourceNodeId, UUID targetNodeId) {
    return linkMapper.exists(canvasId, sourceNodeId, targetNodeId) == 1;
  }

  @Override
  public boolean deleteLink(UUID canvasId, UUID sourceNodeId, UUID targetNodeId) {
    return linkMapper.delete(canvasId, sourceNodeId, targetNodeId) == 1;
  }

  @Override
  public int deleteLinksByNode(UUID canvasId, UUID nodeId) {
    return linkMapper.deleteByNode(canvasId, nodeId);
  }

  @Override
  public int deleteLinksByCanvas(UUID canvasId) {
    return linkMapper.deleteByCanvas(canvasId);
  }

  @Override
  public Optional<CommandDedup> findCommandDedup(UUID canvasId, UUID commandId) {
    return Optional.ofNullable(commandDedupMapper.findById(canvasId, commandId))
        .map(PostgresqlCanvasStore::toDomain);
  }

  @Override
  public void addCommandDedup(CommandDedup commandDedup) {
    if (commandDedupMapper.insert(toData(commandDedup)) != 1) {
      throw new IllegalStateException("insert canvas command dedup failed");
    }
  }

  @Override
  public int deleteCommandDedupByCanvas(UUID canvasId) {
    return commandDedupMapper.deleteByCanvas(canvasId);
  }

  private static CanvasDocument toDomain(CanvasDocumentDO document) {
    return new CanvasDocument(
        document.getId(),
        document.getTitle(),
        document.getVersion(),
        document.getCreatedAt().toInstant(),
        document.getUpdatedAt().toInstant());
  }

  private static NodeRecord toDomain(CanvasNodeDO node) {
    return new NodeRecord(
        node.getId(),
        node.getCanvasId(),
        node.getName(),
        new CanvasTransform(node.getX(), node.getY(), node.getWidth(), node.getHeight()),
        node.getGroupId(),
        node.getModelKey(),
        node.getFunctionConfigJson());
  }

  private static CanvasNodeDO toData(NodeRecord node) {
    CanvasNodeDO data = new CanvasNodeDO();
    data.setId(node.id());
    data.setCanvasId(node.canvasId());
    data.setName(node.name());
    data.setX(node.transform().x());
    data.setY(node.transform().y());
    data.setWidth(node.transform().width());
    data.setHeight(node.transform().height());
    data.setGroupId(node.groupId());
    data.setModelKey(node.modelKey());
    data.setFunctionConfigJson(node.functionConfigJson());
    return data;
  }

  private static CanvasGroup toDomain(CanvasGroupDO group) {
    return new CanvasGroup(
        group.getId(),
        group.getCanvasId(),
        group.getTitle(),
        new CanvasTransform(group.getX(), group.getY(), group.getWidth(), group.getHeight()));
  }

  private static CanvasGroupDO toData(CanvasGroup group) {
    CanvasGroupDO data = new CanvasGroupDO();
    data.setId(group.id());
    data.setCanvasId(group.canvasId());
    data.setTitle(group.title());
    data.setX(group.transform().x());
    data.setY(group.transform().y());
    data.setWidth(group.transform().width());
    data.setHeight(group.transform().height());
    return data;
  }

  private static CanvasLink toDomain(CanvasLinkDO link) {
    return new CanvasLink(link.getCanvasId(), link.getSourceNodeId(), link.getTargetNodeId());
  }

  private static CanvasLinkDO toData(CanvasLink link) {
    CanvasLinkDO data = new CanvasLinkDO();
    data.setCanvasId(link.canvasId());
    data.setSourceNodeId(link.sourceNodeId());
    data.setTargetNodeId(link.targetNodeId());
    return data;
  }

  private static CommandDedup toDomain(CanvasCommandDedupDO commandDedup) {
    return new CommandDedup(
        commandDedup.getCanvasId(), commandDedup.getCommandId(), commandDedup.getRequestHash());
  }

  private static CanvasCommandDedupDO toData(CommandDedup commandDedup) {
    CanvasCommandDedupDO data = new CanvasCommandDedupDO();
    data.setCanvasId(commandDedup.canvasId());
    data.setCommandId(commandDedup.commandId());
    data.setRequestHash(commandDedup.requestHash());
    return data;
  }
}
