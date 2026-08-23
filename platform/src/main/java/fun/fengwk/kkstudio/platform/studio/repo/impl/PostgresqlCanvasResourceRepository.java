package fun.fengwk.kkstudio.platform.studio.repo.impl;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.platform.studio.repo.impl.mapper.CanvasResourceMapper;
import fun.fengwk.kkstudio.platform.studio.repo.impl.model.CanvasResourceDO;

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

  private final CanvasResourceMapper resourceMapper;

  public PostgresqlCanvasResourceRepository(CanvasResourceMapper resourceMapper) {
    this.resourceMapper = Objects.requireNonNull(resourceMapper, "resourceMapper");
  }

  @Override
  public void add(CanvasResource resource) {
    if (resourceMapper.insert(toDO(resource)) != 1) {
      throw new IllegalStateException("insert canvas resource failed: " + resource.id());
    }
  }

  @Override
  public Optional<CanvasResource> findById(UUID canvasId, UUID resourceId) {
    return Optional.ofNullable(resourceMapper.getById(canvasId, resourceId))
        .map(PostgresqlCanvasResourceRepository::toDomain);
  }

  @Override
  public Optional<CanvasResource> findByIdForUpdate(UUID canvasId, UUID resourceId) {
    return Optional.ofNullable(resourceMapper.getByIdForUpdate(canvasId, resourceId))
        .map(PostgresqlCanvasResourceRepository::toDomain);
  }

  @Override
  public List<CanvasResource> findByCanvasId(UUID canvasId) {
    List<CanvasResource> resources = new ArrayList<>();
    for (CanvasResourceDO resource : resourceMapper.listByCanvas(canvasId)) {
      resources.add(toDomain(resource));
    }
    return List.copyOf(resources);
  }

  @Override
  public List<CanvasResource> findByOwnerNodeId(UUID nodeId) {
    List<CanvasResource> resources = new ArrayList<>();
    for (CanvasResourceDO resource : resourceMapper.listByOwnerNodeId(nodeId)) {
      resources.add(toDomain(resource));
    }
    return List.copyOf(resources);
  }

  @Override
  public boolean delete(UUID canvasId, UUID resourceId) {
    return resourceMapper.delete(canvasId, resourceId) == 1;
  }

  @Override
  public int deleteByOwnerNode(UUID canvasId, UUID nodeId) {
    return resourceMapper.deleteByOwnerNode(canvasId, nodeId);
  }

  @Override
  public int deleteByCanvas(UUID canvasId) {
    return resourceMapper.deleteByCanvas(canvasId);
  }

  public static CanvasResource toDomain(CanvasResourceDO resource) {
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

  public static CanvasResourceDO toDO(CanvasResource resource) {
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
