package fun.fengwk.kkstudio.canvas;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次命令批中无法按前置条件执行的具体原因。
 *
 * <p>冲突不是新的编辑基线：它只携带受影响对象与服务端权威值，由客户端决定合并、另存或放弃草稿，服务端不会用旧内容偷偷重试。
 */
public sealed interface CanvasConflict
    permits CanvasConflict.TargetMissing,
        CanvasConflict.TargetPresent,
        CanvasConflict.StaleNode,
        CanvasConflict.StaleGroup,
        CanvasConflict.NodeRunning,
        CanvasConflict.NodeReferenced {

  /** 冲突对象类别。 */
  enum Target {
    NODE,
    GROUP
  }

  /** 节点语义组：不同组互不覆盖，冲突与 patch 都以组为单位。 */
  enum NodeGroup {
    /** 名称组。 */
    NAME,

    /** 有序资源数组组。 */
    RESOURCES,

    /** Function {@code {name,args}} 组。 */
    FUNCTION,

    /** 分组归属组。 */
    MEMBERSHIP,

    /** 几何布局组。 */
    LAYOUT
  }

  /** 命令指向的对象不存在。 */
  record TargetMissing(UUID targetId, Target target) implements CanvasConflict {
    public TargetMissing {
      Objects.requireNonNull(targetId, "targetId");
      Objects.requireNonNull(target, "target");
    }
  }

  /** 创建命令与既有对象 id 冲突。 */
  record TargetPresent(UUID targetId, Target target) implements CanvasConflict {
    public TargetPresent {
      Objects.requireNonNull(targetId, "targetId");
      Objects.requireNonNull(target, "target");
    }
  }

  /** 节点语义组的前置条件已过期，返回当前权威节点投影。 */
  record StaleNode(UUID nodeId, NodeGroup group, CanvasResourceNode current)
      implements CanvasConflict {

    public StaleNode {
      Objects.requireNonNull(nodeId, "nodeId");
      Objects.requireNonNull(group, "group");
      Objects.requireNonNull(current, "current");
    }
  }

  /** 分组名称或几何的前置条件已过期，返回当前权威分组。 */
  record StaleGroup(UUID groupId, CanvasGroup current) implements CanvasConflict {

    public StaleGroup {
      Objects.requireNonNull(groupId, "groupId");
      Objects.requireNonNull(current, "current");
    }
  }

  /** 节点正处于 READY/RUNNING 执行中：运行期间禁止手工换输出或删除节点。 */
  record NodeRunning(UUID nodeId, CanvasFunctionRun run) implements CanvasConflict {

    public NodeRunning {
      Objects.requireNonNull(nodeId, "nodeId");
      Objects.requireNonNull(run, "run");
    }
  }

  /** 节点仍被其他节点的 Function args 引用，必须先在同一批中显式解除引用。 */
  record NodeReferenced(UUID nodeId, List<UUID> referencingNodeIds) implements CanvasConflict {

    public NodeReferenced {
      Objects.requireNonNull(nodeId, "nodeId");
      referencingNodeIds = List.copyOf(referencingNodeIds);
    }
  }
}
