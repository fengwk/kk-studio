package fun.fengwk.kkstudio.canvas.infra.function;

import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePin;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceLifecycle;
import fun.fengwk.kkstudio.canvas.infra.postgresql.PostgresqlCanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.infra.postgresql.PostgresqlCanvasResourceRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 忠实模拟 owner/pin 行约束但不引入 Platform Blob ref_count，使 Runtime foundation 保持模块内闭环。 */
final class TestCanvasResourceLifecycle implements CanvasResourceLifecycle {

  private final PostgresqlCanvasResourceRepository resources;
  private final PostgresqlCanvasFunctionResourcePinRepository pins;

  TestCanvasResourceLifecycle(
      PostgresqlCanvasResourceRepository resources,
      PostgresqlCanvasFunctionResourcePinRepository pins) {
    this.resources = resources;
    this.pins = pins;
  }

  @Override
  public void releaseRunPins(UUID canvasId, UUID nodeId, UUID requestId) {
    List<CanvasFunctionResourcePin> released = pins.findByRun(canvasId, nodeId, requestId);
    pins.deleteByRun(canvasId, nodeId, requestId);
    collectUnowned(canvasId, released);
  }

  @Override
  public void releaseNodePins(UUID canvasId, UUID nodeId) {
    List<CanvasFunctionResourcePin> released = pins.findByNode(canvasId, nodeId);
    pins.deleteByNode(canvasId, nodeId);
    collectUnowned(canvasId, released);
  }

  @Override
  public void discardResource(UUID canvasId, UUID resourceId) {
    resources.delete(canvasId, resourceId);
  }

  @Override
  public List<CanvasResource> replaceOwnedWithTargets(
      UUID canvasId, UUID nodeId, List<UUID> orderedTargetResourceIds) {
    Objects.requireNonNull(orderedTargetResourceIds, "orderedTargetResourceIds");
    if (orderedTargetResourceIds.isEmpty()) {
      throw new IllegalArgumentException("Function output plan must not be empty");
    }
    Set<UUID> distinct = new LinkedHashSet<>(orderedTargetResourceIds);
    if (distinct.size() != orderedTargetResourceIds.size() || distinct.contains(null)) {
      throw new IllegalArgumentException("Function output targets must be distinct Resources");
    }
    List<CanvasResource> targets = new ArrayList<>(orderedTargetResourceIds.size());
    for (UUID targetResourceId : orderedTargetResourceIds) {
      CanvasResource target = resources.findByIdForUpdate(canvasId, targetResourceId).orElse(null);
      if (target == null || target.ownerNodeId() != null || target.resourceIndex() != null) {
        throw new IllegalArgumentException(
            "Function target must be an unowned Resource in the same canvas");
      }
      targets.add(target);
    }
    for (CanvasResource current : resources.findByOwnerNode(canvasId, nodeId)) {
      if (pins.countByResource(canvasId, current.id()) > 0) {
        if (!resources.detachOwner(canvasId, current.id(), nodeId)) {
          throw new IllegalStateException("detach replaced canvas resource failed");
        }
      } else {
        resources.delete(canvasId, current.id());
      }
    }
    List<CanvasResource> attached = new ArrayList<>(targets.size());
    for (int index = 0; index < targets.size(); index++) {
      UUID targetResourceId = targets.get(index).id();
      if (!resources.attachOwner(canvasId, targetResourceId, nodeId, index)) {
        throw new IllegalStateException("attach Function target Resource failed");
      }
      attached.add(targets.get(index).withSlot(nodeId, index));
    }
    return List.copyOf(attached);
  }

  @Override
  public void discardUnownedTargets(UUID canvasId, Collection<UUID> targetResourceIds) {
    Objects.requireNonNull(targetResourceIds, "targetResourceIds");
    for (UUID resourceId : new LinkedHashSet<>(targetResourceIds)) {
      if (resourceId == null) {
        continue;
      }
      resources
          .findByIdForUpdate(canvasId, resourceId)
          .filter(resource -> resource.ownerNodeId() == null)
          .ifPresent(resource -> resources.delete(canvasId, resource.id()));
    }
  }

  private void collectUnowned(UUID canvasId, List<CanvasFunctionResourcePin> released) {
    Set<UUID> resourceIds = new LinkedHashSet<>();
    for (CanvasFunctionResourcePin pin : released) {
      resourceIds.add(pin.resourceId());
    }
    for (UUID resourceId : resourceIds) {
      if (pins.countByResource(canvasId, resourceId) == 0) {
        resources
            .findByIdForUpdate(canvasId, resourceId)
            .filter(resource -> resource.ownerNodeId() == null)
            .ifPresent(resource -> resources.delete(canvasId, resource.id()));
      }
    }
  }
}
