package fun.fengwk.kkstudio.canvas;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable Canvas Resource 的持久化端口。
 *
 * <p>Storage 拥有 Blob，本端口只负责 Canvas Resource 行本身；内容访问一律通过资源上的 {@code blobId} 交给全局 Storage。
 * 资源直接属于节点（{@code ownerNodeId + resourceIndex}），节点/画布删除时按 owned 集合显式清理。
 */
public interface CanvasResourceRepository {

  void add(CanvasResource resource);

  boolean addIfAbsent(CanvasResource resource);

  Optional<CanvasResource> findById(UUID canvasId, UUID resourceId);

  Optional<CanvasResource> findByIdForUpdate(UUID canvasId, UUID resourceId);

  List<CanvasResource> findByCanvasId(UUID canvasId);

  List<CanvasResource> findByOwnerNode(UUID canvasId, UUID nodeId);

  List<CanvasResource> findByOwnerNodeId(UUID nodeId);

  boolean detachOwner(UUID canvasId, UUID resourceId, UUID ownerNodeId);

  boolean attachOwner(UUID canvasId, UUID resourceId, UUID ownerNodeId, int resourceIndex);

  boolean updateTextContent(UUID canvasId, UUID nodeId, String textContent);

  boolean delete(UUID canvasId, UUID resourceId);

  int deleteByOwnerNode(UUID canvasId, UUID nodeId);

  int deleteByCanvas(UUID canvasId);
}
