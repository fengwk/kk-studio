package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasGroup;
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
  private final CanvasCommandDedupMapper commandDedupMapper;
  private final PostgresqlCanvasChangeNotifier notifier;

  public PostgresqlCanvasStore(
      CanvasDocumentMapper documentMapper,
      CanvasNodeMapper nodeMapper,
      CanvasGroupMapper groupMapper,
      CanvasCommandDedupMapper commandDedupMapper,
      PostgresqlCanvasChangeNotifier notifier) {
    this.documentMapper = Objects.requireNonNull(documentMapper, "documentMapper");
    this.nodeMapper = Objects.requireNonNull(nodeMapper, "nodeMapper");
    this.groupMapper = Objects.requireNonNull(groupMapper, "groupMapper");
    this.commandDedupMapper = Objects.requireNonNull(commandDedupMapper, "commandDedupMapper");
    this.notifier = Objects.requireNonNull(notifier, "notifier");
  }

  @Override
  @Transactional
  public CanvasDocument addDocument(UUID canvasId, String title) {
    CanvasDocumentDO document = new CanvasDocumentDO();
    document.setId(canvasId);
    document.setTitle(title);
    if (documentMapper.insert(document) != 1) {
      throw new IllegalStateException("insert canvas document failed");
    }
    CanvasDocumentDO persisted = documentMapper.getById(canvasId);
    if (persisted == null) {
      throw new IllegalStateException("canvas document disappeared after insert");
    }
    notifier.revisionChanged(canvasId, persisted.getRevision());
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
  @Transactional
  public boolean advanceRevision(UUID canvasId, long expectedRevision, long newRevision) {
    if (documentMapper.advanceRevision(canvasId, expectedRevision, newRevision) != 1) {
      return false;
    }
    // revision 未真实变化时保持静默：不发布 revision 失效提示。
    if (newRevision != expectedRevision) {
      notifier.revisionChanged(canvasId, newRevision);
    }
    return true;
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
  public boolean updateNode(NodeRecord node) {
    return nodeMapper.update(toData(node)) == 1;
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
  public boolean updateGroup(CanvasGroup group) {
    return groupMapper.update(toData(group)) == 1;
  }

  @Override
  public boolean deleteGroup(UUID canvasId, UUID groupId) {
    return groupMapper.deleteById(canvasId, groupId) == 1;
  }

  @Override
  public Optional<CommandDedup> findCommandDedup(UUID canvasId, UUID idempotencyKey) {
    return Optional.ofNullable(commandDedupMapper.findById(canvasId, idempotencyKey))
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
        document.getRevision(),
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
        CanvasNodeFunctionJson.decode(node.getFunctionJson()));
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
    data.setFunctionJson(CanvasNodeFunctionJson.encode(node.function()));
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

  private static CommandDedup toDomain(CanvasCommandDedupDO commandDedup) {
    return new CommandDedup(
        commandDedup.getCanvasId(),
        commandDedup.getIdempotencyKey(),
        commandDedup.getRequestHash(),
        commandDedup.getAcceptedRevision());
  }

  private static CanvasCommandDedupDO toData(CommandDedup commandDedup) {
    CanvasCommandDedupDO data = new CanvasCommandDedupDO();
    data.setCanvasId(commandDedup.canvasId());
    data.setIdempotencyKey(commandDedup.idempotencyKey());
    data.setRequestHash(commandDedup.requestHash());
    data.setAcceptedRevision(commandDedup.acceptedRevision());
    return data;
  }
}
