package fun.fengwk.kkstudio.canvas;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Canvas document 可变行状态（document/node/group/dedup）的聚合持久化端口。
 *
 * <p>锁语义是跨域事务协议的一部分：命令、Function Run 与 owner 删除先锁 document，Function Run 再锁 node。实现必须保留 PostgreSQL
 * 当前的 {@code FOR UPDATE}/{@code FOR KEY SHARE} 行为。引用连线是 args 的读取投影，因此这里没有 link 行。
 */
public interface CanvasStore {

  CanvasDocument addDocument(UUID canvasId, String title);

  Optional<CanvasDocument> findDocument(UUID canvasId);

  Optional<CanvasDocument> lockDocument(UUID canvasId);

  Optional<CanvasDocument> lockDocumentForKeyShare(UUID canvasId);

  List<CanvasDocument> listDocuments();

  /** 在已持有 document 行锁的事务内推进 revision；返回是否命中预期值。 */
  boolean advanceRevision(UUID canvasId, long expectedRevision, long newRevision);

  boolean deleteDocument(UUID canvasId);

  void addNode(NodeRecord node);

  Optional<NodeRecord> findNode(UUID canvasId, UUID nodeId);

  Optional<NodeRecord> lockNode(UUID canvasId, UUID nodeId);

  List<NodeRecord> listNodes(UUID canvasId);

  /** 写入节点的名称、几何、分组与 Function 全行值。 */
  boolean updateNode(NodeRecord node);

  boolean deleteNode(UUID canvasId, UUID nodeId);

  void addGroup(CanvasGroup group);

  Optional<CanvasGroup> findGroup(UUID canvasId, UUID groupId);

  List<CanvasGroup> listGroups(UUID canvasId);

  boolean updateGroup(CanvasGroup group);

  boolean deleteGroup(UUID canvasId, UUID groupId);

  Optional<CommandDedup> findCommandDedup(UUID canvasId, UUID idempotencyKey);

  void addCommandDedup(CommandDedup commandDedup);

  int deleteCommandDedupByCanvas(UUID canvasId);

  /** Core 领域类型无法表达的可变 node 行状态。 */
  record NodeRecord(
      UUID id,
      UUID canvasId,
      String name,
      CanvasTransform transform,
      UUID groupId,
      CanvasFunction function) {

    public NodeRecord {
      id = Objects.requireNonNull(id, "id");
      canvasId = Objects.requireNonNull(canvasId, "canvasId");
      name = Objects.requireNonNull(name, "name");
      transform = Objects.requireNonNull(transform, "transform");
    }
  }

  /** Canvas command batch 幂等键对应的持久化事实：请求指纹与首次接受位置。 */
  record CommandDedup(
      UUID canvasId, UUID idempotencyKey, String requestHash, long acceptedRevision) {

    public CommandDedup {
      canvasId = Objects.requireNonNull(canvasId, "canvasId");
      idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
      requestHash = Objects.requireNonNull(requestHash, "requestHash");
      if (acceptedRevision < 0L) {
        throw new IllegalArgumentException("acceptedRevision must be >= 0");
      }
    }
  }
}
