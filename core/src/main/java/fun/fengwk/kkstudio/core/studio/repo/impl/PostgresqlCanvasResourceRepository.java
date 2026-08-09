package fun.fengwk.kkstudio.core.studio.repo.impl;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasResourceMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasResourceDO;
import fun.fengwk.kkstudio.studio.canvas.CanvasResource;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class PostgresqlCanvasResourceRepository implements CanvasResourceRepository {

  private final CanvasResourceMapper resourceMapper;

  public PostgresqlCanvasResourceRepository(CanvasResourceMapper resourceMapper) {
    this.resourceMapper = resourceMapper;
  }

  @Override
  public void add(CanvasResource resource) {
    resourceMapper.insert(toDO(resource));
  }

  @Override
  public boolean addIfAbsent(CanvasResource resource) {
    return resourceMapper.insertIfAbsent(toDO(resource)) == 1;
  }

  @Override
  public Optional<CanvasResource> findById(long canvasId, long resourceId) {
    return Optional.ofNullable(resourceMapper.getById(canvasId, resourceId))
        .map(PostgresqlCanvasResourceRepository::toDomain);
  }

  @Override
  public Optional<CanvasResource> findById(long resourceId) {
    return Optional.ofNullable(resourceMapper.getByResourceId(resourceId))
        .map(PostgresqlCanvasResourceRepository::toDomain);
  }

  @Override
  public List<CanvasResource> findByIds(long canvasId, List<Long> resourceIds) {
    if (resourceIds.isEmpty()) {
      return List.of();
    }
    Map<Long, CanvasResource> byId = new HashMap<>();
    for (CanvasResourceDO resource : resourceMapper.listByIds(canvasId, resourceIds)) {
      byId.put(resource.getId(), toDomain(resource));
    }
    List<CanvasResource> ordered = new ArrayList<>(resourceIds.size());
    for (Long resourceId : resourceIds) {
      CanvasResource resource = byId.get(resourceId);
      if (resource != null) {
        ordered.add(resource);
      }
    }
    return List.copyOf(ordered);
  }

  public static CanvasResource toDomain(CanvasResourceDO resource) {
    return new CanvasResource(
        resource.getId(),
        resource.getCanvasId(),
        CanvasResourceKind.valueOf(resource.getKind()),
        resource.getMediaType(),
        resource.getName(),
        resource.getSize(),
        resource.getTextContent(),
        resource.getMetadataJson(),
        resource.getCreatedAt().toInstant());
  }

  private static CanvasResourceDO toDO(CanvasResource resource) {
    CanvasResourceDO data = new CanvasResourceDO();
    data.setId(resource.id());
    data.setCanvasId(resource.canvasId());
    data.setKind(resource.kind().name());
    data.setMediaType(resource.mediaType());
    data.setName(resource.name());
    data.setSize(resource.size());
    data.setTextContent(resource.textContent());
    data.setMetadataJson(resource.metadataJson());
    data.setCreatedAt(OffsetDateTime.ofInstant(resource.createdAt(), ZoneOffset.UTC));
    return data;
  }
}
