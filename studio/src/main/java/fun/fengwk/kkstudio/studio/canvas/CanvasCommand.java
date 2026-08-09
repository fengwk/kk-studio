package fun.fengwk.kkstudio.studio.canvas;

import java.util.List;
import java.util.Objects;

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
        CanvasCommand.DeleteGroup {

  record CreateTextNode(String name, String markdown, CanvasTransform transform)
      implements CanvasCommand {
    public CreateTextNode {
      CanvasValidation.requireNonBlank(name, "name");
      Objects.requireNonNull(markdown, "markdown");
      Objects.requireNonNull(transform, "transform");
    }
  }

  record UpdateTextNode(long nodeId, String markdown) implements CanvasCommand {
    public UpdateTextNode {
      CanvasValidation.requirePositive(nodeId, "nodeId");
      Objects.requireNonNull(markdown, "markdown");
    }
  }

  record CreateResourceNode(String name, List<Long> resourceIds, CanvasTransform transform)
      implements CanvasCommand {
    public CreateResourceNode {
      CanvasValidation.requireNonBlank(name, "name");
      Objects.requireNonNull(resourceIds, "resourceIds");
      resourceIds = List.copyOf(resourceIds);
      if (resourceIds.isEmpty()) {
        throw new IllegalArgumentException("resourceIds must not be empty");
      }
      resourceIds.forEach(id -> CanvasValidation.requirePositive(id, "resourceId"));
      Objects.requireNonNull(transform, "transform");
    }
  }

  record CreateFunctionNode(
      String name, String modelKey, String configJson, CanvasTransform transform)
      implements CanvasCommand {
    public CreateFunctionNode {
      CanvasValidation.requireNonBlank(name, "name");
      CanvasValidation.requireNonBlank(modelKey, "modelKey");
      CanvasValidation.requireNonBlank(configJson, "configJson");
      Objects.requireNonNull(transform, "transform");
    }
  }

  record UpdateFunction(long nodeId, String modelKey, String configJson) implements CanvasCommand {
    public UpdateFunction {
      CanvasValidation.requirePositive(nodeId, "nodeId");
      CanvasValidation.requireNonBlank(modelKey, "modelKey");
      CanvasValidation.requireNonBlank(configJson, "configJson");
    }
  }

  record RenameNode(long nodeId, String name) implements CanvasCommand {
    public RenameNode {
      CanvasValidation.requirePositive(nodeId, "nodeId");
      CanvasValidation.requireNonBlank(name, "name");
    }
  }

  record NodeTransformUpdate(long nodeId, CanvasTransform transform) {
    public NodeTransformUpdate {
      CanvasValidation.requirePositive(nodeId, "nodeId");
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

  record DeleteNode(long nodeId) implements CanvasCommand {
    public DeleteNode {
      CanvasValidation.requirePositive(nodeId, "nodeId");
    }
  }

  record CreateLink(long sourceNodeId, long targetNodeId) implements CanvasCommand {
    public CreateLink {
      validateLink(sourceNodeId, targetNodeId);
    }
  }

  record DeleteLink(long sourceNodeId, long targetNodeId) implements CanvasCommand {
    public DeleteLink {
      validateLink(sourceNodeId, targetNodeId);
    }
  }

  record CreateGroup(String title, CanvasTransform transform, List<Long> memberNodeIds)
      implements CanvasCommand {
    public CreateGroup {
      CanvasValidation.requireNonBlank(title, "title");
      Objects.requireNonNull(transform, "transform");
      Objects.requireNonNull(memberNodeIds, "memberNodeIds");
      memberNodeIds = List.copyOf(memberNodeIds);
      memberNodeIds.forEach(id -> CanvasValidation.requirePositive(id, "memberNodeId"));
    }
  }

  record MoveGroup(long groupId, double x, double y) implements CanvasCommand {
    public MoveGroup {
      CanvasValidation.requirePositive(groupId, "groupId");
      if (!Double.isFinite(x) || !Double.isFinite(y)) {
        throw new IllegalArgumentException("group x/y must be finite");
      }
    }
  }

  record Ungroup(long groupId, List<Long> memberNodeIds) implements CanvasCommand {
    public Ungroup {
      CanvasValidation.requirePositive(groupId, "groupId");
      Objects.requireNonNull(memberNodeIds, "memberNodeIds");
      memberNodeIds = List.copyOf(memberNodeIds);
      if (memberNodeIds.isEmpty()) {
        throw new IllegalArgumentException("memberNodeIds must not be empty");
      }
      memberNodeIds.forEach(id -> CanvasValidation.requirePositive(id, "memberNodeId"));
    }
  }

  record DeleteGroup(long groupId) implements CanvasCommand {
    public DeleteGroup {
      CanvasValidation.requirePositive(groupId, "groupId");
    }
  }

  private static void validateLink(long sourceNodeId, long targetNodeId) {
    CanvasValidation.requirePositive(sourceNodeId, "sourceNodeId");
    CanvasValidation.requirePositive(targetNodeId, "targetNodeId");
    if (sourceNodeId == targetNodeId) {
      throw new IllegalArgumentException("sourceNodeId must differ from targetNodeId");
    }
  }
}
