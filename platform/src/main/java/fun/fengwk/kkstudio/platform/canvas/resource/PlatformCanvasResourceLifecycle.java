package fun.fengwk.kkstudio.platform.canvas.resource;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePin;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceLifecycle;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Canvas Resource owner、Function pin 与全局 Blob 引用的单一生命周期实现。
 *
 * <p>调用方必须已经锁定 {@code canvas_document} 行。Resource 行本身贡献一个 Blob 引用；pin 只决定无 owner Resource
 * 是否保留，不额外修改 ref_count。
 */
@Component
public class PlatformCanvasResourceLifecycle implements CanvasResourceLifecycle {

  private final CanvasResourceRepository resourceRepository;
  private final CanvasFunctionResourcePinRepository pinRepository;
  private final StorageBlobManager blobManager;

  public PlatformCanvasResourceLifecycle(
      CanvasResourceRepository resourceRepository,
      CanvasFunctionResourcePinRepository pinRepository,
      StorageBlobManager blobManager) {
    this.resourceRepository = Objects.requireNonNull(resourceRepository, "resourceRepository");
    this.pinRepository = Objects.requireNonNull(pinRepository, "pinRepository");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
  }

  /** 释放指定 Run 的全部 pin，并回收因此失去最后一个 pin 的无 owner Resource。 */
  @Override
  public void releaseRunPins(UUID canvasId, UUID nodeId, UUID requestId) {
    List<CanvasFunctionResourcePin> refs = pinRepository.findByRun(canvasId, nodeId, requestId);
    pinRepository.deleteByRun(canvasId, nodeId, requestId);
    collectUnowned(canvasId, refs);
  }

  /** 释放节点当前 Run 的全部 pin，并回收因此失去最后一个 pin 的无 owner Resource。 */
  @Override
  public void releaseNodePins(UUID canvasId, UUID nodeId) {
    List<CanvasFunctionResourcePin> refs = pinRepository.findByNode(canvasId, nodeId);
    pinRepository.deleteByNode(canvasId, nodeId);
    collectUnowned(canvasId, refs);
  }

  /** 画布深删除前清空全部 pin；画布内全部 Resource 随后由 {@link #deleteCanvasResources} 回收。 */
  @Override
  public void releaseCanvasPins(UUID canvasId) {
    pinRepository.deleteByCanvas(canvasId);
  }

  /** 删除节点拥有的资源。仍被任一 Function Run pin 的资源只解除 owner；无 pin 资源删除行并释放其 Blob 引用。 */
  @Override
  public void deleteOwnedResources(UUID canvasId, UUID nodeId) {
    for (CanvasResource resource : resourceRepository.findByOwnerNode(canvasId, nodeId)) {
      if (pinRepository.countByResource(canvasId, resource.id()) > 0) {
        if (!resourceRepository.detachOwner(canvasId, resource.id(), nodeId)) {
          throw new IllegalStateException("detach pinned canvas resource failed: " + resource.id());
        }
      } else {
        deleteResource(resource);
      }
    }
  }

  /** Function success 的资源交换：旧 owned Resource 若仍被其他 Run pin 则解除 owner，否则删除；随后把预分配目标挂到 index 0。 */
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
        deleteResource(current);
      }
    }
    if (!resourceRepository.attachOwner(canvasId, targetResourceId, nodeId, 0)) {
      throw new IllegalStateException(
          "attach Function target Resource failed: " + targetResourceId);
    }
    return new CanvasResource(
        target.id(),
        target.canvasId(),
        nodeId,
        0,
        target.blobId(),
        target.name(),
        target.textContent(),
        target.createdAt());
  }

  /** 失败、取消或迟到结果清理：只有无 owner 的目标 Resource 会被删除；保留 OUTPUT pin 本身。 */
  @Override
  public void discardUnownedTarget(UUID canvasId, UUID targetResourceId) {
    resourceRepository
        .findByIdForUpdate(canvasId, targetResourceId)
        .filter(resource -> resource.ownerNodeId() == null)
        .ifPresent(this::deleteResource);
  }

  /** 画布深删除：调用方须先删除全部 pin，随后删除每个 Resource 行并释放其 Blob 引用。 */
  @Override
  public void deleteCanvasResources(UUID canvasId) {
    for (CanvasResource resource : resourceRepository.findByCanvasId(canvasId)) {
      deleteResource(resource);
    }
  }

  /** 对已释放 pin 涉及的资源去重检查，仅删除既无剩余 pin 又无节点 owner 的资源。 */
  private void collectUnowned(UUID canvasId, List<CanvasFunctionResourcePin> refs) {
    Set<UUID> resourceIds = new LinkedHashSet<>();
    for (CanvasFunctionResourcePin ref : refs) {
      resourceIds.add(ref.resourceId());
    }
    for (UUID resourceId : resourceIds) {
      if (pinRepository.countByResource(canvasId, resourceId) == 0) {
        resourceRepository
            .findByIdForUpdate(canvasId, resourceId)
            .filter(resource -> resource.ownerNodeId() == null)
            .ifPresent(this::deleteResource);
      }
    }
  }

  /** 先删除 Canvas Resource 行，再释放其可选 Blob 引用，任何失败均向上抛出。 */
  private void deleteResource(CanvasResource resource) {
    if (!resourceRepository.delete(resource.canvasId(), resource.id())) {
      throw new IllegalStateException("delete canvas resource failed: " + resource.id());
    }
    if (resource.blobId() != null && !blobManager.release(resource.blobId())) {
      throw new IllegalStateException("release canvas resource blob failed: " + resource.blobId());
    }
  }
}
