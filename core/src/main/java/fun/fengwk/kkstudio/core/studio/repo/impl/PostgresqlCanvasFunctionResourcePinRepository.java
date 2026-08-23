package fun.fengwk.kkstudio.core.studio.repo.impl;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePin;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasFunctionResourcePinMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasFunctionResourcePinDO;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** {@code canvas_function_resource_pin} 的持久化实现。 */
@Repository
public class PostgresqlCanvasFunctionResourcePinRepository
    implements CanvasFunctionResourcePinRepository {

  private final CanvasFunctionResourcePinMapper refMapper;

  public PostgresqlCanvasFunctionResourcePinRepository(CanvasFunctionResourcePinMapper refMapper) {
    this.refMapper = Objects.requireNonNull(refMapper, "refMapper");
  }

  @Override
  public void addAll(List<CanvasFunctionResourcePin> refs) {
    if (refs.isEmpty()) {
      return;
    }
    List<CanvasFunctionResourcePinDO> data = new ArrayList<>(refs.size());
    for (CanvasFunctionResourcePin ref : refs) {
      data.add(toDO(ref));
    }
    if (refMapper.insertAll(data) != data.size()) {
      throw new IllegalStateException("insert canvas function resource refs failed");
    }
  }

  @Override
  public List<CanvasFunctionResourcePin> findByRun(UUID canvasId, UUID nodeId, UUID requestId) {
    List<CanvasFunctionResourcePin> refs = new ArrayList<>();
    for (CanvasFunctionResourcePinDO ref : refMapper.findByRun(canvasId, nodeId, requestId)) {
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

  private static CanvasFunctionResourcePin toDomain(CanvasFunctionResourcePinDO ref) {
    return new CanvasFunctionResourcePin(
        ref.getCanvasId(),
        ref.getNodeId(),
        ref.getRequestId(),
        ref.getResourceId(),
        CanvasFunctionResourcePin.Role.valueOf(ref.getRole()));
  }

  private static CanvasFunctionResourcePinDO toDO(CanvasFunctionResourcePin ref) {
    CanvasFunctionResourcePinDO data = new CanvasFunctionResourcePinDO();
    data.setCanvasId(ref.canvasId());
    data.setNodeId(ref.nodeId());
    data.setRequestId(ref.requestId());
    data.setResourceId(ref.resourceId());
    data.setRole(ref.role().name());
    return data;
  }
}
