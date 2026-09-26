package fun.fengwk.kkstudio.canvas.infra.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.canvas.CanvasReference;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.canvas.CanvasTransform;

import java.util.List;
import java.util.UUID;

/** {@link PostgresqlCanvasQueryService} 的快照与引用投影在真实 PostgreSQL 上的契约。 */
class PostgresqlCanvasQueryServiceIntegrationTest extends PostgresCanvasInfraTestSupport {

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

  /** 无引用的配置、未知画布与文档列表都必须保持空/缺失语义，不臆造连线或占位行。 */
  @Test
  void snapshotWithoutReferencesAndUnknownCanvas() {
    UUID canvasId = addDocument();
    addFunctionNode(canvasId, "image.crop");

    CanvasSnapshot snapshot = queryService.findSnapshot(canvasId).orElseThrow();
    assertTrue(snapshot.references().isEmpty());
    assertTrue(snapshot.groups().isEmpty());
    assertEquals(1, snapshot.nodes().size());

    CanvasDocument document = canvasStore.findDocument(canvasId).orElseThrow();
    assertEquals(List.of(document), queryService.listDocuments());
    assertTrue(queryService.findSnapshot(UUID.randomUUID()).isEmpty());
  }
}
