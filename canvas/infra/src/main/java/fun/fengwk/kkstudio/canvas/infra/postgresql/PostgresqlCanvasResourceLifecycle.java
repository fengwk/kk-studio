package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.canvas.CanvasBlobReleaser;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePin;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceLifecycle;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Canvas Resource owner、Function pin 与全局 Blob 引用的单一生命周期实现。
 *
 * <p>调用方必须已经锁定 {@code canvas_document} 行。Resource 行本身贡献一个 Blob 引用；pin 只决定无 owner Resource
 * 是否保留，不额外修改引用计数。宿主 Storage 的 Blob 引用释放通过 {@link CanvasBlobReleaser} 注入，因此本类不需要了解对象存储。
 */
@Repository
public class PostgresqlCanvasResourceLifecycle implements CanvasResourceLifecycle {

  private final CanvasResourceRepository resourceRepository;
  private final CanvasFunctionResourcePinRepository pinRepository;
  private final CanvasBlobReleaser blobReleaser;

  public PostgresqlCanvasResourceLifecycle(
      CanvasResourceRepository resourceRepository,
      CanvasFunctionResourcePinRepository pinRepository,
      CanvasBlobReleaser blobReleaser) {
    this.resourceRepository = Objects.requireNonNull(resourceRepository, "resourceRepository");
    this.pinRepository = Objects.requireNonNull(pinRepository, "pinRepository");
    this.blobReleaser = Objects.requireNonNull(blobReleaser, "blobReleaser");
  }

  @Override
  public void releaseRunPins(UUID canvasId, UUID nodeId, UUID requestId) {
    List<CanvasFunctionResourcePin> pins = pinRepository.findByRun(canvasId, nodeId, requestId);
    pinRepository.deleteByRun(canvasId, nodeId, requestId);
    collectUnowned(canvasId, pins);
  }

  @Override
  public void releaseNodePins(UUID canvasId, UUID nodeId) {
    List<CanvasFunctionResourcePin> pins = pinRepository.findByNode(canvasId, nodeId);
    pinRepository.deleteByNode(canvasId, nodeId);
    collectUnowned(canvasId, pins);
  }

  @Override
  public void releaseCanvasPins(UUID canvasId) {
    pinRepository.deleteByCanvas(canvasId);
  }

  @Override
  public CanvasResource replaceOwnedWithTarget(UUID canvasId, UUID nodeId, UUID targetResourceId) {
    CanvasResource target =
        resourceRepository.findByIdForUpdate(canvasId, targetResourceId).orElse(null);
    if (target == null
        || target.blobId() == null
        || target.ownerNodeId() != null
        || target.resourceIndex() != null) {
      throw new IllegalArgumentException(
          "Function target must be an unowned blob Resource in the same canvas");
    }
    for (CanvasResource current : resourceRepository.findByOwnerNode(canvasId, nodeId)) {
      if (pinRepository.countByResource(canvasId, current.id()) > 0) {
        if (!resourceRepository.detachOwner(canvasId, current.id(), nodeId)) {
          throw new IllegalStateException(
              "detach replaced canvas resource failed: " + current.id());
        }
      } else {
        discardResource(canvasId, current.id());
      }
    }
    if (!resourceRepository.attachOwner(canvasId, targetResourceId, nodeId, 0)) {
      throw new IllegalStateException(
          "attach Function target Resource failed: " + targetResourceId);
    }
    return target.withSlot(nodeId, 0);
  }

  @Override
  public void discardUnownedTarget(UUID canvasId, UUID targetResourceId) {
    resourceRepository
        .findByIdForUpdate(canvasId, targetResourceId)
        .filter(resource -> resource.ownerNodeId() == null)
        .ifPresent(resource -> discardResource(canvasId, resource.id()));
  }

  @Override
  public void discardResource(UUID canvasId, UUID resourceId) {
    CanvasResource resource =
        resourceRepository.findByIdForUpdate(canvasId, resourceId).orElse(null);
    if (resource == null) {
      return;
    }
    if (!resourceRepository.delete(canvasId, resourceId)) {
      throw new IllegalStateException("delete canvas resource failed: " + resourceId);
    }
    if (resource.blobId() != null) {
      blobReleaser.release(resource.blobId());
    }
  }

  @Override
  public void deleteCanvasResources(UUID canvasId) {
    for (CanvasResource resource : resourceRepository.findByCanvasId(canvasId)) {
      discardResource(canvasId, resource.id());
    }
  }

  /** 对刚释放的 pin 涉及的资源去重检查，仅回收既无剩余 pin 又无节点 owner 的历史资源。 */
  private void collectUnowned(UUID canvasId, List<CanvasFunctionResourcePin> pins) {
    Set<UUID> resourceIds = new LinkedHashSet<>();
    for (CanvasFunctionResourcePin pin : pins) {
      resourceIds.add(pin.resourceId());
    }
    for (UUID resourceId : resourceIds) {
      if (pinRepository.countByResource(canvasId, resourceId) > 0) {
        continue;
      }
      resourceRepository
          .findByIdForUpdate(canvasId, resourceId)
          .filter(resource -> resource.ownerNodeId() == null)
          .ifPresent(resource -> discardResource(canvasId, resource.id()));
    }
  }
}
