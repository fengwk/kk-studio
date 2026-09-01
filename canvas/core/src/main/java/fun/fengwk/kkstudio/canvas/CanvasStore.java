package fun.fengwk.kkstudio.canvas;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Canvas document 与 graph 行状态的聚合持久化端口。
 *
 * <p>锁语义是跨域事务协议的一部分：命令、Function Run 与 owner 删除先锁 document；Function Run 再锁 node。实现必须保留 PostgreSQL
 * 当前的 {@code FOR UPDATE}/{@code FOR KEY SHARE} 行为。
 */
public interface CanvasStore {

  CanvasDocument addDocument(UUID canvasId, String title);

  Optional<CanvasDocument> findDocument(UUID canvasId);

  Optional<CanvasDocument> lockDocument(UUID canvasId);

  Optional<CanvasDocument> lockDocumentForKeyShare(UUID canvasId);

  List<CanvasDocument> listDocuments();

  boolean advanceDocumentVersion(UUID canvasId, long expectedVersion, long newVersion);

  boolean deleteDocument(UUID canvasId);

  void addNode(NodeRecord node);

  Optional<NodeRecord> findNode(UUID canvasId, UUID nodeId);

  Optional<NodeRecord> lockNode(UUID canvasId, UUID nodeId);

  List<NodeRecord> listNodes(UUID canvasId);

  boolean updateNodeTransform(NodeRecord node);

  boolean renameNode(UUID canvasId, UUID nodeId, String name);

  boolean updateNodeFunction(
      UUID canvasId, UUID nodeId, String modelKey, String functionConfigJson);

  boolean attachNodeToGroupIfUngrouped(UUID canvasId, UUID nodeId, UUID groupId);

  boolean detachNodeFromGroup(UUID canvasId, UUID groupId, UUID nodeId);

  int detachAllNodesFromGroup(UUID canvasId, UUID groupId);

  int moveGroupNodes(UUID canvasId, UUID groupId, double deltaX, double deltaY);

  boolean deleteNode(UUID canvasId, UUID nodeId);

  void addGroup(CanvasGroup group);

  Optional<CanvasGroup> findGroup(UUID canvasId, UUID groupId);

  List<CanvasGroup> listGroups(UUID canvasId);

  boolean moveGroup(CanvasGroup group);

  boolean renameGroup(UUID canvasId, UUID groupId, String title);

  boolean deleteGroup(UUID canvasId, UUID groupId);

  void addLink(CanvasLink link);

  List<CanvasLink> listLinks(UUID canvasId);

  boolean linkExists(UUID canvasId, UUID sourceNodeId, UUID targetNodeId);

  boolean deleteLink(UUID canvasId, UUID sourceNodeId, UUID targetNodeId);

  int deleteLinksByNode(UUID canvasId, UUID nodeId);

  int deleteLinksByCanvas(UUID canvasId);

  Optional<CommandDedup> findCommandDedup(UUID canvasId, UUID idempotencyKey);

  void addCommandDedup(CommandDedup commandDedup);

  int deleteCommandDedupByCanvas(UUID canvasId);

  /** 仅 Canvas Core 领域类型无法表达的可变 graph 行状态。 */
  record NodeRecord(
      UUID id,
      UUID canvasId,
      String name,
      CanvasTransform transform,
      UUID groupId,
      String modelKey,
      String functionConfigJson) {

    public NodeRecord {
      id = Objects.requireNonNull(id, "id");
      canvasId = Objects.requireNonNull(canvasId, "canvasId");
      name = Objects.requireNonNull(name, "name");
      transform = Objects.requireNonNull(transform, "transform");
    }

    public NodeRecord withName(String newName) {
      return new NodeRecord(
          id, canvasId, newName, transform, groupId, modelKey, functionConfigJson);
    }

    public NodeRecord withTransform(CanvasTransform newTransform) {
      return new NodeRecord(
          id, canvasId, name, newTransform, groupId, modelKey, functionConfigJson);
    }

    public NodeRecord withGroupId(UUID newGroupId) {
      return new NodeRecord(
          id, canvasId, name, transform, newGroupId, modelKey, functionConfigJson);
    }

    public NodeRecord withFunction(String newModelKey, String newFunctionConfigJson) {
      return new NodeRecord(
          id, canvasId, name, transform, groupId, newModelKey, newFunctionConfigJson);
    }
  }

  /** Canvas command batch 幂等键对应的持久化事实。 */
  record CommandDedup(UUID canvasId, UUID idempotencyKey, String requestHash) {

    public CommandDedup {
      canvasId = Objects.requireNonNull(canvasId, "canvasId");
      idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
      requestHash = Objects.requireNonNull(requestHash, "requestHash");
    }
  }
}
