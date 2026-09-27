package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * Canvas typed command：与 canvas-core 的 {@code CanvasCommand} 一一对应，每条命令只修改一个语义组。
 *
 * <p>前置条件是编辑起点中该语义组的旧值，而不是整图版本：名称用 {@code expectedName}，资源数组用有序 Resource id 列表， Function 用完整 {@code
 * {name,args}}，布局可选携带 {@code expectedTransform} 以拒绝离线积压的位置重放。批内命令一起校验， 一起提交或一起回滚。命令只引用存储 blob 或既有
 * Resource，服务端负责生成新 Resource 的 id。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = CanvasCommandDTO.CreateNode.class, name = "CREATE_NODE"),
  @JsonSubTypes.Type(value = CanvasCommandDTO.RenameNode.class, name = "RENAME_NODE"),
  @JsonSubTypes.Type(value = CanvasCommandDTO.SetNodeResources.class, name = "SET_NODE_RESOURCES"),
  @JsonSubTypes.Type(value = CanvasCommandDTO.SetNodeFunction.class, name = "SET_NODE_FUNCTION"),
  @JsonSubTypes.Type(value = CanvasCommandDTO.SetNodeGroup.class, name = "SET_NODE_GROUP"),
  @JsonSubTypes.Type(value = CanvasCommandDTO.DeleteNode.class, name = "DELETE_NODE"),
  @JsonSubTypes.Type(
      value = CanvasCommandDTO.UpdateNodeTransform.class,
      name = "UPDATE_NODE_TRANSFORM"),
  @JsonSubTypes.Type(value = CanvasCommandDTO.CreateGroup.class, name = "CREATE_GROUP"),
  @JsonSubTypes.Type(value = CanvasCommandDTO.RenameGroup.class, name = "RENAME_GROUP"),
  @JsonSubTypes.Type(
      value = CanvasCommandDTO.UpdateGroupTransform.class,
      name = "UPDATE_GROUP_TRANSFORM"),
  @JsonSubTypes.Type(value = CanvasCommandDTO.DeleteGroup.class, name = "DELETE_GROUP")
})
public sealed interface CanvasCommandDTO
    permits CanvasCommandDTO.CreateNode,
        CanvasCommandDTO.RenameNode,
        CanvasCommandDTO.SetNodeResources,
        CanvasCommandDTO.SetNodeFunction,
        CanvasCommandDTO.SetNodeGroup,
        CanvasCommandDTO.DeleteNode,
        CanvasCommandDTO.UpdateNodeTransform,
        CanvasCommandDTO.CreateGroup,
        CanvasCommandDTO.RenameGroup,
        CanvasCommandDTO.UpdateGroupTransform,
        CanvasCommandDTO.DeleteGroup {

  @JsonAnySetter
  default void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }

  /** 创建节点：nodeId 由客户端生成，资源数组可为空以便同批再赋予 Function。 */
  record CreateNode(
      String nodeId,
      String name,
      CanvasTransformDTO transform,
      List<CanvasResourceInputDTO> resources)
      implements CanvasCommandDTO {}

  /** 改名：前置条件是编辑起点的旧名称。 */
  record RenameNode(String nodeId, String expectedName, String name) implements CanvasCommandDTO {}

  /** 替换资源数组：前置条件是编辑起点的有序 Resource id 列表。 */
  record SetNodeResources(
      String nodeId, List<String> expectedResourceIds, List<CanvasResourceInputDTO> resources)
      implements CanvasCommandDTO {}

  /** 设置或清除 Function：前置条件是编辑起点的完整 {@code {name,args}}，null 表示编辑起点没有 Function。 */
  record SetNodeFunction(
      String nodeId, CanvasFunctionDTO expectedFunction, CanvasFunctionDTO function)
      implements CanvasCommandDTO {}

  /** 设置或清除节点分组：前置条件是编辑起点的分组 id，null 表示编辑起点未分组。 */
  record SetNodeGroup(String nodeId, String expectedGroupId, String groupId)
      implements CanvasCommandDTO {}

  /** 删除节点：前置条件是编辑起点的资源数组与 Function；仍被引用的节点必须先在同一批解除引用。 */
  record DeleteNode(
      String nodeId, List<String> expectedResourceIds, CanvasFunctionDTO expectedFunction)
      implements CanvasCommandDTO {}

  /** 更新节点几何：expectedTransform 非空表示重连积压的布局基线，为空表示在线操作。 */
  record UpdateNodeTransform(
      String nodeId, CanvasTransformDTO transform, CanvasTransformDTO expectedTransform)
      implements CanvasCommandDTO {}

  /** 创建不嵌套的视觉分组；成员关系由各节点的 {@link SetNodeGroup} 建立。 */
  record CreateGroup(String groupId, String title, CanvasTransformDTO transform)
      implements CanvasCommandDTO {}

  /** 重命名分组：前置条件是编辑起点的旧标题。 */
  record RenameGroup(String groupId, String expectedTitle, String title)
      implements CanvasCommandDTO {}

  /** 更新分组几何：expectedTransform 语义与 {@link UpdateNodeTransform} 一致。 */
  record UpdateGroupTransform(
      String groupId, CanvasTransformDTO transform, CanvasTransformDTO expectedTransform)
      implements CanvasCommandDTO {}

  /** 删除分组：前置条件是编辑起点的成员节点集合；成员节点在同批解除分组。 */
  record DeleteGroup(String groupId, List<String> expectedMemberNodeIds)
      implements CanvasCommandDTO {}
}
