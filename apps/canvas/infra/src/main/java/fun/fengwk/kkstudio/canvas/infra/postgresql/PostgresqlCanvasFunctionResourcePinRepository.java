package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePin;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePinRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** {@code canvas_function_resource_pin} 的持久化实现。 */
@Repository
public class PostgresqlCanvasFunctionResourcePinRepository
    implements CanvasFunctionResourcePinRepository {

  private final CanvasFunctionResourcePinJdbcStore pinStore;

  public PostgresqlCanvasFunctionResourcePinRepository(
      CanvasFunctionResourcePinJdbcStore pinStore) {
    this.pinStore = Objects.requireNonNull(pinStore, "pinStore");
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
    if (pinStore.insertAll(data) != data.size()) {
      throw new IllegalStateException("insert canvas function resource refs failed");
    }
  }

  @Override
  public List<CanvasFunctionResourcePin> findByRun(UUID canvasId, UUID nodeId, UUID requestId) {
    return toDomain(pinStore.findByRun(canvasId, nodeId, requestId));
  }

  @Override
  public List<CanvasFunctionResourcePin> findByNode(UUID canvasId, UUID nodeId) {
    return toDomain(pinStore.findByNode(canvasId, nodeId));
  }

  @Override
  public List<CanvasFunctionResourcePin> findByCanvas(UUID canvasId) {
    return toDomain(pinStore.findByCanvas(canvasId));
  }

  @Override
  public int countByResource(UUID canvasId, UUID resourceId) {
    return pinStore.countByResource(canvasId, resourceId);
  }

  @Override
  public boolean deleteByRun(UUID canvasId, UUID nodeId, UUID requestId) {
    return pinStore.deleteByRun(canvasId, nodeId, requestId) >= 0;
  }

  @Override
  public int deleteByNode(UUID canvasId, UUID nodeId) {
    return pinStore.deleteByNode(canvasId, nodeId);
  }

  @Override
  public int deleteByCanvas(UUID canvasId) {
    return pinStore.deleteByCanvas(canvasId);
  }

  private static List<CanvasFunctionResourcePin> toDomain(List<CanvasFunctionResourcePinDO> refs) {
    List<CanvasFunctionResourcePin> pins = new ArrayList<>(refs.size());
    for (CanvasFunctionResourcePinDO ref : refs) {
      pins.add(
          new CanvasFunctionResourcePin(
              ref.getCanvasId(),
              ref.getNodeId(),
              ref.getRequestId(),
              ref.getResourceId(),
              CanvasFunctionResourcePin.Role.valueOf(ref.getRole())));
    }
    return List.copyOf(pins);
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
