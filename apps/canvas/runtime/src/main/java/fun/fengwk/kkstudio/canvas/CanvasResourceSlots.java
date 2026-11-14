package fun.fengwk.kkstudio.canvas;

import fun.fengwk.kkstudio.canvas.CanvasMutation.AttachResource;
import fun.fengwk.kkstudio.canvas.CanvasMutation.DeleteResource;
import fun.fengwk.kkstudio.canvas.CanvasMutation.DetachResource;
import fun.fengwk.kkstudio.canvas.CanvasMutation.InsertResource;
import fun.fengwk.kkstudio.common.error.DomainException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 节点资源数组一次编辑的槽位计算：把槽位意图解析成不可变 Resource 行与按序持久化步骤。
 *
 * <p>与规划状态一致地推进 {@code resources} 映射：被移除的槽位就地解除挂接或删除，保留槽位最后同步到新 index。保留资源先脱离、再统一按新顺序挂接，避免在 {@code
 * (canvas, owner, index)} 唯一约束下出现瞬时重复槽位；被 Run pin 的旧行只解除挂接，行与内容保留完好。
 */
final class CanvasResourceSlots {

  private final Clock clock;
  private final Supplier<UUID> resourceIds;

  CanvasResourceSlots(Clock clock, Supplier<UUID> resourceIds) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.resourceIds = Objects.requireNonNull(resourceIds, "resourceIds");
  }

  /**
   * 计算新槽位与持久化步骤，并把槽位变化同步到 {@code resources}。
   *
   * @param previous 编辑起点的有序 Resource id 列表
   * @param inputs 本次槽位意图（空列表表示删除全部槽位）
   * @param resources 编辑起点的 Resource 行；被移除的槽位就地从这里解除挂接或删除
   * @param pinnedResourceIds 被任一活跃 Run pin 的资源：只能解除挂接，不能删除行
   */
  Plan plan(
      UUID canvasId,
      UUID nodeId,
      List<UUID> previous,
      List<CanvasResourceInput> inputs,
      Map<UUID, CanvasResource> resources,
      Set<UUID> pinnedResourceIds) {
    List<CanvasResource> slots = new ArrayList<>(inputs.size());
    List<Boolean> retained = new ArrayList<>(inputs.size());
    Set<UUID> retainedIds = new LinkedHashSet<>();
    List<UUID> baseline = List.copyOf(previous);
    for (int index = 0; index < inputs.size(); index++) {
      addSlot(
          canvasId,
          nodeId,
          baseline,
          inputs.get(index),
          index,
          resources,
          retainedIds,
          slots,
          retained);
    }
    requireHomogeneous(slots);

    List<CanvasMutation> mutations = new ArrayList<>();
    boolean changed =
        retireMissing(baseline, retainedIds, nodeId, resources, pinnedResourceIds, mutations);
    if (hasReorderedSlot(slots, retained, baseline)) {
      changed = true;
    }
    if (changed) {
      for (UUID resourceId : retainedIds) {
        mutations.add(new DetachResource(resourceId, nodeId));
      }
      for (int index = 0; index < slots.size(); index++) {
        if (retained.get(index)) {
          mutations.add(new AttachResource(slots.get(index).id(), nodeId, index));
        }
      }
    }
    for (int index = 0; index < slots.size(); index++) {
      if (!retained.get(index)) {
        mutations.add(new InsertResource(slots.get(index)));
        changed = true;
      }
    }
    // 保留资源的槽位表达必须同步到新位置，否则本批后续命令与前归还的节点投影会看到旧 index。
    for (CanvasResource slot : slots) {
      resources.put(slot.id(), slot);
    }
    return new Plan(slots, mutations, changed);
  }

  private void addSlot(
      UUID canvasId,
      UUID nodeId,
      List<UUID> previous,
      CanvasResourceInput input,
      int index,
      Map<UUID, CanvasResource> resources,
      Set<UUID> retainedIds,
      List<CanvasResource> slots,
      List<Boolean> retained) {
    switch (input) {
      case CanvasResourceInput.Keep keep -> {
        if (!previous.contains(keep.resourceId())) {
          throw DomainException.validation(
              "canvas", "kept resource must be part of the edited node resource list");
        }
        if (!retainedIds.add(keep.resourceId())) {
          throw DomainException.validation("canvas", "kept resource must not appear twice");
        }
        slots.add(requireResource(resources, keep.resourceId()).withSlot(nodeId, index));
        retained.add(true);
      }
      case CanvasResourceInput.Text text -> {
        slots.add(newResource(canvasId, nodeId, index, null, text.name(), text.textContent()));
        retained.add(false);
      }
      case CanvasResourceInput.Blob blob -> {
        slots.add(newResource(canvasId, nodeId, index, blob.blobId(), blob.name(), null));
        retained.add(false);
      }
    }
  }

  private CanvasResource newResource(
      UUID canvasId, UUID nodeId, int index, UUID blobId, String name, String textContent) {
    return new CanvasResource(
        resourceIds.get(), canvasId, nodeId, index, blobId, name, textContent, clock.instant());
  }

  /** 编辑起点中不再被保留的槽位要么解除挂接（仍被 pin），要么删除行；返回是否产生变化。 */
  private static boolean retireMissing(
      List<UUID> previous,
      Set<UUID> retainedIds,
      UUID nodeId,
      Map<UUID, CanvasResource> resources,
      Set<UUID> pinnedResourceIds,
      List<CanvasMutation> mutations) {
    boolean changed = false;
    for (UUID resourceId : previous) {
      if (retainedIds.contains(resourceId)) {
        continue;
      }
      CanvasResource removed = requireResource(resources, resourceId);
      if (pinnedResourceIds.contains(resourceId)) {
        mutations.add(new DetachResource(resourceId, nodeId));
        resources.put(resourceId, removed.withoutOwner());
      } else {
        mutations.add(new DeleteResource(resourceId));
        resources.remove(resourceId);
      }
      changed = true;
    }
    return changed;
  }

  private static boolean hasReorderedSlot(
      List<CanvasResource> slots, List<Boolean> retained, List<UUID> previous) {
    for (int index = 0; index < slots.size(); index++) {
      if (retained.get(index) && previous.indexOf(slots.get(index).id()) != index) {
        return true;
      }
    }
    return false;
  }

  private static void requireHomogeneous(List<CanvasResource> slots) {
    if (slots.isEmpty()) {
      return;
    }
    boolean text = slots.get(0).isText();
    for (CanvasResource slot : slots) {
      if (slot.isText() != text) {
        throw DomainException.validation(
            "canvas", "node resources must share one content kind: text or media");
      }
    }
  }

  private static CanvasResource requireResource(
      Map<UUID, CanvasResource> resources, UUID resourceId) {
    CanvasResource resource = resources.get(resourceId);
    if (resource == null) {
      throw new IllegalStateException("canvas resource disappeared during planning");
    }
    return resource;
  }

  /**
   * 一次槽位编辑的结果；{@code resources} 已在本类内同步，调用方只需落回节点槽位。
   *
   * @param slots 编辑后的有序槽位行
   * @param mutations 要执行的持久化步骤
   * @param changed 是否真的产生变化，使「列表与基线完全一致」的批不推进 revision
   */
  record Plan(List<CanvasResource> slots, List<CanvasMutation> mutations, boolean changed) {

    Plan {
      slots = List.copyOf(slots);
      mutations = List.copyOf(mutations);
    }
  }
}
