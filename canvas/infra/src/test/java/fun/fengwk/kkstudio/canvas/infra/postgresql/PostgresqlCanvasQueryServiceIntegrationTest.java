package fun.fengwk.kkstudio.canvas.infra.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.canvas.CanvasLink;
import fun.fengwk.kkstudio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.canvas.CanvasStore.NodeRecord;
import fun.fengwk.kkstudio.canvas.CanvasTransform;

import java.time.Instant;
import java.util.UUID;

/** {@link PostgresqlCanvasQueryService} 对 document/graph/resource/run 的完整 snapshot 投影测试。 */
class PostgresqlCanvasQueryServiceIntegrationTest extends PostgresCanvasInfraTestSupport {

  private static final Instant FIXED_TIME = Instant.parse("2026-03-04T05:06:07.123Z");

  @Autowired private CanvasQueryService query;
  @Autowired private CanvasResourceRepository resources;
  @Autowired private CanvasFunctionRunRepository runs;
  @Autowired private CanvasFunctionResourcePinRepository pins;

  /**
   * Snapshot 必须将普通资源节点、Function 节点、run、group 与 link 从真实表投影为 Core 读模型；不存在的 document 返回 empty，不能产生半个
   * snapshot。
   */
  @Test
  void snapshotProjectsCompleteCanvasGraph() {
    assertTrue(query.findSnapshot(UUID.randomUUID()).isEmpty());

    UUID canvasId = addDocument();
    CanvasGroup group =
        new CanvasGroup(
            UUID.randomUUID(), canvasId, "projected group", new CanvasTransform(1, 2, 600, 400));
    canvasStore.addGroup(group);
    NodeRecord source =
        new NodeRecord(
            UUID.randomUUID(),
            canvasId,
            "source",
            new CanvasTransform(10, 20, 300, 200),
            group.id(),
            null,
            null);
    NodeRecord function =
        new NodeRecord(
            UUID.randomUUID(),
            canvasId,
            "function",
            new CanvasTransform(400, 200, 300, 200),
            null,
            "projection-model",
            "{\"prompt\":{\"segments\":[{\"type\":\"TEXT\",\"text\":\"hello\"}]},\"parameters\":{}}");
    canvasStore.addNode(source);
    canvasStore.addNode(function);
    CanvasResource resource =
        new CanvasResource(
            UUID.randomUUID(), canvasId, source.id(), 0, null, "prompt.txt", "hello", FIXED_TIME);
    resources.add(resource);
    CanvasLink link = new CanvasLink(canvasId, source.id(), function.id());
    canvasStore.addLink(link);
    UUID requestId = UUID.randomUUID();
    runs.insertRunning(
        new CanvasFunctionRun(
            function.id(),
            requestId,
            CanvasFunctionRunStatus.RUNNING,
            "QUEUED",
            "{\"stage\":\"QUEUED\"}",
            null,
            FIXED_TIME));

    CanvasSnapshot snapshot = query.findSnapshot(canvasId).orElseThrow();
    assertEquals(canvasId, snapshot.document().id());
    assertEquals(1, query.listDocuments().size());
    assertEquals(group, snapshot.groups().getFirst());
    assertEquals(link, snapshot.links().getFirst());
    assertEquals(2, snapshot.nodes().size());

    CanvasResourceNode projectedSource = node(snapshot, source.id());
    assertEquals(group.id(), projectedSource.groupId());
    assertEquals(resource, projectedSource.resources().getFirst());
    assertEquals(null, projectedSource.function());
    assertEquals(null, projectedSource.run());

    CanvasResourceNode projectedFunction = node(snapshot, function.id());
    assertTrue(projectedFunction.resources().isEmpty());
    assertNotNull(projectedFunction.function());
    assertEquals("projection-model", projectedFunction.function().modelKey());
    assertEquals(requestId, projectedFunction.run().requestId());
    assertEquals("QUEUED", projectedFunction.run().stage());

    assertTrue(pins.findByNode(canvasId, function.id()).isEmpty());
  }

  private static CanvasResourceNode node(CanvasSnapshot snapshot, UUID nodeId) {
    return snapshot.nodes().stream()
        .filter(node -> node.id().equals(nodeId))
        .findFirst()
        .orElseThrow();
  }
}
