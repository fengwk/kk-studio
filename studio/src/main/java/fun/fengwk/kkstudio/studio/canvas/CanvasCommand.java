package fun.fengwk.kkstudio.studio.canvas;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Canvas v1 支持的原子 command batch 元素。 */
public sealed interface CanvasCommand
    permits CanvasCommand.CreateTextNode,
        CanvasCommand.UpdateTextNode,
        CanvasCommand.CreateResourceNode,
        CanvasCommand.CreateFunctionNode,
        CanvasCommand.UpdateFunction,
        CanvasCommand.RenameNode,
        CanvasCommand.UpdateNodeTransforms,
        CanvasCommand.DeleteNode,
        CanvasCommand.CreateLink,
        CanvasCommand.DeleteLink,
        CanvasCommand.CreateGroup,
        CanvasCommand.MoveGroup,
        CanvasCommand.Ungroup,
        CanvasCommand.DeleteGroup,
        CanvasCommand.RenameGroup {

  record CreateTextNode(UUID nodeId, String name, String markdown, CanvasTransform transform)
      implements CanvasCommand {
    public CreateTextNode {
      Objects.requireNonNull(nodeId, "nodeId");
      CanvasValidation.requireNonBlank(name, "name");
      Objects.requireNonNull(markdown, "markdown");
      Objects.requireNonNull(transform, "transform");
    }
  }

  record UpdateTextNode(UUID nodeId, String markdown) implements CanvasCommand {
    public UpdateTextNode {
      Objects.requireNonNull(nodeId, "nodeId");
      Objects.requireNonNull(markdown, "markdown");
    }
  }

  record CreateResourceNode(
      UUID nodeId, String name, List<UUID> uploadIds, CanvasTransform transform)
      implements CanvasCommand {
    public CreateResourceNode {
      Objects.requireNonNull(nodeId, "nodeId");
      CanvasValidation.requireNonBlank(name, "name");
      Objects.requireNonNull(uploadIds, "uploadIds");
      uploadIds = List.copyOf(uploadIds);
      if (uploadIds.isEmpty()) {
        throw new IllegalArgumentException("uploadIds must not be empty");
      }
      uploadIds.forEach(uploadId -> Objects.requireNonNull(uploadId, "uploadId"));
      Objects.requireNonNull(transform, "transform");
    }
  }

  record CreateFunctionNode(
      UUID nodeId, String name, String modelKey, String configJson, CanvasTransform transform)
      implements CanvasCommand {
    public CreateFunctionNode {
      Objects.requireNonNull(nodeId, "nodeId");
      CanvasValidation.requireNonBlank(name, "name");
      CanvasValidation.requireNonBlank(modelKey, "modelKey");
      CanvasValidation.requireNonBlank(configJson, "configJson");
      Objects.requireNonNull(transform, "transform");
    }
  }

  record UpdateFunction(UUID nodeId, String modelKey, String configJson) implements CanvasCommand {
    public UpdateFunction {
      Objects.requireNonNull(nodeId, "nodeId");
      CanvasValidation.requireNonBlank(modelKey, "modelKey");
      CanvasValidation.requireNonBlank(configJson, "configJson");
    }
  }

  record RenameNode(UUID nodeId, String name) implements CanvasCommand {
    public RenameNode {
      Objects.requireNonNull(nodeId, "nodeId");
      CanvasValidation.requireNonBlank(name, "name");
    }
  }

  record NodeTransformUpdate(UUID nodeId, CanvasTransform transform) {
    public NodeTransformUpdate {
      Objects.requireNonNull(nodeId, "nodeId");
      Objects.requireNonNull(transform, "transform");
    }
  }

  record UpdateNodeTransforms(List<NodeTransformUpdate> updates) implements CanvasCommand {
    public UpdateNodeTransforms {
      Objects.requireNonNull(updates, "updates");
      updates = List.copyOf(updates);
      if (updates.isEmpty()) {
        throw new IllegalArgumentException("updates must not be empty");
      }
    }
  }

  record DeleteNode(UUID nodeId) implements CanvasCommand {
    public DeleteNode {
      Objects.requireNonNull(nodeId, "nodeId");
    }
  }

  record CreateLink(UUID sourceNodeId, UUID targetNodeId) implements CanvasCommand {
    public CreateLink {
      validateLink(sourceNodeId, targetNodeId);
    }
  }

  record DeleteLink(UUID sourceNodeId, UUID targetNodeId) implements CanvasCommand {
    public DeleteLink {
      validateLink(sourceNodeId, targetNodeId);
    }
  }

  record CreateGroup(
      UUID groupId, String title, CanvasTransform transform, List<UUID> memberNodeIds)
      implements CanvasCommand {
    public CreateGroup {
      Objects.requireNonNull(groupId, "groupId");
      CanvasValidation.requireNonBlank(title, "title");
      Objects.requireNonNull(transform, "transform");
      Objects.requireNonNull(memberNodeIds, "memberNodeIds");
      memberNodeIds = List.copyOf(memberNodeIds);
      memberNodeIds.forEach(memberNodeId -> Objects.requireNonNull(memberNodeId, "memberNodeId"));
    }
  }

  record MoveGroup(UUID groupId, double x, double y) implements CanvasCommand {
    public MoveGroup {
      Objects.requireNonNull(groupId, "groupId");
      if (!Double.isFinite(x) || !Double.isFinite(y)) {
        throw new IllegalArgumentException("group x/y must be finite");
      }
    }
  }

  record Ungroup(UUID groupId, List<UUID> memberNodeIds) implements CanvasCommand {
    public Ungroup {
      Objects.requireNonNull(groupId, "groupId");
      Objects.requireNonNull(memberNodeIds, "memberNodeIds");
      memberNodeIds = List.copyOf(memberNodeIds);
      if (memberNodeIds.isEmpty()) {
        throw new IllegalArgumentException("memberNodeIds must not be empty");
      }
      memberNodeIds.forEach(memberNodeId -> Objects.requireNonNull(memberNodeId, "memberNodeId"));
    }
  }

  record DeleteGroup(UUID groupId) implements CanvasCommand {
    public DeleteGroup {
      Objects.requireNonNull(groupId, "groupId");
    }
  }

  record RenameGroup(UUID groupId, String title) implements CanvasCommand {
    public RenameGroup {
      Objects.requireNonNull(groupId, "groupId");
      CanvasValidation.requireNonBlank(title, "title");
    }
  }

  private static void validateLink(UUID sourceNodeId, UUID targetNodeId) {
    Objects.requireNonNull(sourceNodeId, "sourceNodeId");
    Objects.requireNonNull(targetNodeId, "targetNodeId");
    if (Objects.equals(sourceNodeId, targetNodeId)) {
      throw new IllegalArgumentException("sourceNodeId must differ from targetNodeId");
    }
  }
}
