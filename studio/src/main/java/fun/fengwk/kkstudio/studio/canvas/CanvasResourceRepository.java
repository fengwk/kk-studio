package fun.fengwk.kkstudio.studio.canvas;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable Canvas Resource 的持久化端口。
 *
 * <p>Storage 拥有 Blob，本端口只负责 Canvas Resource 行本身；内容访问一律通过资源上的 {@code blobId} 交给全局 Storage。
 */
public interface CanvasResourceRepository {

  void add(CanvasResource resource);

  Optional<CanvasResource> findById(UUID canvasId, UUID resourceId);

  Optional<CanvasResource> findByIdForUpdate(UUID canvasId, UUID resourceId);

  List<CanvasResource> findByCanvasId(UUID canvasId);

  List<CanvasResource> findByOwnerNodeId(UUID nodeId);

  boolean delete(UUID canvasId, UUID resourceId);
}
