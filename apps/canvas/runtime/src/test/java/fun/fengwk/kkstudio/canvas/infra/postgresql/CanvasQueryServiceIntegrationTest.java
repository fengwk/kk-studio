package fun.fengwk.kkstudio.canvas.infra.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.canvas.CanvasReference;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.canvas.function.CanvasJson;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@link CanvasQueryService} 的快照与引用投影在真实 PostgreSQL 上的契约。 */
class CanvasQueryServiceIntegrationTest extends PostgresCanvasInfraTestSupport {

  @Autowired private CanvasQueryService queryService;

  /** 连线是 args 的读取投影：引用只存一份，快照按消费节点 args 的保留形状还原 source/target/index，未知函数形状不产生连线。 */
  @Test
  void snapshotProjectsReferencesFromFunctionArgs() {
    UUID canvasId = addDocument();
    NodeFixture source = addTextNode(canvasId, "first");
    resourceRepository.add(source.textResource(1, "second"));
    UUID groupId = UUID.randomUUID();
    canvasStore.addGroup(
        new CanvasGroup(groupId, canvasId, "group", new CanvasTransform(0, 0, 10, 10)));
    NodeFixture consumer = addFunctionNode(canvasId, "video.generate");
    canvasStore.updateNode(
        consumer.record(
            new CanvasFunction(
                "video.generate",
                CanvasJson.parseObject(
                    """
                    {"prompt":{"segments":[
                      {"type":"TEXT","text":"prompt"},
                      {"type":"resource","nodeId":"%s","index":1}
                    ]}}
                    """
                        .formatted(source.nodeId)))));

    CanvasSnapshot snapshot = queryService.findSnapshot(canvasId).orElseThrow();

    assertEquals(canvasStore.findDocument(canvasId).orElseThrow(), snapshot.document());
    assertEquals(
        List.of(new CanvasReference(canvasId, source.nodeId, consumer.nodeId, 1)),
        snapshot.references());
    assertEquals(1, snapshot.groups().size());
    CanvasResourceNode sourceNode =
        snapshot.nodes().stream()
            .filter(node -> node.id().equals(source.nodeId))
            .findFirst()
            .orElseThrow();
    assertEquals(
        List.of("first", "second"),
        sourceNode.resources().stream().map(CanvasResource::textContent).toList());
    assertEquals(
        List.of(0, 1), sourceNode.resources().stream().map(CanvasResource::resourceIndex).toList());
    CanvasResourceNode consumerNode =
        snapshot.nodes().stream()
            .filter(node -> node.id().equals(consumer.nodeId))
            .findFirst()
            .orElseThrow();
    assertEquals("video.generate", consumerNode.function().name());
    assertTrue(consumerNode.resources().isEmpty());
    assertNull(consumerNode.run());
  }

  /** 只有挂接到节点的 Blob 才是 Canvas 持有资源，解挂、未知 Canvas 与未知 Blob 均不可访问。 */
  @Test
  void containsHeldBlobRequiresAttachedResource() {
    UUID canvasId = addDocument();
    NodeFixture node = addFunctionNode(canvasId, "image.crop");
    UUID blobId = UUID.randomUUID();
    jdbc.update(
        "insert into storage_blob (id, sha256, size_bytes, media_type, ref_count, state)"
            + " values (?, ?, 1, 'application/octet-stream', 1, 'ACTIVE')",
        blobId,
        "a".repeat(64));
    CanvasResource resource =
        new CanvasResource(
            UUID.randomUUID(), canvasId, node.nodeId, 0, blobId, "image.png", null, Instant.EPOCH);
    resourceRepository.add(resource);

    assertTrue(queryService.containsHeldBlob(canvasId, blobId));
    assertFalse(queryService.containsHeldBlob(canvasId, UUID.randomUUID()));
    assertFalse(queryService.containsHeldBlob(UUID.randomUUID(), blobId));
    assertTrue(resourceRepository.detachOwner(canvasId, resource.id(), node.nodeId));
    assertFalse(queryService.containsHeldBlob(canvasId, blobId));
  }

  /** 无引用的配置与未知画布保持空/缺失语义，不臆造连线或占位行。 */
  @Test
  void snapshotWithoutReferencesAndUnknownCanvas() {
    UUID canvasId = addDocument();
    addFunctionNode(canvasId, "image.crop");

    CanvasSnapshot snapshot = queryService.findSnapshot(canvasId).orElseThrow();
    assertTrue(snapshot.references().isEmpty());
    assertTrue(snapshot.groups().isEmpty());
    assertEquals(1, snapshot.nodes().size());

    assertTrue(queryService.findSnapshot(UUID.randomUUID()).isEmpty());
  }

  /** findResource 仅返回归属于有效画布且挂接在节点上的资源；缺失画布、缺失资源或已解挂资源均返回 empty。 */
  @Test
  void findResourceResolvesAttachedResourceAndRejectsMissingOrDetached() {
    UUID canvasId = addDocument();
    NodeFixture source = addTextNode(canvasId, "attached");
    CanvasResource resource = source.textResource(0, "attached");

    CanvasResource found = queryService.findResource(canvasId, resource.id()).orElseThrow();
    assertEquals(resource.id(), found.id());
    assertEquals(resource.canvasId(), found.canvasId());
    assertEquals(resource.ownerNodeId(), found.ownerNodeId());
    assertEquals(resource.textContent(), found.textContent());
    assertTrue(queryService.findResource(UUID.randomUUID(), resource.id()).isEmpty());
    assertTrue(queryService.findResource(canvasId, UUID.randomUUID()).isEmpty());

    assertTrue(resourceRepository.detachOwner(canvasId, resource.id(), source.nodeId));
    assertTrue(queryService.findResource(canvasId, resource.id()).isEmpty());
  }
}
