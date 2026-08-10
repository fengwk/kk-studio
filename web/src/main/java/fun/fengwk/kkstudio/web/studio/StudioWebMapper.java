package fun.fengwk.kkstudio.web.studio;

import fun.fengwk.kkstudio.share.studio.CanvasCommandDTO;
import fun.fengwk.kkstudio.share.studio.CanvasDocumentDTO;
import fun.fengwk.kkstudio.share.studio.CanvasFunctionDTO;
import fun.fengwk.kkstudio.share.studio.CanvasFunctionModelDTO;
import fun.fengwk.kkstudio.share.studio.CanvasFunctionParameterDefinitionDTO;
import fun.fengwk.kkstudio.share.studio.CanvasFunctionReferencePolicyDTO;
import fun.fengwk.kkstudio.share.studio.CanvasFunctionRunDTO;
import fun.fengwk.kkstudio.share.studio.CanvasGroupDTO;
import fun.fengwk.kkstudio.share.studio.CanvasLinkDTO;
import fun.fengwk.kkstudio.share.studio.CanvasResourceDTO;
import fun.fengwk.kkstudio.share.studio.CanvasResourceNodeDTO;
import fun.fengwk.kkstudio.share.studio.CanvasSnapshotDTO;
import fun.fengwk.kkstudio.share.studio.CanvasTransformDTO;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.studio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.studio.canvas.CanvasLink;
import fun.fengwk.kkstudio.studio.canvas.CanvasResource;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.studio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.studio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionParameterDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Canvas domain 与 share DTO 之间的严格 HTTP 边界映射。 */
public final class StudioWebMapper {

  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]*");
  private static final Pattern NON_NEGATIVE_DECIMAL = Pattern.compile("0|[1-9][0-9]*");

  private StudioWebMapper() {}

  public static CanvasDocumentDTO toDto(CanvasDocument document) {
    CanvasDocumentDTO dto = new CanvasDocumentDTO();
    dto.setId(Long.toString(document.id()));
    dto.setTitle(document.title());
    dto.setGraphRevision(Long.toString(document.graphRevision()));
    dto.setCreatedAt(document.createdAt().toString());
    dto.setUpdatedAt(document.updatedAt().toString());
    return dto;
  }

  public static CanvasSnapshotDTO toDto(CanvasSnapshot snapshot) {
    CanvasSnapshotDTO dto = new CanvasSnapshotDTO();
    dto.setDocument(toDto(snapshot.document()));
    dto.setNodes(snapshot.nodes().stream().map(StudioWebMapper::toDto).toList());
    dto.setGroups(snapshot.groups().stream().map(StudioWebMapper::toDto).toList());
    dto.setLinks(snapshot.links().stream().map(StudioWebMapper::toDto).toList());
    return dto;
  }

  public static List<CanvasCommand> toCommands(List<CanvasCommandDTO> commands) {
    if (commands == null) {
      throw new IllegalArgumentException("commands is required");
    }
    List<CanvasCommand> mapped = new ArrayList<>(commands.size());
    for (CanvasCommandDTO command : commands) {
      if (command == null) {
        throw new IllegalArgumentException("commands must not contain null");
      }
      mapped.add(toCommand(command));
    }
    return List.copyOf(mapped);
  }

  public static long parsePositiveId(String value, String field) {
    return parseLong(value, field, POSITIVE_DECIMAL, true);
  }

  public static long parseNonNegativeLong(String value, String field) {
    return parseLong(value, field, NON_NEGATIVE_DECIMAL, false);
  }

  private static CanvasCommand toCommand(CanvasCommandDTO command) {
    return switch (command) {
      case CanvasCommandDTO.CreateTextNode value -> new CanvasCommand.CreateTextNode(
          value.name(), value.markdown(), toTransform(value.transform()));
      case CanvasCommandDTO.UpdateTextNode value -> new CanvasCommand.UpdateTextNode(
          parsePositiveId(value.nodeId(), "nodeId"), value.markdown());
      case CanvasCommandDTO.CreateResourceNode value -> new CanvasCommand.CreateResourceNode(
          value.name(),
          parseIds(value.resourceIds(), "resourceIds"),
          toTransform(value.transform()));
      case CanvasCommandDTO.CreateFunctionNode value -> new CanvasCommand.CreateFunctionNode(
          value.name(), value.modelKey(), value.configJson(), toTransform(value.transform()));
      case CanvasCommandDTO.UpdateFunction value -> new CanvasCommand.UpdateFunction(
          parsePositiveId(value.nodeId(), "nodeId"), value.modelKey(), value.configJson());
      case CanvasCommandDTO.RenameNode value -> new CanvasCommand.RenameNode(
          parsePositiveId(value.nodeId(), "nodeId"), value.name());
      case CanvasCommandDTO.UpdateNodeTransforms value -> new CanvasCommand.UpdateNodeTransforms(
          toTransformUpdates(value.updates()));
      case CanvasCommandDTO.DeleteNode value -> new CanvasCommand.DeleteNode(
          parsePositiveId(value.nodeId(), "nodeId"));
      case CanvasCommandDTO.CreateLink value -> new CanvasCommand.CreateLink(
          parsePositiveId(value.sourceNodeId(), "sourceNodeId"),
          parsePositiveId(value.targetNodeId(), "targetNodeId"));
      case CanvasCommandDTO.DeleteLink value -> new CanvasCommand.DeleteLink(
          parsePositiveId(value.sourceNodeId(), "sourceNodeId"),
          parsePositiveId(value.targetNodeId(), "targetNodeId"));
      case CanvasCommandDTO.CreateGroup value -> new CanvasCommand.CreateGroup(
          value.title(),
          toTransform(value.transform()),
          parseIds(value.memberNodeIds(), "memberNodeIds"));
      case CanvasCommandDTO.MoveGroup value -> new CanvasCommand.MoveGroup(
          parsePositiveId(value.groupId(), "groupId"), value.x(), value.y());
      case CanvasCommandDTO.Ungroup value -> new CanvasCommand.Ungroup(
          parsePositiveId(value.groupId(), "groupId"),
          parseIds(value.memberNodeIds(), "memberNodeIds"));
      case CanvasCommandDTO.DeleteGroup value -> new CanvasCommand.DeleteGroup(
          parsePositiveId(value.groupId(), "groupId"));
    };
  }

  private static CanvasResourceNodeDTO toDto(CanvasResourceNode node) {
    CanvasResourceNodeDTO dto = new CanvasResourceNodeDTO();
    dto.setId(Long.toString(node.id()));
    dto.setCanvasId(Long.toString(node.canvasId()));
    dto.setName(node.name());
    dto.setTransform(toDto(node.transform()));
    dto.setGroupId(node.groupId() == null ? null : Long.toString(node.groupId()));
    dto.setResources(node.resources().stream().map(StudioWebMapper::toDto).toList());
    dto.setFunction(node.function() == null ? null : toDto(node.function()));
    dto.setRun(node.run() == null ? null : toDto(node.run()));
    return dto;
  }

  public static CanvasResourceDTO toDto(CanvasResource resource) {
    CanvasResourceDTO dto = new CanvasResourceDTO();
    dto.setId(Long.toString(resource.id()));
    dto.setCanvasId(Long.toString(resource.canvasId()));
    dto.setKind(resource.kind().name());
    dto.setMediaType(resource.mediaType());
    dto.setName(resource.name());
    dto.setSize(Long.toString(resource.size()));
    dto.setTextContent(resource.textContent());
    dto.setMetadataJson(resource.metadataJson());
    dto.setCreatedAt(resource.createdAt().toString());
    return dto;
  }

  private static CanvasFunctionDTO toDto(CanvasFunction function) {
    CanvasFunctionDTO dto = new CanvasFunctionDTO();
    dto.setModelKey(function.modelKey());
    dto.setConfigJson(function.configJson());
    return dto;
  }

  public static CanvasFunctionRunDTO toDto(CanvasFunctionRun run) {
    CanvasFunctionRunDTO dto = new CanvasFunctionRunDTO();
    dto.setNodeId(Long.toString(run.nodeId()));
    dto.setRequestId(run.requestId());
    dto.setStatus(run.status().name());
    dto.setStage(run.stage());
    dto.setError(run.error());
    dto.setUpdatedAt(run.updatedAt().toString());
    return dto;
  }

  public static CanvasFunctionModelDTO toDto(
      CanvasFunctionModel model, boolean available, String unavailableReason) {
    CanvasFunctionModelDTO dto = new CanvasFunctionModelDTO();
    dto.setKey(model.key());
    dto.setLabel(model.label());
    dto.setOutputKind(model.outputKind().name());
    CanvasFunctionReferencePolicyDTO referencePolicy = new CanvasFunctionReferencePolicyDTO();
    referencePolicy.setAllowedKinds(
        model.referencePolicy().allowedKinds().stream().map(Enum::name).sorted().toList());
    referencePolicy.setMaxReferences(model.referencePolicy().maxReferences());
    LinkedHashMap<String, Integer> maxByKind = new LinkedHashMap<>();
    model.referencePolicy().maxByKind().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> maxByKind.put(entry.getKey().name(), entry.getValue()));
    referencePolicy.setMaxByKind(maxByKind);
    dto.setReferencePolicy(referencePolicy);
    dto.setParameters(model.parameters().stream().map(StudioWebMapper::toDto).toList());
    dto.setAvailable(available);
    dto.setUnavailableReason(unavailableReason);
    return dto;
  }

  private static CanvasFunctionParameterDefinitionDTO toDto(
      CanvasFunctionParameterDefinition definition) {
    CanvasFunctionParameterDefinitionDTO dto = new CanvasFunctionParameterDefinitionDTO();
    dto.setKey(definition.key());
    dto.setLabel(definition.label());
    dto.setType(definition.type().name());
    dto.setRequired(definition.required());
    dto.setDefaultValue(definition.defaultValue());
    dto.setOptions(definition.options());
    dto.setMin(definition.min());
    dto.setMax(definition.max());
    return dto;
  }

  private static CanvasGroupDTO toDto(CanvasGroup group) {
    CanvasGroupDTO dto = new CanvasGroupDTO();
    dto.setId(Long.toString(group.id()));
    dto.setCanvasId(Long.toString(group.canvasId()));
    dto.setTitle(group.title());
    dto.setTransform(toDto(group.transform()));
    return dto;
  }

  private static CanvasLinkDTO toDto(CanvasLink link) {
    CanvasLinkDTO dto = new CanvasLinkDTO();
    dto.setCanvasId(Long.toString(link.canvasId()));
    dto.setSourceNodeId(Long.toString(link.sourceNodeId()));
    dto.setTargetNodeId(Long.toString(link.targetNodeId()));
    return dto;
  }

  private static CanvasTransformDTO toDto(CanvasTransform transform) {
    return new CanvasTransformDTO(
        transform.x(), transform.y(), transform.width(), transform.height());
  }

  private static CanvasTransform toTransform(CanvasTransformDTO transform) {
    if (transform == null) {
      throw new IllegalArgumentException("transform is required");
    }
    return new CanvasTransform(transform.x(), transform.y(), transform.width(), transform.height());
  }

  private static List<Long> parseIds(List<String> ids, String field) {
    if (ids == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    return ids.stream().map(id -> parsePositiveId(id, field)).toList();
  }

  private static List<CanvasCommand.NodeTransformUpdate> toTransformUpdates(
      List<CanvasCommandDTO.NodeTransformUpdateDTO> updates) {
    if (updates == null) {
      throw new IllegalArgumentException("updates is required");
    }
    return updates.stream()
        .map(
            update -> {
              if (update == null) {
                throw new IllegalArgumentException("updates must not contain null");
              }
              return new CanvasCommand.NodeTransformUpdate(
                  parsePositiveId(update.nodeId(), "nodeId"), toTransform(update.transform()));
            })
        .toList();
  }

  private static long parseLong(
      String value, String field, Pattern pattern, boolean requirePositive) {
    if (value == null || !pattern.matcher(value).matches()) {
      throw new IllegalArgumentException(
          field
              + (requirePositive
                  ? " must be a positive decimal string"
                  : " must be a decimal string"));
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException(field + " is outside bigint range", ex);
    }
  }
}
