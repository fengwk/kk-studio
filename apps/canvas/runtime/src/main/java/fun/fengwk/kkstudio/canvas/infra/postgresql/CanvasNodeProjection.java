package fun.fengwk.kkstudio.canvas.infra.postgresql;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.canvas.NodeRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 将节点、资源与 Run 行投影为快照节点；命令前沿与查询快照共用同一规则。 */
final class CanvasNodeProjection {

  private CanvasNodeProjection() {}

  /** 资源按 owner 归属并按槽位排序，Run 按 nodeId 归属；无 owner 资源不进入快照。 */
  static List<CanvasResourceNode> project(
      List<NodeRecord> nodes, List<CanvasResource> resources, List<CanvasFunctionRun> runs) {
    Map<UUID, List<CanvasResource>> resourcesByNode = new HashMap<>();
    for (CanvasResource resource : resources) {
      if (resource.ownerNodeId() == null) {
        continue;
      }
      resourcesByNode
          .computeIfAbsent(resource.ownerNodeId(), ignored -> new ArrayList<>())
          .add(resource);
    }
    Map<UUID, CanvasFunctionRun> runsByNode = new HashMap<>();
    for (CanvasFunctionRun run : runs) {
      runsByNode.put(run.nodeId(), run);
    }
    List<CanvasResourceNode> projected = new ArrayList<>(nodes.size());
    for (NodeRecord node : nodes) {
      List<CanvasResource> owned =
          new ArrayList<>(resourcesByNode.getOrDefault(node.id(), List.of()));
      owned.sort(Comparator.comparingInt(CanvasResource::resourceIndex));
      projected.add(
          new CanvasResourceNode(
              node.id(),
              node.canvasId(),
              node.name(),
              node.transform(),
              node.groupId(),
              owned,
              node.function(),
              runsByNode.get(node.id())));
    }
    return List.copyOf(projected);
  }
}
