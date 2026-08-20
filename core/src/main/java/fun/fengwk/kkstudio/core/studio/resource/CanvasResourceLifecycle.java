package fun.fengwk.kkstudio.core.studio.resource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasFunctionResourcePinMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasResourceMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasFunctionResourcePinDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasResourceDO;

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
public class CanvasResourceLifecycle {

  private final CanvasResourceMapper resourceMapper;
  private final CanvasFunctionResourcePinMapper refMapper;
  private final ObjectProvider<StorageBlobManager> blobManagers;

  public CanvasResourceLifecycle(
      CanvasResourceMapper resourceMapper,
      CanvasFunctionResourcePinMapper refMapper,
      ObjectProvider<StorageBlobManager> blobManagers) {
    this.resourceMapper = Objects.requireNonNull(resourceMapper, "resourceMapper");
    this.refMapper = Objects.requireNonNull(refMapper, "refMapper");
    this.blobManagers = Objects.requireNonNull(blobManagers, "blobManagers");
  }

  /** 释放指定 Run 的全部 pin，并回收因此失去最后一个 pin 的无 owner Resource。 */
  public void releaseRunPins(UUID canvasId, UUID nodeId, UUID requestId) {
    List<CanvasFunctionResourcePinDO> refs = refMapper.findByRun(canvasId, nodeId, requestId);
    refMapper.deleteByRun(canvasId, nodeId, requestId);
    collectUnowned(canvasId, refs);
  }

  /** 释放节点当前 Run 的全部 pin，并回收因此失去最后一个 pin 的无 owner Resource。 */
  public void releaseNodePins(UUID canvasId, UUID nodeId) {
    List<CanvasFunctionResourcePinDO> refs = refMapper.findByNode(canvasId, nodeId);
    refMapper.deleteByNode(canvasId, nodeId);
    collectUnowned(canvasId, refs);
  }

  /** 画布深删除前清空全部 pin；画布内全部 Resource 随后由 {@link #deleteCanvasResources} 回收。 */
  public void releaseCanvasPins(UUID canvasId) {
    refMapper.deleteByCanvas(canvasId);
  }

  /** 删除节点拥有的资源。仍被任一 Function Run pin 的资源只解除 owner；无 pin 资源删除行并释放其 Blob 引用。 */
  public void deleteOwnedResources(UUID canvasId, UUID nodeId) {
    for (CanvasResourceDO resource : resourceMapper.listByOwnerNode(canvasId, nodeId)) {
      if (refMapper.countByResource(canvasId, resource.getId()) > 0) {
        if (resourceMapper.detachOwner(canvasId, resource.getId(), nodeId) != 1) {
          throw new IllegalStateException(
              "detach pinned canvas resource failed: " + resource.getId());
        }
      } else {
        deleteResource(resource);
      }
    }
  }

  /** Function success 的资源交换：旧 owned Resource 若仍被其他 Run pin 则解除 owner，否则删除；随后把预分配目标挂到 index 0。 */
  public CanvasResourceDO replaceOwnedWithTarget(
      UUID canvasId, UUID nodeId, UUID targetResourceId) {
    CanvasResourceDO target = resourceMapper.getByIdForUpdate(canvasId, targetResourceId);
    if (target == null
        || target.getBlobId() == null
        || target.getOwnerNodeId() != null
        || target.getResourceIndex() != null) {
      throw new IllegalArgumentException(
          "Function target must be an unowned blob Resource in the same canvas");
    }
    for (CanvasResourceDO current : resourceMapper.listByOwnerNode(canvasId, nodeId)) {
      if (refMapper.countByResource(canvasId, current.getId()) > 0) {
        if (resourceMapper.detachOwner(canvasId, current.getId(), nodeId) != 1) {
          throw new IllegalStateException(
              "detach replaced canvas resource failed: " + current.getId());
        }
      } else {
        deleteResource(current);
      }
    }
    if (resourceMapper.attachOwner(canvasId, targetResourceId, nodeId, 0) != 1) {
      throw new IllegalStateException(
          "attach Function target Resource failed: " + targetResourceId);
    }
    target.setOwnerNodeId(nodeId);
    target.setResourceIndex(0);
    return target;
  }

  /** 失败、取消或迟到结果清理：只有无 owner 的目标 Resource 会被删除；保留 OUTPUT pin 本身。 */
  public void discardUnownedTarget(UUID canvasId, UUID targetResourceId) {
    CanvasResourceDO resource = resourceMapper.getByIdForUpdate(canvasId, targetResourceId);
    if (resource != null && resource.getOwnerNodeId() == null) {
      deleteResource(resource);
    }
  }

  /** 画布深删除：调用方须先删除全部 pin，随后删除每个 Resource 行并释放其 Blob 引用。 */
  public void deleteCanvasResources(UUID canvasId) {
    for (CanvasResourceDO resource : resourceMapper.listByCanvas(canvasId)) {
      deleteResource(resource);
    }
  }

  private void collectUnowned(UUID canvasId, List<CanvasFunctionResourcePinDO> refs) {
    Set<UUID> resourceIds = new LinkedHashSet<>();
    for (CanvasFunctionResourcePinDO ref : refs) {
      resourceIds.add(ref.getResourceId());
    }
    for (UUID resourceId : resourceIds) {
      if (refMapper.countByResource(canvasId, resourceId) == 0) {
        CanvasResourceDO resource = resourceMapper.getByIdForUpdate(canvasId, resourceId);
        if (resource != null && resource.getOwnerNodeId() == null) {
          deleteResource(resource);
        }
      }
    }
  }

  private void deleteResource(CanvasResourceDO resource) {
    if (resourceMapper.delete(resource.getCanvasId(), resource.getId()) != 1) {
      throw new IllegalStateException("delete canvas resource failed: " + resource.getId());
    }
    if (resource.getBlobId() != null && !requireBlobManager().release(resource.getBlobId())) {
      throw new IllegalStateException(
          "release canvas resource blob failed: " + resource.getBlobId());
    }
  }

  private StorageBlobManager requireBlobManager() {
    StorageBlobManager blobManager = blobManagers.getIfAvailable();
    if (blobManager == null) {
      throw new IllegalStateException("global blob storage is unavailable");
    }
    return blobManager;
  }
}
