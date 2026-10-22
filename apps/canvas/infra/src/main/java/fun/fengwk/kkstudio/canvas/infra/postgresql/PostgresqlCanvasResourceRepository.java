package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@code canvas_resource} 的持久化实现。 */
@Repository
public class PostgresqlCanvasResourceRepository implements CanvasResourceRepository {

  private final CanvasResourceJdbcStore resourceStore;

  public PostgresqlCanvasResourceRepository(CanvasResourceJdbcStore resourceStore) {
    this.resourceStore = Objects.requireNonNull(resourceStore, "resourceStore");
  }

  @Override
  public void add(CanvasResource resource) {
    if (resourceStore.insert(toDO(resource)) != 1) {
      throw new IllegalStateException("insert canvas resource failed: " + resource.id());
    }
  }

  @Override
  public boolean addIfAbsent(CanvasResource resource) {
    return resourceStore.insertIfAbsent(toDO(resource)) == 1;
  }

  @Override
  public Optional<CanvasResource> findById(UUID canvasId, UUID resourceId) {
    return resourceStore
        .findById(canvasId, resourceId)
        .map(PostgresqlCanvasResourceRepository::toDomain);
  }

  @Override
  public Optional<CanvasResource> findByIdForUpdate(UUID canvasId, UUID resourceId) {
    return resourceStore
        .findByIdForUpdate(canvasId, resourceId)
        .map(PostgresqlCanvasResourceRepository::toDomain);
  }

  @Override
  public List<CanvasResource> findByCanvasId(UUID canvasId) {
    List<CanvasResource> resources = new ArrayList<>();
    for (CanvasResourceDO resource : resourceStore.listByCanvas(canvasId)) {
      resources.add(toDomain(resource));
    }
    return List.copyOf(resources);
  }

  @Override
  public List<CanvasResource> findByOwnerNode(UUID canvasId, UUID nodeId) {
    List<CanvasResource> resources = new ArrayList<>();
    for (CanvasResourceDO resource : resourceStore.listByOwnerNode(canvasId, nodeId)) {
      resources.add(toDomain(resource));
    }
    return List.copyOf(resources);
  }

  @Override
  public boolean detachOwner(UUID canvasId, UUID resourceId, UUID ownerNodeId) {
    return resourceStore.detachOwner(canvasId, resourceId, ownerNodeId) == 1;
  }

  @Override
  public boolean attachOwner(UUID canvasId, UUID resourceId, UUID ownerNodeId, int resourceIndex) {
    return resourceStore.attachOwner(canvasId, resourceId, ownerNodeId, resourceIndex) == 1;
  }

  @Override
  public boolean delete(UUID canvasId, UUID resourceId) {
    return resourceStore.delete(canvasId, resourceId) == 1;
  }

  @Override
  public int deleteByOwnerNode(UUID canvasId, UUID nodeId) {
    return resourceStore.deleteByOwnerNode(canvasId, nodeId);
  }

  @Override
  public int deleteByCanvas(UUID canvasId) {
    return resourceStore.deleteByCanvas(canvasId);
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
