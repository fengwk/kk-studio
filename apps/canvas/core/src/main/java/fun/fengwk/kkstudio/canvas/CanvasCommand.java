package fun.fengwk.kkstudio.canvas;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Canvas 支持的原子命令批元素，每条命令只修改一个语义组。
 *
 * <p>前置条件是编辑起点中该语义组的旧值，而不是整图版本：名称用旧名称，资源数组用有序 Resource id 列表，Function 用完整 {@code {name,args}}（JSON
 * 语义比较），布局可选携带旧几何以便拒绝离线积压。 批内全部命令一起校验、一起提交或一起回滚。
 */
public sealed interface CanvasCommand
    permits CanvasCommand.CreateNode,
        CanvasCommand.RenameNode,
        CanvasCommand.SetNodeResources,
        CanvasCommand.SetNodeFunction,
        CanvasCommand.SetNodeGroup,
        CanvasCommand.DeleteNode,
        CanvasCommand.UpdateNodeTransform,
        CanvasCommand.CreateGroup,
        CanvasCommand.RenameGroup,
        CanvasCommand.UpdateGroupTransform,
        CanvasCommand.DeleteGroup {

  /** 创建节点：id 由客户端生成，资源数组可为空以便同批再用 {@link SetNodeFunction} 赋予函数。 */
  record CreateNode(
      UUID nodeId, String name, CanvasTransform transform, List<CanvasResourceInput> resources)
      implements CanvasCommand {

    public CreateNode {
      Objects.requireNonNull(nodeId, "nodeId");
      name = CanvasValidation.requireName(name, "node name");
      Objects.requireNonNull(transform, "transform");
      resources = CanvasValidation.requireList(resources, "resources");
    }
  }

  /** 改名：前置条件是编辑起点的旧名称。 */
  record RenameNode(UUID nodeId, String expectedName, String name) implements CanvasCommand {

    public RenameNode {
      Objects.requireNonNull(nodeId, "nodeId");
      CanvasValidation.requireName(expectedName, "expectedName");
      name = CanvasValidation.requireName(name, "node name");
    }
  }

  /** 替换节点资源数组：前置条件是编辑起点的有序 Resource id 列表。 */
  record SetNodeResources(
      UUID nodeId, List<UUID> expectedResourceIds, List<CanvasResourceInput> resources)
      implements CanvasCommand {

    public SetNodeResources {
      Objects.requireNonNull(nodeId, "nodeId");
      expectedResourceIds =
          CanvasValidation.requireList(expectedResourceIds, "expectedResourceIds");
      CanvasValidation.requireDistinct(expectedResourceIds, "expectedResourceIds");
      resources = CanvasValidation.requireList(resources, "resources");
    }
  }

  /** 设置或清除 Function：前置条件是编辑起点的完整 {@code {name,args}}。 */
  record SetNodeFunction(UUID nodeId, CanvasFunction expectedFunction, CanvasFunction function)
      implements CanvasCommand {

    public SetNodeFunction {
      Objects.requireNonNull(nodeId, "nodeId");
    }
  }

  /** 设置或清除节点分组：前置条件是编辑起点的分组 id。 */
  record SetNodeGroup(UUID nodeId, UUID expectedGroupId, UUID groupId) implements CanvasCommand {

    public SetNodeGroup {
      Objects.requireNonNull(nodeId, "nodeId");
    }
  }

  /** 删除节点：前置条件是编辑起点的资源数组与 Function；仍被引用的节点必须先在同一批中解除引用。 */
  record DeleteNode(UUID nodeId, List<UUID> expectedResourceIds, CanvasFunction expectedFunction)
      implements CanvasCommand {

    public DeleteNode {
      Objects.requireNonNull(nodeId, "nodeId");
      expectedResourceIds =
          CanvasValidation.requireList(expectedResourceIds, "expectedResourceIds");
      CanvasValidation.requireDistinct(expectedResourceIds, "expectedResourceIds");
    }
  }

  /**
   * 更新节点几何：在线操作按服务端接受顺序收敛。
   *
   * <p>{@code expectedTransform} 非空时表示重连积压的布局基线，与服务端当前值不一致会被拒绝，从而不重放过期位置；为空时表示在线操作。
   */
  record UpdateNodeTransform(
      UUID nodeId, CanvasTransform transform, CanvasTransform expectedTransform)
      implements CanvasCommand {

    public UpdateNodeTransform {
      Objects.requireNonNull(nodeId, "nodeId");
      Objects.requireNonNull(transform, "transform");
    }
  }

  /** 创建不嵌套的视觉分组；成员关系由各节点的 {@link SetNodeGroup} 命令建立。 */
  record CreateGroup(UUID groupId, String title, CanvasTransform transform)
      implements CanvasCommand {

    public CreateGroup {
      Objects.requireNonNull(groupId, "groupId");
      title = CanvasValidation.requireName(title, "group title");
      Objects.requireNonNull(transform, "transform");
    }
  }

  /** 重命名分组：前置条件是编辑起点的旧标题。 */
  record RenameGroup(UUID groupId, String expectedTitle, String title) implements CanvasCommand {

    public RenameGroup {
      Objects.requireNonNull(groupId, "groupId");
      CanvasValidation.requireName(expectedTitle, "expectedTitle");
      title = CanvasValidation.requireName(title, "group title");
    }
  }

  /** 更新分组几何，语义与 {@link UpdateNodeTransform} 的布局基线一致。 */
  record UpdateGroupTransform(
      UUID groupId, CanvasTransform transform, CanvasTransform expectedTransform)
      implements CanvasCommand {

    public UpdateGroupTransform {
      Objects.requireNonNull(groupId, "groupId");
      Objects.requireNonNull(transform, "transform");
    }
  }

  /** 删除分组：前置条件是编辑起点的成员节点集合；成员节点在同批中解除分组。 */
  record DeleteGroup(UUID groupId, List<UUID> expectedMemberNodeIds) implements CanvasCommand {

    public DeleteGroup {
      Objects.requireNonNull(groupId, "groupId");
      expectedMemberNodeIds =
          CanvasValidation.requireList(expectedMemberNodeIds, "expectedMemberNodeIds");
      CanvasValidation.requireDistinct(expectedMemberNodeIds, "expectedMemberNodeIds");
    }
  }
}
