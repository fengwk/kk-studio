package fun.fengwk.kkstudio.web.mapper;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.canvas.CanvasConflict;
import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.canvas.CanvasGroupPatch;
import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasNodePatch;
import fun.fengwk.kkstudio.canvas.CanvasPatch;
import fun.fengwk.kkstudio.canvas.CanvasReference;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceInput;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.share.canvas.CanvasCommandDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasConflictDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasDocumentDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasFunctionDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasFunctionDefinitionDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasFunctionOutputDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasFunctionReferencePolicyDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasFunctionRunDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasGroupDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasGroupPatchDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasNodePatchDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasPatchDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasPresignedUrlDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasReferenceDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasResourceDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasResourceInputDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasResourceNodeDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasSnapshotDTO;
import fun.fengwk.kkstudio.share.canvas.CanvasTransformDTO;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;

import java.math.BigDecimal;
import java.math.BigInteger;
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
 * 且媒体事实为空。blob 行缺失时抛出显式错误，绝不静默伪造媒体事实。
 *
 * <p>Function args 在 wire 是任意 JSON object，在领域是严格 {@link CanvasJson.JsonObject}：映射双向递归转换，并在入口触发 Core
 * 的深度与长度校验，保证 HTTP 输入不会绕过持久化形式的不变量。
 */
@Component
public class WebDtoMapper {

  private final StorageBlobManager blobManager;

  public WebDtoMapper(StorageBlobManager blobManager) {
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
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
    dto.setRevision(Long.toString(document.revision()));
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
    dto.setReferences(snapshot.references().stream().map(WebDtoMapper::toDto).toList());
    return dto;
  }

  public CanvasPatchDTO toDto(CanvasPatch patch) {
    Objects.requireNonNull(patch, "patch");
    CanvasPatchDTO dto = new CanvasPatchDTO();
    dto.setRevision(Long.toString(patch.revision()));
    dto.setGroups(patch.groups().stream().map(this::toGroupPatchDto).toList());
    dto.setNodes(patch.nodes().stream().map(this::toNodePatchDto).toList());
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
    dto.setFunction(toDto(node.function()));
    dto.setRun(toDto(node.run()));
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
    if (run == null) {
      return null;
    }
    CanvasFunctionRunDTO dto = new CanvasFunctionRunDTO();
    dto.setNodeId(run.nodeId().toString());
    dto.setRequestId(run.requestId().toString());
    dto.setStatus(run.status().name());
    dto.setStage(run.stage());
    dto.setError(run.error());
    dto.setUpdatedAt(run.updatedAt().toString());
    return dto;
  }

  /** 把命令批的具体冲突映射成 409 响应体的结构化载荷。 */
  public List<CanvasConflictDTO> toDto(List<CanvasConflict> conflicts) {
    Objects.requireNonNull(conflicts, "conflicts");
    return conflicts.stream().map(this::toDto).toList();
  }

  public CanvasFunctionDefinitionDTO toDto(
      CanvasFunctionDefinition definition, boolean available, String unavailableReason) {
    Objects.requireNonNull(definition, "definition");
    CanvasFunctionDefinitionDTO dto = new CanvasFunctionDefinitionDTO();
    dto.setName(definition.name());
    dto.setDescription(definition.description());
    dto.setArgsSchema(toArgs(definition.argsSchema()));
    dto.setOutputs(
        definition.outputs().stream()
            .map(
                output -> {
                  CanvasFunctionOutputDTO outputDto = new CanvasFunctionOutputDTO();
                  outputDto.setKind(output.kind().name());
                  outputDto.setName(output.name());
                  return outputDto;
                })
            .toList());
    CanvasFunctionReferencePolicyDTO referencePolicy = new CanvasFunctionReferencePolicyDTO();
    referencePolicy.setAllowedKinds(
        definition.referencePolicy().allowedKinds().stream().map(Enum::name).sorted().toList());
    referencePolicy.setMaxReferences(definition.referencePolicy().maxReferences());
    LinkedHashMap<String, Integer> maxByKind = new LinkedHashMap<>();
    definition.referencePolicy().maxByKind().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> maxByKind.put(entry.getKey().name(), entry.getValue()));
    referencePolicy.setMaxByKind(maxByKind);
    dto.setReferencePolicy(referencePolicy);
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
    if (commands == null || commands.isEmpty()) {
      throw new IllegalArgumentException("commands must contain at least one command");
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
      case CanvasCommandDTO.CreateNode value -> new CanvasCommand.CreateNode(
          parseUuid(value.nodeId(), "nodeId"),
          value.name(),
          toTransform(value.transform()),
          toResourceInputs(value.resources()));
      case CanvasCommandDTO.RenameNode value -> new CanvasCommand.RenameNode(
          parseUuid(value.nodeId(), "nodeId"), value.expectedName(), value.name());
      case CanvasCommandDTO.SetNodeResources value -> new CanvasCommand.SetNodeResources(
          parseUuid(value.nodeId(), "nodeId"),
          parseUuids(value.expectedResourceIds(), "expectedResourceIds"),
          toResourceInputs(value.resources()));
      case CanvasCommandDTO.SetNodeFunction value -> new CanvasCommand.SetNodeFunction(
          parseUuid(value.nodeId(), "nodeId"),
          toFunction(value.expectedFunction()),
          toFunction(value.function()));
      case CanvasCommandDTO.SetNodeGroup value -> new CanvasCommand.SetNodeGroup(
          parseUuid(value.nodeId(), "nodeId"),
          toNullableUuid(value.expectedGroupId(), "expectedGroupId"),
          toNullableUuid(value.groupId(), "groupId"));
      case CanvasCommandDTO.DeleteNode value -> new CanvasCommand.DeleteNode(
          parseUuid(value.nodeId(), "nodeId"),
          parseUuids(value.expectedResourceIds(), "expectedResourceIds"),
          toFunction(value.expectedFunction()));
      case CanvasCommandDTO.UpdateNodeTransform value -> new CanvasCommand.UpdateNodeTransform(
          parseUuid(value.nodeId(), "nodeId"),
          toTransform(value.transform()),
          toNullableTransform(value.expectedTransform()));
      case CanvasCommandDTO.CreateGroup value -> new CanvasCommand.CreateGroup(
          parseUuid(value.groupId(), "groupId"), value.title(), toTransform(value.transform()));
      case CanvasCommandDTO.RenameGroup value -> new CanvasCommand.RenameGroup(
          parseUuid(value.groupId(), "groupId"), value.expectedTitle(), value.title());
      case CanvasCommandDTO.UpdateGroupTransform value -> new CanvasCommand.UpdateGroupTransform(
          parseUuid(value.groupId(), "groupId"),
          toTransform(value.transform()),
          toNullableTransform(value.expectedTransform()));
      case CanvasCommandDTO.DeleteGroup value -> new CanvasCommand.DeleteGroup(
          parseUuid(value.groupId(), "groupId"),
          parseUuids(value.expectedMemberNodeIds(), "expectedMemberNodeIds"));
    };
  }

  private CanvasConflictDTO toDto(CanvasConflict conflict) {
    return switch (conflict) {
      case CanvasConflict.TargetMissing missing -> new CanvasConflictDTO.TargetMissing(
          missing.targetId().toString(), missing.target().name());
      case CanvasConflict.TargetPresent present -> new CanvasConflictDTO.TargetPresent(
          present.targetId().toString(), present.target().name());
      case CanvasConflict.StaleNode stale -> new CanvasConflictDTO.StaleNode(
          stale.nodeId().toString(), stale.group().name(), toDto(stale.current()));
      case CanvasConflict.StaleGroup stale -> new CanvasConflictDTO.StaleGroup(
          stale.groupId().toString(), toDto(stale.current()));
      case CanvasConflict.NodeRunning running -> new CanvasConflictDTO.NodeRunning(
          running.nodeId().toString(), toDto(running.run()));
      case CanvasConflict.NodeReferenced referenced -> new CanvasConflictDTO.NodeReferenced(
          referenced.nodeId().toString(),
          referenced.referencingNodeIds().stream().map(UUID::toString).toList());
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

  private CanvasFunctionDTO toDto(CanvasFunction function) {
    if (function == null) {
      return null;
    }
    return new CanvasFunctionDTO(function.name(), toArgs(function.args()));
  }

  private CanvasGroupDTO toDto(CanvasGroup group) {
    CanvasGroupDTO dto = new CanvasGroupDTO();
    dto.setId(group.id().toString());
    dto.setCanvasId(group.canvasId().toString());
    dto.setTitle(group.title());
    dto.setTransform(toDto(group.transform()));
    return dto;
  }

  private static CanvasReferenceDTO toDto(CanvasReference reference) {
    CanvasReferenceDTO dto = new CanvasReferenceDTO();
    dto.setCanvasId(reference.canvasId().toString());
    dto.setSourceNodeId(reference.sourceNodeId().toString());
    dto.setTargetNodeId(reference.targetNodeId().toString());
    dto.setIndex(reference.index());
    return dto;
  }

  private static CanvasTransformDTO toDto(CanvasTransform transform) {
    return new CanvasTransformDTO(
        transform.x(), transform.y(), transform.width(), transform.height());
  }

  private static CanvasResourceInput toResourceInput(CanvasResourceInputDTO input) {
    return switch (input) {
      case CanvasResourceInputDTO.Keep keep -> new CanvasResourceInput.Keep(
          parseUuid(keep.resourceId(), "resourceId"));
      case CanvasResourceInputDTO.Text text -> {
        if (text.textContent() == null) {
          throw new IllegalArgumentException("textContent must not be null");
        }
        yield new CanvasResourceInput.Text(text.name(), text.textContent());
      }
      case CanvasResourceInputDTO.Blob blob -> new CanvasResourceInput.Blob(
          blob.name(), parseUuid(blob.blobId(), "blobId"));
    };
  }

  private static List<CanvasResourceInput> toResourceInputs(List<CanvasResourceInputDTO> inputs) {
    if (inputs == null) {
      throw new IllegalArgumentException("resources is required");
    }
    List<CanvasResourceInput> mapped = new ArrayList<>(inputs.size());
    for (CanvasResourceInputDTO input : inputs) {
      if (input == null) {
        throw new IllegalArgumentException("resources must not contain null");
      }
      mapped.add(toResourceInput(input));
    }
    return List.copyOf(mapped);
  }

  private static CanvasFunction toFunction(CanvasFunctionDTO function) {
    if (function == null) {
      return null;
    }
    if (function.name() == null) {
      throw new IllegalArgumentException("function name is required");
    }
    return new CanvasFunction(function.name(), toJsonObject(function.args()));
  }

  private static CanvasTransform toTransform(CanvasTransformDTO transform) {
    if (transform == null) {
      throw new IllegalArgumentException("transform is required");
    }
    return new CanvasTransform(transform.x(), transform.y(), transform.width(), transform.height());
  }

  private static CanvasTransform toNullableTransform(CanvasTransformDTO transform) {
    return transform == null ? null : toTransform(transform);
  }

  private static UUID toNullableUuid(String value, String field) {
    return value == null ? null : parseUuid(value, field);
  }

  private static List<UUID> parseUuids(List<String> ids, String field) {
    if (ids == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    return ids.stream().map(id -> parseUuid(id, field)).toList();
  }

  /** wire 的任意 JSON object 转成 Core 的严格 args 值模型，并复用 Core 的深度与长度上限。 */
  private static CanvasJson.JsonObject toJsonObject(Map<String, Object> args) {
    if (args == null) {
      throw new IllegalArgumentException("function args is required");
    }
    if (!(toJson(args, "function args", 0) instanceof CanvasJson.JsonObject object)) {
      throw new IllegalArgumentException("function args must be a JSON object");
    }
    String text = object.write();
    if (text.length() > CanvasJson.MAX_LENGTH) {
      throw new IllegalArgumentException(
          "function args must not exceed " + CanvasJson.MAX_LENGTH + " JSON characters");
    }
    return object;
  }

  private static CanvasJson toJson(Object value, String field, int depth) {
    if (depth > CanvasJson.MAX_DEPTH) {
      throw new IllegalArgumentException(
          field + " must not nest deeper than " + CanvasJson.MAX_DEPTH);
    }
    if (value == null) {
      return new CanvasJson.JsonNull();
    }
    if (value instanceof String text) {
      return new CanvasJson.JsonText(text);
    }
    if (value instanceof Boolean bool) {
      return new CanvasJson.JsonBool(bool);
    }
    if (value instanceof Map<?, ?> map) {
      LinkedHashMap<String, CanvasJson> values = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw new IllegalArgumentException(field + " must use string object keys");
        }
        values.put(key, toJson(entry.getValue(), field, depth + 1));
      }
      return new CanvasJson.JsonObject(values);
    }
    if (value instanceof List<?> list) {
      List<CanvasJson> values = new ArrayList<>(list.size());
      for (Object item : list) {
        values.add(toJson(item, field, depth + 1));
      }
      return new CanvasJson.JsonArray(values);
    }
    if (value instanceof BigDecimal decimal) {
      return new CanvasJson.JsonNumber(decimal);
    }
    if (value instanceof BigInteger
        || value instanceof Long
        || value instanceof Integer
        || value instanceof Short
        || value instanceof Byte) {
      return new CanvasJson.JsonNumber(new BigDecimal(value.toString()));
    }
    if (value instanceof Double || value instanceof Float) {
      double number = ((Number) value).doubleValue();
      if (!Double.isFinite(number)) {
        throw new IllegalArgumentException(field + " numbers must be finite");
      }
      return new CanvasJson.JsonNumber(BigDecimal.valueOf(number));
    }
    throw new IllegalArgumentException(
        field + " must contain only JSON values, found " + value.getClass().getName());
  }

  /** Core 的严格 args 值模型转回 wire 的普通 JSON 树，数值保持十进制文本形态。 */
  private static Map<String, Object> toArgs(CanvasJson.JsonObject args) {
    LinkedHashMap<String, Object> values = new LinkedHashMap<>();
    args.values().forEach((key, value) -> values.put(key, toJsonValue(value, 0)));
    return values;
  }

  private static Object toJsonValue(CanvasJson value, int depth) {
    if (depth > CanvasJson.MAX_DEPTH) {
      throw new IllegalStateException(
          "function args must not nest deeper than " + CanvasJson.MAX_DEPTH);
    }
    return switch (value) {
      case CanvasJson.JsonObject object -> {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        object.values().forEach((key, item) -> values.put(key, toJsonValue(item, depth + 1)));
        yield values;
      }
      case CanvasJson.JsonArray array -> {
        List<Object> values = new ArrayList<>(array.values().size());
        for (CanvasJson item : array.values()) {
          values.add(toJsonValue(item, depth + 1));
        }
        yield values;
      }
      case CanvasJson.JsonText text -> text.value();
      case CanvasJson.JsonNumber number -> new BigDecimal(number.value().toPlainString());
      case CanvasJson.JsonBool bool -> bool.value();
      case CanvasJson.JsonNull ignored -> null;
    };
  }

  private StorageBlob blob(UUID blobId) {
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
