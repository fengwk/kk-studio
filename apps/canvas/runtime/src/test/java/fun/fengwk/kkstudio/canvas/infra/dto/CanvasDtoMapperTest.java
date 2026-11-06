package fun.fengwk.kkstudio.canvas.infra.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.canvas.CanvasConflict;
import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.canvas.CanvasGroupPatch;
import fun.fengwk.kkstudio.canvas.CanvasNodePatch;
import fun.fengwk.kkstudio.canvas.CanvasPatch;
import fun.fengwk.kkstudio.canvas.CanvasReference;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceInput;
import fun.fengwk.kkstudio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.canvas.dto.CanvasCommandDTO;
import fun.fengwk.kkstudio.canvas.dto.CanvasConflictDTO;
import fun.fengwk.kkstudio.canvas.dto.CanvasDocumentDTO;
import fun.fengwk.kkstudio.canvas.dto.CanvasFunctionDTO;
import fun.fengwk.kkstudio.canvas.dto.CanvasFunctionDefinitionDTO;
import fun.fengwk.kkstudio.canvas.dto.CanvasFunctionVersionDTO;
import fun.fengwk.kkstudio.canvas.dto.CanvasGroupPatchDTO;
import fun.fengwk.kkstudio.canvas.dto.CanvasNodePatchDTO;
import fun.fengwk.kkstudio.canvas.dto.CanvasPatchDTO;
import fun.fengwk.kkstudio.canvas.dto.CanvasResourceDTO;
import fun.fengwk.kkstudio.canvas.dto.CanvasResourceInputDTO;
import fun.fengwk.kkstudio.canvas.dto.CanvasResourceNodeDTO;
import fun.fengwk.kkstudio.canvas.dto.CanvasSnapshotDTO;
import fun.fengwk.kkstudio.canvas.dto.CanvasTransformDTO;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionOutputSpec;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasJson;
import fun.fengwk.kkstudio.canvas.function.CanvasResourceKind;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.storage.dto.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.platform.storage.dto.StorageUrlMethod;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 验证 CanvasDtoMapper 的权威媒体事实、命令入参、冲突投影与版本指纹语义。 */
class CanvasDtoMapperTest {

  private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");
  private static final CanvasTransform TRANSFORM = new CanvasTransform(1, 2, 3, 4);
  private static final CanvasTransformDTO TRANSFORM_DTO = new CanvasTransformDTO(1, 2, 3, 4);

  private final StorageBlobManager blobManager = mock(StorageBlobManager.class);
  private final CanvasDtoMapper mapper = new CanvasDtoMapper(blobManager);

  @Test
  void parseVersionKeepsCanonicalSemantics() {
    String fingerprint = "a".repeat(64);
    CanvasFunctionVersionDTO version = new CanvasFunctionVersionDTO();
    version.setDefinitionFingerprint(fingerprint);
    assertEquals(
        fingerprint,
        CanvasDtoMapper.parseVersion(version, "expectedVersion").definitionFingerprint());
    assertThrows(
        DomainException.class, () -> CanvasDtoMapper.parseVersion(null, "expectedVersion"));
    version.setDefinitionFingerprint("ABC");
    assertThrows(
        DomainException.class, () -> CanvasDtoMapper.parseVersion(version, "expectedVersion"));
  }

  @Test
  void mapsTextAndBlobResourcesFromAuthoritativeFacts() {
    UUID canvasId = UUID.randomUUID();
    UUID nodeId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    StorageBlob blob = blob("video/mp4", 12L, 30L, 40L, 90L);
    when(blobManager.getBlob(blobId)).thenReturn(blob);

    CanvasResource text =
        new CanvasResource(UUID.randomUUID(), canvasId, nodeId, 0, null, "note.txt", "hello", NOW);
    CanvasResourceDTO.Text textDto =
        assertInstanceOf(CanvasResourceDTO.Text.class, mapper.toDto(text));
    assertEquals(CanvasResourceKind.TEXT, textDto.kind());
    assertEquals("hello", textDto.textContent());

    CanvasResource video =
        new CanvasResource(UUID.randomUUID(), canvasId, nodeId, 0, blobId, "clip.mp4", null, NOW);
    CanvasResourceDTO.Blob videoDto =
        assertInstanceOf(CanvasResourceDTO.Blob.class, mapper.toDto(video));
    assertEquals(CanvasResourceKind.VIDEO, videoDto.kind());
    assertEquals("video/mp4", videoDto.mediaType());
    assertEquals(90L, videoDto.durationMs());
    assertEquals(30, videoDto.width());

    when(blobManager.getBlob(blobId)).thenReturn(blob("audio/mpeg", 1L, null, null, 20L));
    assertEquals(CanvasResourceKind.AUDIO, mapper.toDto(video).kind());
    when(blobManager.getBlob(blobId)).thenReturn(blob("image/png", 1L, null, null, null));
    assertEquals(CanvasResourceKind.IMAGE, mapper.toDto(video).kind());
    when(blobManager.getBlob(blobId)).thenReturn(blob("text/plain", 1L, null, null, null));
    assertEquals(CanvasResourceKind.TEXT, mapper.toDto(video).kind());
    when(blobManager.getBlob(blobId)).thenReturn(blob("application/pdf", 1L, null, null, null));
    assertThrows(IllegalArgumentException.class, () -> mapper.toDto(video));
    when(blobManager.getBlob(blobId)).thenReturn(blob(null, 1L, null, null, null));
    assertThrows(IllegalArgumentException.class, () -> mapper.toDto(video));
    when(blobManager.getBlob(blobId)).thenReturn(null);
    assertThrows(IllegalStateException.class, () -> mapper.toDto(video));
  }

  @Test
  void mapsSnapshotPatchAndConflicts() {
    UUID canvasId = UUID.randomUUID();
    UUID nodeId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    CanvasDocument document = new CanvasDocument(canvasId, "draft", 3L, NOW, NOW);
    CanvasResource resource =
        new CanvasResource(UUID.randomUUID(), canvasId, nodeId, 0, null, "note.txt", "hello", NOW);
    CanvasFunction function = function();
    CanvasFunctionRun run = run(nodeId, CanvasFunctionRunStatus.RUNNING, null);
    CanvasResourceNode node =
        new CanvasResourceNode(
            nodeId, canvasId, "shot", TRANSFORM, groupId, List.of(resource), function, run);
    CanvasGroup group = new CanvasGroup(groupId, canvasId, "group", TRANSFORM);
    CanvasReference reference = new CanvasReference(canvasId, sourceId, nodeId, 0);
    CanvasSnapshotDTO snapshot =
        mapper.toDto(
            new CanvasSnapshot(document, List.of(node), List.of(group), List.of(reference)));
    assertEquals("3", snapshot.getDocument().getRevision());
    assertEquals("image.crop", snapshot.getNodes().get(0).getFunction().name());
    assertEquals(CanvasFunctionRunStatus.RUNNING, snapshot.getNodes().get(0).getRun().getStatus());
    assertEquals(sourceId.toString(), snapshot.getReferences().get(0).getSourceNodeId());

    CanvasPatchDTO patch =
        mapper.toDto(
            new CanvasPatch(
                4L,
                List.of(new CanvasNodePatch.Upsert(node), new CanvasNodePatch.Remove(nodeId)),
                List.of(new CanvasGroupPatch.Upsert(group), new CanvasGroupPatch.Remove(groupId))));
    assertEquals("4", patch.getRevision());
    assertInstanceOf(CanvasNodePatchDTO.Upsert.class, patch.getNodes().get(0));
    assertInstanceOf(CanvasNodePatchDTO.Remove.class, patch.getNodes().get(1));
    assertInstanceOf(CanvasGroupPatchDTO.Upsert.class, patch.getGroups().get(0));
    assertInstanceOf(CanvasGroupPatchDTO.Remove.class, patch.getGroups().get(1));

    List<CanvasConflictDTO> conflicts =
        mapper.toDto(
            List.of(
                new CanvasConflict.TargetMissing(nodeId, CanvasConflict.Target.NODE),
                new CanvasConflict.TargetPresent(groupId, CanvasConflict.Target.GROUP),
                new CanvasConflict.StaleNode(nodeId, CanvasConflict.NodeGroup.NAME, node),
                new CanvasConflict.StaleGroup(groupId, group),
                new CanvasConflict.NodeRunning(nodeId, run),
                new CanvasConflict.NodeReferenced(nodeId, List.of(sourceId))));
    assertEquals(6, conflicts.size());
    assertInstanceOf(CanvasConflictDTO.TargetMissing.class, conflicts.get(0));
    assertInstanceOf(CanvasConflictDTO.NodeReferenced.class, conflicts.get(5));
    assertNull(mapper.toDto((CanvasFunctionRun) null));
  }

  @Test
  void mapsFunctionDefinitionAndPresignedUrl() {
    CanvasJson.JsonObject schema =
        (CanvasJson.JsonObject)
            CanvasJson.parse(
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}");
    CanvasFunctionDefinition definition =
        new CanvasFunctionDefinition(
            "image.crop",
            "crop",
            schema,
            List.of(
                new CanvasFunctionOutputSpec(CanvasResourceKind.IMAGE, "still"),
                new CanvasFunctionOutputSpec(CanvasResourceKind.VIDEO, null)),
            new CanvasFunctionReferencePolicy(
                Set.of(CanvasResourceKind.IMAGE, CanvasResourceKind.VIDEO),
                2,
                Map.of(CanvasResourceKind.VIDEO, 1)));
    CanvasFunctionDefinitionDTO dto = mapper.toDto(definition, false, "offline");
    assertEquals("image.crop", dto.getName());
    assertEquals(
        List.of(CanvasResourceKind.IMAGE, CanvasResourceKind.VIDEO),
        dto.getReferencePolicy().getAllowedKinds());
    assertEquals(1, dto.getReferencePolicy().getMaxByKind().get("VIDEO"));
    assertEquals(64, dto.getVersion().getDefinitionFingerprint().length());
    assertEquals("offline", dto.getUnavailableReason());

    StoragePresignedUrlDTO signed =
        StoragePresignedUrlDTO.builder()
            .method(StorageUrlMethod.GET)
            .url("https://example.test/blob")
            .headers(Map.of("host", "example.test"))
            .expiresAt(NOW)
            .build();
    var copied = CanvasDtoMapper.toPresignedUrlDto(signed);
    assertEquals(signed.getUrl(), copied.getUrl());
    assertEquals(signed.getHeaders(), copied.getHeaders());
    assertEquals(NOW, copied.getExpiresAt());
  }

  @Test
  void mapsEveryCommandShape() {
    UUID nodeId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    UUID resourceId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    CanvasFunctionDTO functionDto = new CanvasFunctionDTO("image.crop", Map.of("x", 1));
    List<CanvasCommand> commands =
        mapper.toCommands(
            List.of(
                new CanvasCommandDTO.CreateNode(
                    nodeId,
                    "shot",
                    TRANSFORM_DTO,
                    List.of(
                        new CanvasResourceInputDTO.Keep(resourceId),
                        new CanvasResourceInputDTO.Text("note.txt", "hello"),
                        new CanvasResourceInputDTO.Blob("shot.png", blobId))),
                new CanvasCommandDTO.RenameNode(nodeId, "old", "shot"),
                new CanvasCommandDTO.SetNodeResources(nodeId, List.of(resourceId), List.of()),
                new CanvasCommandDTO.SetNodeFunction(nodeId, null, functionDto),
                new CanvasCommandDTO.SetNodeGroup(nodeId, null, groupId),
                new CanvasCommandDTO.DeleteNode(nodeId, List.of(), functionDto),
                new CanvasCommandDTO.UpdateNodeTransform(nodeId, TRANSFORM_DTO, null),
                new CanvasCommandDTO.CreateGroup(groupId, "group", TRANSFORM_DTO),
                new CanvasCommandDTO.RenameGroup(groupId, "old", "group"),
                new CanvasCommandDTO.UpdateGroupTransform(groupId, TRANSFORM_DTO, TRANSFORM_DTO),
                new CanvasCommandDTO.DeleteGroup(groupId, List.of(nodeId))));
    assertEquals(11, commands.size());
    CanvasCommand.CreateNode created = (CanvasCommand.CreateNode) commands.get(0);
    assertInstanceOf(CanvasResourceInput.Keep.class, created.resources().get(0));
    assertInstanceOf(CanvasResourceInput.Text.class, created.resources().get(1));
    assertInstanceOf(CanvasResourceInput.Blob.class, created.resources().get(2));
    assertNull(((CanvasCommand.SetNodeFunction) commands.get(3)).expectedFunction());
    assertEquals(groupId, ((CanvasCommand.SetNodeGroup) commands.get(4)).groupId());
  }

  @Test
  void rejectsMalformedCommandsAndJsonValues() {
    assertThrows(DomainException.class, () -> mapper.toCommands(null));
    assertThrows(DomainException.class, () -> mapper.toCommands(List.of()));
    List<CanvasCommandDTO> withNull = new ArrayList<>();
    withNull.add(null);
    assertThrows(DomainException.class, () -> mapper.toCommands(withNull));

    UUID nodeId = UUID.randomUUID();
    List<CanvasResourceInputDTO> resources = new ArrayList<>();
    resources.add(null);
    assertThrows(
        DomainException.class,
        () ->
            mapper.toCommands(
                List.of(
                    new CanvasCommandDTO.CreateNode(nodeId, "shot", TRANSFORM_DTO, resources))));
    assertThrows(
        DomainException.class,
        () ->
            mapper.toCommands(
                List.of(
                    new CanvasCommandDTO.CreateNode(
                        nodeId,
                        "shot",
                        null,
                        List.of(new CanvasResourceInputDTO.Text("note.txt", null))))));
    assertThrows(
        DomainException.class,
        () ->
            mapper.toCommands(
                List.of(new CanvasCommandDTO.SetNodeFunction(nodeId, null, nullArgs()))));

    LinkedHashMap<String, Object> badNumber = new LinkedHashMap<>();
    badNumber.put("value", Double.NaN);
    assertThrows(DomainException.class, () -> commandWithArgs(badNumber));
    LinkedHashMap<String, Object> badKey = new LinkedHashMap<>();
    badKey.put("nested", Map.of(1, "x"));
    assertThrows(DomainException.class, () -> commandWithArgs(badKey));
    assertThrows(DomainException.class, () -> commandWithArgs(Map.of("value", new Object())));

    LinkedHashMap<String, Object> deep = new LinkedHashMap<>();
    Object current = deep;
    for (int i = 0; i < CanvasJson.MAX_DEPTH + 1; i++) {
      LinkedHashMap<String, Object> next = new LinkedHashMap<>();
      ((Map<String, Object>) current).put("child", next);
      current = next;
    }
    assertThrows(DomainException.class, () -> commandWithArgs(deep));
  }

  @Test
  void mapsDocumentAndPlainNodeWithoutFunction() {
    UUID canvasId = UUID.randomUUID();
    CanvasDocumentDTO document = mapper.toDto(new CanvasDocument(canvasId, "draft", 0L, NOW, NOW));
    assertEquals(canvasId.toString(), document.getId());
    UUID nodeId = UUID.randomUUID();
    CanvasResource resource =
        new CanvasResource(UUID.randomUUID(), canvasId, nodeId, 0, null, "note.txt", "hello", NOW);
    CanvasResourceNodeDTO node =
        mapper.toDto(
            new CanvasResourceNode(
                nodeId, canvasId, "note", TRANSFORM, null, List.of(resource), null, null));
    assertNull(node.getFunction());
    assertNull(node.getGroupId());
    assertNull(node.getRun());
  }

  private void commandWithArgs(Map<String, Object> args) {
    mapper.toCommands(
        List.of(
            new CanvasCommandDTO.SetNodeFunction(
                UUID.randomUUID(), null, new CanvasFunctionDTO("image.crop", args))));
  }

  private static CanvasFunctionDTO nullArgs() {
    return new CanvasFunctionDTO(null, null);
  }

  private static CanvasFunction function() {
    return new CanvasFunction(
        "image.crop",
        new CanvasJson.JsonObject(
            Map.of(
                "x",
                new CanvasJson.JsonNumber(BigDecimal.ONE),
                "flag",
                new CanvasJson.JsonBool(true),
                "label",
                new CanvasJson.JsonText("shot"),
                "empty",
                new CanvasJson.JsonNull(),
                "items",
                new CanvasJson.JsonArray(List.of(new CanvasJson.JsonText("a"))))));
  }

  private static CanvasFunctionRun run(UUID nodeId, CanvasFunctionRunStatus status, String error) {
    return new CanvasFunctionRun(
        nodeId, UUID.randomUUID(), status, 0, "render", "{}", error, NOW, NOW);
  }

  private static StorageBlob blob(
      String mediaType, long size, Long width, Long height, Long duration) {
    StorageBlob blob = new StorageBlob();
    blob.setMediaType(mediaType);
    blob.setSizeBytes(size);
    blob.setWidth(width);
    blob.setHeight(height);
    blob.setDurationMs(duration);
    return blob;
  }
}
