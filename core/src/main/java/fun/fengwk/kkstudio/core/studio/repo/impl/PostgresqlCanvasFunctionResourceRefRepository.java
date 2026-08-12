package fun.fengwk.kkstudio.core.studio.repo.impl;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasFunctionResourceRefMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasFunctionResourceRefDO;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionResourceRef;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionResourceRefRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** {@code canvas_function_resource_ref} 的持久化实现。 */
@Repository
public class PostgresqlCanvasFunctionResourceRefRepository
    implements CanvasFunctionResourceRefRepository {

  private final CanvasFunctionResourceRefMapper refMapper;

  public PostgresqlCanvasFunctionResourceRefRepository(CanvasFunctionResourceRefMapper refMapper) {
    this.refMapper = Objects.requireNonNull(refMapper, "refMapper");
  }

  @Override
  public void addAll(List<CanvasFunctionResourceRef> refs) {
    if (refs.isEmpty()) {
      return;
    }
    List<CanvasFunctionResourceRefDO> data = new ArrayList<>(refs.size());
    for (CanvasFunctionResourceRef ref : refs) {
      data.add(toDO(ref));
    }
    if (refMapper.insertAll(data) != data.size()) {
      throw new IllegalStateException("insert canvas function resource refs failed");
    }
  }

  @Override
  public List<CanvasFunctionResourceRef> findByRun(UUID canvasId, UUID nodeId, UUID requestId) {
    List<CanvasFunctionResourceRef> refs = new ArrayList<>();
    for (CanvasFunctionResourceRefDO ref : refMapper.findByRun(canvasId, nodeId, requestId)) {
      refs.add(toDomain(ref));
    }
    return List.copyOf(refs);
  }

  @Override
  public boolean deleteByRun(UUID canvasId, UUID nodeId, UUID requestId) {
    return refMapper.deleteByRun(canvasId, nodeId, requestId) >= 0;
  }

  @Override
  public int deleteByNode(UUID canvasId, UUID nodeId) {
    return refMapper.deleteByNode(canvasId, nodeId);
  }

  @Override
  public int deleteByCanvas(UUID canvasId) {
    return refMapper.deleteByCanvas(canvasId);
  }

  private static CanvasFunctionResourceRef toDomain(CanvasFunctionResourceRefDO ref) {
    return new CanvasFunctionResourceRef(
        ref.getCanvasId(),
        ref.getNodeId(),
        ref.getRequestId(),
        ref.getResourceId(),
        CanvasFunctionResourceRef.Role.valueOf(ref.getRole()));
  }

  private static CanvasFunctionResourceRefDO toDO(CanvasFunctionResourceRef ref) {
    CanvasFunctionResourceRefDO data = new CanvasFunctionResourceRefDO();
    data.setCanvasId(ref.canvasId());
    data.setNodeId(ref.nodeId());
    data.setRequestId(ref.requestId());
    data.setResourceId(ref.resourceId());
    data.setRole(ref.role().name());
    return data;
  }
}
