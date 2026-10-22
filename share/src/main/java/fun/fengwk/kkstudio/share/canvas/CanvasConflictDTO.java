package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * 一次命令批中无法按前置条件执行的具体原因，随 409 响应返回。
 *
 * <p>冲突不是新的编辑基线：它只携带受影响对象与服务端权威值，由客户端决定合并、另存或放弃草稿，服务端不会用旧内容偷偷重试。冲突出现在错误响应的 {@code errors.conflicts}
 * 列表中；判别值 {@code kind} 来自类型信息，运行时类型自身携带该注解，因此即使列表位于无类型的错误上下文里也会写出。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
  @JsonSubTypes.Type(value = CanvasConflictDTO.TargetMissing.class, name = "TARGET_MISSING"),
  @JsonSubTypes.Type(value = CanvasConflictDTO.TargetPresent.class, name = "TARGET_PRESENT"),
  @JsonSubTypes.Type(value = CanvasConflictDTO.StaleNode.class, name = "STALE_NODE"),
  @JsonSubTypes.Type(value = CanvasConflictDTO.StaleGroup.class, name = "STALE_GROUP"),
  @JsonSubTypes.Type(value = CanvasConflictDTO.NodeRunning.class, name = "NODE_RUNNING"),
  @JsonSubTypes.Type(value = CanvasConflictDTO.NodeReferenced.class, name = "NODE_REFERENCED")
})
public sealed interface CanvasConflictDTO
    permits CanvasConflictDTO.TargetMissing,
        CanvasConflictDTO.TargetPresent,
        CanvasConflictDTO.StaleNode,
        CanvasConflictDTO.StaleGroup,
        CanvasConflictDTO.NodeRunning,
        CanvasConflictDTO.NodeReferenced {

  /** 命令指向的对象不存在；{@code target} 为 {@code NODE} 或 {@code GROUP}。 */
  record TargetMissing(String targetId, String target) implements CanvasConflictDTO {}

  /** 创建命令与既有对象 id 冲突；{@code target} 为 {@code NODE} 或 {@code GROUP}。 */
  record TargetPresent(String targetId, String target) implements CanvasConflictDTO {}

  /**
   * 节点语义组的前置条件已过期，携带当前权威节点投影。
   *
   * <p>{@code group} 为 {@code NAME}、{@code RESOURCES}、{@code FUNCTION}、{@code MEMBERSHIP} 或 {@code
   * LAYOUT}。
   */
  record StaleNode(String nodeId, String group, CanvasResourceNodeDTO current)
      implements CanvasConflictDTO {}

  /** 分组名称或几何的前置条件已过期，携带当前权威分组。 */
  record StaleGroup(String groupId, CanvasGroupDTO current) implements CanvasConflictDTO {}

  /** 节点正处于 READY/RUNNING 执行中：运行期间禁止手工换输出或删除节点。 */
  record NodeRunning(String nodeId, CanvasFunctionRunDTO run) implements CanvasConflictDTO {}

  /** 节点仍被其他节点的 Function args 引用，必须先在同一批中显式解除引用。 */
  record NodeReferenced(String nodeId, List<String> referencingNodeIds)
      implements CanvasConflictDTO {}
}
