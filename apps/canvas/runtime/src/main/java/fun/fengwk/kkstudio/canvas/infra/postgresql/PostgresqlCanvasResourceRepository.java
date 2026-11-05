package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.canvas.CanvasResource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable Canvas Resource 在 {@code canvas_resource} 中的持久化实现。
 *
 * <p>Storage 拥有 Blob，这里只负责 Canvas Resource 行本身；内容访问一律通过资源上的 {@code blobId} 交给全局 Storage。
 * 资源直接属于节点（{@code ownerNodeId + resourceIndex}）；解除挂接但保留行是历史资源与 pin 保活的形式，行删除与 Blob 引用释放由 {@link
 * CanvasResourceLifecycle} 负责。
 */
@Repository
public class PostgresqlCanvasResourceRepository {

  private final CanvasResourceJdbcStore resourceStore;

  public PostgresqlCanvasResourceRepository(CanvasResourceJdbcStore resourceStore) {
    this.resourceStore = Objects.requireNonNull(resourceStore, "resourceStore");
  }

  public void add(CanvasResource resource) {
    if (resourceStore.insert(toDO(resource)) != 1) {
      throw new IllegalStateException("insert canvas resource failed: " + resource.id());
    }
  }

  public boolean addIfAbsent(CanvasResource resource) {
    return resourceStore.insertIfAbsent(toDO(resource)) == 1;
  }

  public Optional<CanvasResource> findById(UUID canvasId, UUID resourceId) {
    return resourceStore
        .findById(canvasId, resourceId)
        .map(PostgresqlCanvasResourceRepository::toDomain);
  }

  public Optional<CanvasResource> findByIdForUpdate(UUID canvasId, UUID resourceId) {
    return resourceStore
        .findByIdForUpdate(canvasId, resourceId)
        .map(PostgresqlCanvasResourceRepository::toDomain);
  }

  public List<CanvasResource> findByCanvasId(UUID canvasId) {
    List<CanvasResource> resources = new ArrayList<>();
    for (CanvasResourceDO resource : resourceStore.listByCanvas(canvasId)) {
      resources.add(toDomain(resource));
    }
    return List.copyOf(resources);
  }

  public List<CanvasResource> findByOwnerNode(UUID canvasId, UUID nodeId) {
    List<CanvasResource> resources = new ArrayList<>();
    for (CanvasResourceDO resource : resourceStore.listByOwnerNode(canvasId, nodeId)) {
      resources.add(toDomain(resource));
    }
    return List.copyOf(resources);
  }

  public boolean detachOwner(UUID canvasId, UUID resourceId, UUID ownerNodeId) {
    return resourceStore.detachOwner(canvasId, resourceId, ownerNodeId) == 1;
  }

  public boolean attachOwner(UUID canvasId, UUID resourceId, UUID ownerNodeId, int resourceIndex) {
    return resourceStore.attachOwner(canvasId, resourceId, ownerNodeId, resourceIndex) == 1;
  }

  public boolean delete(UUID canvasId, UUID resourceId) {
    return resourceStore.delete(canvasId, resourceId) == 1;
  }

  public int deleteByOwnerNode(UUID canvasId, UUID nodeId) {
    return resourceStore.deleteByOwnerNode(canvasId, nodeId);
  }

  private static CanvasResource toDomain(CanvasResourceDO resource) {
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

  private static CanvasResourceDO toDO(CanvasResource resource) {
    CanvasResourceDO data = new CanvasResourceDO();
    data.setId(resource.id());
    data.setCanvasId(resource.canvasId());
    data.setOwnerNodeId(resource.ownerNodeId());
    data.setResourceIndex(resource.resourceIndex());
    data.setBlobId(resource.blobId());
    data.setName(resource.name());
    data.setTextContent(resource.textContent());
    data.setCreatedAt(OffsetDateTime.ofInstant(resource.createdAt(), ZoneOffset.UTC));
    return data;
  }
}
