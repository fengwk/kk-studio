package fun.fengwk.kkstudio.share.canvas;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/** Canvas typed command：创建类命令携带客户端生成的实体 UUID（nodeId/groupId）， 资源上传句柄由共享存储服务生成，命令只引用 uploadIds。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = CanvasCommandDTO.CreateTextNode.class, name = "CREATE_TEXT_NODE"),
  @JsonSubTypes.Type(value = CanvasCommandDTO.UpdateTextNode.class, name = "UPDATE_TEXT_NODE"),
  @JsonSubTypes.Type(
      value = CanvasCommandDTO.CreateResourceNode.class,
      name = "CREATE_RESOURCE_NODE"),
  @JsonSubTypes.Type(
      value = CanvasCommandDTO.CreateFunctionNode.class,
      name = "CREATE_FUNCTION_NODE"),
  @JsonSubTypes.Type(value = CanvasCommandDTO.UpdateFunction.class, name = "UPDATE_FUNCTION"),
  @JsonSubTypes.Type(value = CanvasCommandDTO.RenameNode.class, name = "RENAME_NODE"),
  @JsonSubTypes.Type(
      value = CanvasCommandDTO.UpdateNodeTransforms.class,
      name = "UPDATE_NODE_TRANSFORMS"),
  @JsonSubTypes.Type(value = CanvasCommandDTO.DeleteNode.class, name = "DELETE_NODE"),
  @JsonSubTypes.Type(value = CanvasCommandDTO.CreateLink.class, name = "CREATE_LINK"),
  @JsonSubTypes.Type(value = CanvasCommandDTO.DeleteLink.class, name = "DELETE_LINK"),
  @JsonSubTypes.Type(value = CanvasCommandDTO.CreateGroup.class, name = "CREATE_GROUP"),
  @JsonSubTypes.Type(value = CanvasCommandDTO.MoveGroup.class, name = "MOVE_GROUP"),
  @JsonSubTypes.Type(value = CanvasCommandDTO.Ungroup.class, name = "UNGROUP"),
  @JsonSubTypes.Type(value = CanvasCommandDTO.DeleteGroup.class, name = "DELETE_GROUP"),
  @JsonSubTypes.Type(value = CanvasCommandDTO.RenameGroup.class, name = "RENAME_GROUP")
})
public sealed interface CanvasCommandDTO
    permits CanvasCommandDTO.CreateTextNode,
        CanvasCommandDTO.UpdateTextNode,
        CanvasCommandDTO.CreateResourceNode,
        CanvasCommandDTO.CreateFunctionNode,
        CanvasCommandDTO.UpdateFunction,
        CanvasCommandDTO.RenameNode,
        CanvasCommandDTO.UpdateNodeTransforms,
        CanvasCommandDTO.DeleteNode,
        CanvasCommandDTO.CreateLink,
        CanvasCommandDTO.DeleteLink,
        CanvasCommandDTO.CreateGroup,
        CanvasCommandDTO.MoveGroup,
        CanvasCommandDTO.Ungroup,
        CanvasCommandDTO.DeleteGroup,
        CanvasCommandDTO.RenameGroup {

  @JsonAnySetter
  default void rejectUnknownField(String field, Object value) {
    throw new IllegalArgumentException("unknown field: " + field);
  }

  record CreateTextNode(String nodeId, String name, String markdown, CanvasTransformDTO transform)
      implements CanvasCommandDTO {}

  record UpdateTextNode(String nodeId, String markdown) implements CanvasCommandDTO {}

  record CreateResourceNode(
      String nodeId, String name, List<String> uploadIds, CanvasTransformDTO transform)
      implements CanvasCommandDTO {}

  record CreateFunctionNode(
      String nodeId, String name, String modelKey, String configJson, CanvasTransformDTO transform)
      implements CanvasCommandDTO {}

  record UpdateFunction(String nodeId, String modelKey, String configJson)
      implements CanvasCommandDTO {}

  record RenameNode(String nodeId, String name) implements CanvasCommandDTO {}

  record NodeTransformUpdateDTO(String nodeId, CanvasTransformDTO transform) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
      throw new IllegalArgumentException("unknown field: " + field);
    }
  }

  record UpdateNodeTransforms(List<NodeTransformUpdateDTO> updates) implements CanvasCommandDTO {}

  record DeleteNode(String nodeId) implements CanvasCommandDTO {}

  record CreateLink(String sourceNodeId, String targetNodeId) implements CanvasCommandDTO {}

  record DeleteLink(String sourceNodeId, String targetNodeId) implements CanvasCommandDTO {}

  record CreateGroup(
      String groupId, String title, CanvasTransformDTO transform, List<String> memberNodeIds)
      implements CanvasCommandDTO {}

  record MoveGroup(String groupId, double x, double y) implements CanvasCommandDTO {}

  record Ungroup(String groupId, List<String> memberNodeIds) implements CanvasCommandDTO {}

  record DeleteGroup(String groupId) implements CanvasCommandDTO {}

  record RenameGroup(String groupId, String title) implements CanvasCommandDTO {}
}
