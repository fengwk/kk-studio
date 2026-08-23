package fun.fengwk.kkstudio.web.studio;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.canvas.CanvasGroupPatch;
import fun.fengwk.kkstudio.canvas.CanvasLink;
import fun.fengwk.kkstudio.canvas.CanvasLinkPatch;
import fun.fengwk.kkstudio.canvas.CanvasNodePatch;
import fun.fengwk.kkstudio.canvas.CanvasPatch;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionParameterDefinition;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.share.canvas.CanvasCommandDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasDocumentDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasFunctionDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasFunctionModelDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasFunctionParameterDefinitionDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasFunctionReferencePolicyDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasFunctionRunDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasGroupDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasGroupPatchDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasLinkDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasLinkPatchDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasNodePatchDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasPatchDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasPresignedUrlDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasResourceDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasResourceNodeDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasSnapshotDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasTransformDTO;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Canvas domain 与 share DTO 之间的严格 HTTP 边界映射。
 *
 * <p>媒体资源 DTO 的 kind/mediaType/宽高/时长来自 blob 行（blob 是媒体事实的权威源）；TEXT 资源没有 blob，kind 固定为 TEXT
 * 且媒体事实为空。blob 存储不可用（S3 未启用）或 blob 行缺失时抛出显式错误，绝不静默伪造媒体事实。
 */
@Component
public class StudioWebMapper {

  private final ObjectProvider<StorageBlobManager> blobManagers;

  public StudioWebMapper(ObjectProvider<StorageBlobManager> blobManagers) {
    this.blobManagers = Objects.requireNonNull(blobManagers, "blobManagers");
  }

  /** 解析 canonical UUID 实体 id（{@code UUID.fromString} 往返一致）。 */
  public static UUID parseUuid(String value, String field) {
    Objects.requireNonNull(field, "field");
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(field + " must be a canonical UUID: " + value, error);
    }
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical UUID: " + value);
    }
    return parsed;
  }

  public CanvasDocumentDTO toDto(CanvasDocument document) {
    Objects.requireNonNull(document, "document");
    CanvasDocumentDTO dto = new CanvasDocumentDTO();
    dto.setId(document.id().toString());
    dto.setTitle(document.title());
    dto.setVersion(Long.toString(document.version()));
    dto.setCreatedAt(document.createdAt().toString());
    dto.setUpdatedAt(document.updatedAt().toString());
    return dto;
  }

  public CanvasSnapshotDTO toDto(CanvasSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    CanvasSnapshotDTO dto = new CanvasSnapshotDTO();
    dto.setDocument(toDto(snapshot.document()));
    dto.setNodes(snapshot.nodes().stream().map(this::toDto).toList());
    dto.setGroups(snapshot.groups().stream().map(this::toDto).toList());
    dto.setLinks(snapshot.links().stream().map(this::toDto).toList());
    return dto;
  }

  public CanvasPatchDTO toDto(CanvasPatch patch) {
    Objects.requireNonNull(patch, "patch");
    CanvasPatchDTO dto = new CanvasPatchDTO();
    dto.setBaseVersion(Long.toString(patch.baseVersion()));
    dto.setVersion(Long.toString(patch.version()));
    dto.setGroups(patch.groups().stream().map(this::toGroupPatchDto).toList());
    dto.setNodes(patch.nodes().stream().map(this::toNodePatchDto).toList());
    dto.setLinks(patch.links().stream().map(this::toLinkPatchDto).toList());
    return dto;
  }

  public CanvasResourceNodeDTO toDto(CanvasResourceNode node) {
    Objects.requireNonNull(node, "node");
    CanvasResourceNodeDTO dto = new CanvasResourceNodeDTO();
    dto.setId(node.id().toString());
    dto.setCanvasId(node.canvasId().toString());
    dto.setName(node.name());
    dto.setTransform(toDto(node.transform()));
    dto.setGroupId(node.groupId() == null ? null : node.groupId().toString());
    dto.setResources(node.resources().stream().map(this::toDto).toList());
    dto.setFunction(node.function() == null ? null : toDto(node.function()));
    dto.setRun(node.run() == null ? null : toDto(node.run()));
    return dto;
  }

  public CanvasResourceDTO toDto(CanvasResource resource) {
    Objects.requireNonNull(resource, "resource");
    CanvasResourceDTO dto = new CanvasResourceDTO();
    dto.setId(resource.id().toString());
    dto.setCanvasId(resource.canvasId().toString());
    dto.setOwnerNodeId(resource.ownerNodeId().toString());
    dto.setResourceIndex(resource.resourceIndex());
    dto.setBlobId(resource.blobId() == null ? null : resource.blobId().toString());
    dto.setName(resource.name());
    dto.setTextContent(resource.textContent());
    dto.setCreatedAt(resource.createdAt().toString());
    if (resource.isText()) {
      dto.setKind(CanvasResourceKind.TEXT.name());
      return dto;
    }
    StorageBlob blob = blob(resource.blobId());
    dto.setKind(kindOf(blob.getMediaType()).name());
    dto.setMediaType(blob.getMediaType());
    dto.setSizeBytes(blob.getSizeBytes());
    dto.setWidth(blob.getWidth() == null ? null : blob.getWidth().intValue());
    dto.setHeight(blob.getHeight() == null ? null : blob.getHeight().intValue());
    dto.setDurationMs(blob.getDurationMs());
    return dto;
  }

  public CanvasFunctionRunDTO toDto(CanvasFunctionRun run) {
    Objects.requireNonNull(run, "run");
    CanvasFunctionRunDTO dto = new CanvasFunctionRunDTO();
    dto.setNodeId(run.nodeId().toString());
    dto.setRequestId(run.requestId().toString());
    dto.setStatus(run.status().name());
    dto.setStage(run.stage());
    dto.setError(run.error());
    dto.setUpdatedAt(run.updatedAt().toString());
    return dto;
  }

  public CanvasFunctionModelDTO toDto(
      CanvasFunctionModel model, boolean available, String unavailableReason) {
    Objects.requireNonNull(model, "model");
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
    dto.setParameters(model.parameters().stream().map(this::toDto).toList());
    dto.setAvailable(available);
    dto.setUnavailableReason(unavailableReason);
    return dto;
  }

  public static CanvasPresignedUrlDTO toPresignedUrlDto(StoragePresignedUrlDTO signed) {
    Objects.requireNonNull(signed, "signed");
    CanvasPresignedUrlDTO dto = new CanvasPresignedUrlDTO();
    dto.setMethod(signed.getMethod());
    dto.setUrl(signed.getUrl());
    dto.setHeaders(signed.getHeaders());
    dto.setExpiresAt(signed.getExpiresAt());
    return dto;
  }

  public List<CanvasCommand> toCommands(List<CanvasCommandDTO> commands) {
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

  private CanvasCommand toCommand(CanvasCommandDTO command) {
    return switch (command) {
      case CanvasCommandDTO.CreateTextNode value -> new CanvasCommand.CreateTextNode(
          parseUuid(value.nodeId(), "nodeId"),
          value.name(),
          value.markdown(),
          toTransform(value.transform()));
      case CanvasCommandDTO.UpdateTextNode value -> new CanvasCommand.UpdateTextNode(
          parseUuid(value.nodeId(), "nodeId"), value.markdown());
      case CanvasCommandDTO.CreateResourceNode value -> new CanvasCommand.CreateResourceNode(
          parseUuid(value.nodeId(), "nodeId"),
          value.name(),
          parseUuids(value.uploadIds(), "uploadIds"),
          toTransform(value.transform()));
      case CanvasCommandDTO.CreateFunctionNode value -> new CanvasCommand.CreateFunctionNode(
          parseUuid(value.nodeId(), "nodeId"),
          value.name(),
          value.modelKey(),
          value.configJson(),
          toTransform(value.transform()));
      case CanvasCommandDTO.UpdateFunction value -> new CanvasCommand.UpdateFunction(
          parseUuid(value.nodeId(), "nodeId"), value.modelKey(), value.configJson());
      case CanvasCommandDTO.RenameNode value -> new CanvasCommand.RenameNode(
          parseUuid(value.nodeId(), "nodeId"), value.name());
      case CanvasCommandDTO.UpdateNodeTransforms value -> new CanvasCommand.UpdateNodeTransforms(
          toTransformUpdates(value.updates()));
      case CanvasCommandDTO.DeleteNode value -> new CanvasCommand.DeleteNode(
          parseUuid(value.nodeId(), "nodeId"));
      case CanvasCommandDTO.CreateLink value -> new CanvasCommand.CreateLink(
          parseUuid(value.sourceNodeId(), "sourceNodeId"),
          parseUuid(value.targetNodeId(), "targetNodeId"));
      case CanvasCommandDTO.DeleteLink value -> new CanvasCommand.DeleteLink(
          parseUuid(value.sourceNodeId(), "sourceNodeId"),
          parseUuid(value.targetNodeId(), "targetNodeId"));
      case CanvasCommandDTO.CreateGroup value -> new CanvasCommand.CreateGroup(
          parseUuid(value.groupId(), "groupId"),
          value.title(),
          toTransform(value.transform()),
          parseUuids(value.memberNodeIds(), "memberNodeIds"));
      case CanvasCommandDTO.MoveGroup value -> new CanvasCommand.MoveGroup(
          parseUuid(value.groupId(), "groupId"), value.x(), value.y());
      case CanvasCommandDTO.Ungroup value -> new CanvasCommand.Ungroup(
          parseUuid(value.groupId(), "groupId"),
          parseUuids(value.memberNodeIds(), "memberNodeIds"));
      case CanvasCommandDTO.DeleteGroup value -> new CanvasCommand.DeleteGroup(
          parseUuid(value.groupId(), "groupId"));
      case CanvasCommandDTO.RenameGroup value -> new CanvasCommand.RenameGroup(
          parseUuid(value.groupId(), "groupId"), value.title());
    };
  }

  private CanvasGroupPatchDTO toGroupPatchDto(CanvasGroupPatch patch) {
    return switch (patch) {
      case CanvasGroupPatch.Upsert upsert -> new CanvasGroupPatchDTO.Upsert(toDto(upsert.group()));
      case CanvasGroupPatch.Remove remove -> new CanvasGroupPatchDTO.Remove(
          remove.groupId().toString());
    };
  }

  private CanvasNodePatchDTO toNodePatchDto(CanvasNodePatch patch) {
    return switch (patch) {
      case CanvasNodePatch.Upsert upsert -> new CanvasNodePatchDTO.Upsert(toDto(upsert.node()));
      case CanvasNodePatch.Remove remove -> new CanvasNodePatchDTO.Remove(
          remove.nodeId().toString());
    };
  }

  private CanvasLinkPatchDTO toLinkPatchDto(CanvasLinkPatch patch) {
    return switch (patch) {
      case CanvasLinkPatch.Upsert upsert -> new CanvasLinkPatchDTO.Upsert(toDto(upsert.link()));
      case CanvasLinkPatch.Remove remove -> new CanvasLinkPatchDTO.Remove(
          remove.sourceNodeId().toString(), remove.targetNodeId().toString());
    };
  }

  private CanvasFunctionDTO toDto(CanvasFunction function) {
    return new CanvasFunctionDTO(function.modelKey(), function.configJson());
  }

  private CanvasGroupDTO toDto(CanvasGroup group) {
    CanvasGroupDTO dto = new CanvasGroupDTO();
    dto.setId(group.id().toString());
    dto.setCanvasId(group.canvasId().toString());
    dto.setTitle(group.title());
    dto.setTransform(toDto(group.transform()));
    return dto;
  }

  private CanvasLinkDTO toDto(CanvasLink link) {
    CanvasLinkDTO dto = new CanvasLinkDTO();
    dto.setCanvasId(link.canvasId().toString());
    dto.setSourceNodeId(link.sourceNodeId().toString());
    dto.setTargetNodeId(link.targetNodeId().toString());
    return dto;
  }

  private static CanvasTransformDTO toDto(CanvasTransform transform) {
    return new CanvasTransformDTO(
        transform.x(), transform.y(), transform.width(), transform.height());
  }

  private CanvasFunctionParameterDefinitionDTO toDto(CanvasFunctionParameterDefinition definition) {
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

  private static CanvasTransform toTransform(CanvasTransformDTO transform) {
    if (transform == null) {
      throw new IllegalArgumentException("transform is required");
    }
    return new CanvasTransform(transform.x(), transform.y(), transform.width(), transform.height());
  }

  private static List<UUID> parseUuids(List<String> ids, String field) {
    if (ids == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    return ids.stream().map(id -> parseUuid(id, field)).toList();
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
                  parseUuid(update.nodeId(), "nodeId"), toTransform(update.transform()));
            })
        .toList();
  }

  private StorageBlob blob(UUID blobId) {
    StorageBlobManager blobManager = blobManagers.getIfAvailable();
    if (blobManager == null) {
      throw new IllegalStateException("global blob storage is unavailable");
    }
    StorageBlob blob = blobManager.getBlob(blobId);
    if (blob == null) {
      throw new IllegalStateException("resource blob is missing: " + blobId);
    }
    return blob;
  }

  private static CanvasResourceKind kindOf(String mediaType) {
    if (mediaType == null) {
      throw new IllegalArgumentException("blob mediaType must not be null");
    }
    if (mediaType.startsWith("image/")) {
      return CanvasResourceKind.IMAGE;
    }
    if (mediaType.startsWith("video/")) {
      return CanvasResourceKind.VIDEO;
    }
    if (mediaType.startsWith("audio/")) {
      return CanvasResourceKind.AUDIO;
    }
    if (mediaType.startsWith("text/")) {
      return CanvasResourceKind.TEXT;
    }
    throw new IllegalArgumentException("unsupported blob mediaType: " + mediaType);
  }
}
