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

/** {@link CanvasStore} 的 PostgreSQL/JDBC 实现。 */
@Repository
public class PostgresqlCanvasStore implements CanvasStore {

  private final CanvasDocumentJdbcStore documentStore;
  private final CanvasNodeJdbcStore nodeStore;
  private final CanvasGroupJdbcStore groupStore;
  private final CanvasCommandDedupJdbcStore commandDedupStore;
  private final PostgresqlCanvasChangeNotifier notifier;

  public PostgresqlCanvasStore(
      CanvasDocumentJdbcStore documentStore,
      CanvasNodeJdbcStore nodeStore,
      CanvasGroupJdbcStore groupStore,
      CanvasCommandDedupJdbcStore commandDedupStore,
      PostgresqlCanvasChangeNotifier notifier) {
    this.documentStore = Objects.requireNonNull(documentStore, "documentStore");
    this.nodeStore = Objects.requireNonNull(nodeStore, "nodeStore");
    this.groupStore = Objects.requireNonNull(groupStore, "groupStore");
    this.commandDedupStore = Objects.requireNonNull(commandDedupStore, "commandDedupStore");
    this.notifier = Objects.requireNonNull(notifier, "notifier");
  }

  @Override
  @Transactional
  public CanvasDocument addDocument(UUID canvasId, String title) {
    CanvasDocumentDO document = new CanvasDocumentDO();
    document.setId(canvasId);
    document.setTitle(title);
    documentStore.insert(document);
    CanvasDocumentDO persisted = documentStore.findById(canvasId).orElseThrow();
    notifier.revisionChanged(canvasId, persisted.getRevision());
    return toDomain(persisted);
  }

  @Override
  public Optional<CanvasDocument> findDocument(UUID canvasId) {
    return documentStore.findById(canvasId).map(PostgresqlCanvasStore::toDomain);
  }

  @Override
  public Optional<CanvasDocument> lockDocument(UUID canvasId) {
    return documentStore.findByIdForUpdate(canvasId).map(PostgresqlCanvasStore::toDomain);
  }

  @Override
  public Optional<CanvasDocument> lockDocumentForKeyShare(UUID canvasId) {
    return documentStore.findByIdForKeyShare(canvasId).map(PostgresqlCanvasStore::toDomain);
  }

  @Override
  public List<CanvasDocument> listDocuments() {
    List<CanvasDocument> documents = new ArrayList<>();
    for (CanvasDocumentDO document : documentStore.listAll()) {
      documents.add(toDomain(document));
    }
    return List.copyOf(documents);
  }

  @Override
  @Transactional
  public boolean advanceRevision(UUID canvasId, long expectedRevision, long newRevision) {
    if (documentStore.advanceRevision(canvasId, expectedRevision, newRevision) != 1) {
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
    return documentStore.deleteById(canvasId) == 1;
  }

  @Override
  public void addNode(NodeRecord node) {
    if (nodeStore.insert(toData(node)) != 1) {
      throw new IllegalStateException("insert canvas node failed: " + node.id());
    }
  }

  @Override
  public Optional<NodeRecord> findNode(UUID canvasId, UUID nodeId) {
    return nodeStore.findById(canvasId, nodeId).map(PostgresqlCanvasStore::toDomain);
  }

  @Override
  public Optional<NodeRecord> lockNode(UUID canvasId, UUID nodeId) {
    return nodeStore.findByIdForUpdate(canvasId, nodeId).map(PostgresqlCanvasStore::toDomain);
  }

  @Override
  public List<NodeRecord> listNodes(UUID canvasId) {
    List<NodeRecord> nodes = new ArrayList<>();
    for (CanvasNodeDO node : nodeStore.listByCanvas(canvasId)) {
      nodes.add(toDomain(node));
    }
    return List.copyOf(nodes);
  }

  @Override
  public boolean updateNode(NodeRecord node) {
    return nodeStore.update(toData(node)) == 1;
  }

  @Override
  public boolean deleteNode(UUID canvasId, UUID nodeId) {
    return nodeStore.deleteById(canvasId, nodeId) == 1;
  }

  @Override
  public void addGroup(CanvasGroup group) {
    if (groupStore.insert(toData(group)) != 1) {
      throw new IllegalStateException("insert canvas group failed: " + group.id());
    }
  }

  @Override
  public Optional<CanvasGroup> findGroup(UUID canvasId, UUID groupId) {
    return groupStore.findById(canvasId, groupId).map(PostgresqlCanvasStore::toDomain);
  }

  @Override
  public List<CanvasGroup> listGroups(UUID canvasId) {
    List<CanvasGroup> groups = new ArrayList<>();
    for (CanvasGroupDO group : groupStore.listByCanvas(canvasId)) {
      groups.add(toDomain(group));
    }
    return List.copyOf(groups);
  }

  @Override
  public boolean updateGroup(CanvasGroup group) {
    return groupStore.update(toData(group)) == 1;
  }

  @Override
  public boolean deleteGroup(UUID canvasId, UUID groupId) {
    return groupStore.deleteById(canvasId, groupId) == 1;
  }

  @Override
  public Optional<CommandDedup> findCommandDedup(UUID canvasId, UUID idempotencyKey) {
    return commandDedupStore
        .findById(canvasId, idempotencyKey)
        .map(PostgresqlCanvasStore::toDomain);
  }

  @Override
  public void addCommandDedup(CommandDedup commandDedup) {
    if (commandDedupStore.insert(toData(commandDedup)) != 1) {
      throw new IllegalStateException("insert canvas command dedup failed");
    }
  }

  @Override
  public int deleteCommandDedupByCanvas(UUID canvasId) {
    return commandDedupStore.deleteByCanvas(canvasId);
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
