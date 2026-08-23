package fun.fengwk.kkstudio.canvas.infra.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore.NodeRecord;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@link PostgresqlCanvasResourceRepository} 扩展 owner/text API 的真实 PostgreSQL 契约。 */
class PostgresqlCanvasResourceRepositoryIntegrationTest extends PostgresCanvasInfraTestSupport {

  private static final Instant CREATED_AT = Instant.parse("2026-01-02T03:04:05.123Z");

  @Autowired private CanvasResourceRepository resources;

  /**
   * 直接验证 add-if-absent、owner 双向查询、detach/attach 条件更新、文本更新与三种删除；每个 boolean/count 都必须来自实际 SQL 受影响行数。
   */
  @Test
  void resourceOwnerAndTextLifecycle() {
    UUID canvasId = addDocument();
    NodeRecord firstNode = addNode(canvasId, false);
    NodeRecord secondNode = addNode(canvasId, false);
    CanvasResource first = textResource(canvasId, firstNode.id(), 0, "first");
    CanvasResource second = textResource(canvasId, firstNode.id(), 1, "second");
    CanvasResource orphan = textResource(canvasId, null, null, "orphan");

    assertTrue(resources.findById(canvasId, first.id()).isEmpty());
    resources.add(first);
    assertTrue(resources.addIfAbsent(second));
    assertFalse(resources.addIfAbsent(second));
    resources.add(orphan);

    assertEquals(first, resources.findById(canvasId, first.id()).orElseThrow());
    assertEquals(
        first,
        transactions.execute(
            status -> resources.findByIdForUpdate(canvasId, first.id()).orElseThrow()));
    assertEquals(
        Boolean.TRUE,
        transactions.execute(
            status -> resources.findByIdForUpdate(canvasId, UUID.randomUUID()).isEmpty()));
    assertEquals(
        List.of(first.id(), second.id(), orphan.id()).stream().sorted().toList(),
        resources.findByCanvasId(canvasId).stream().map(CanvasResource::id).sorted().toList());
    assertEquals(List.of(first, second), resources.findByOwnerNode(canvasId, firstNode.id()));
    assertEquals(List.of(first, second), resources.findByOwnerNodeId(firstNode.id()));
    assertTrue(resources.findByOwnerNode(canvasId, secondNode.id()).isEmpty());

    assertFalse(resources.detachOwner(canvasId, first.id(), secondNode.id()));
    assertTrue(resources.detachOwner(canvasId, first.id(), firstNode.id()));
    assertFalse(resources.detachOwner(canvasId, first.id(), firstNode.id()));
    CanvasResource detached = resources.findById(canvasId, first.id()).orElseThrow();
    assertEquals(null, detached.ownerNodeId());
    assertEquals(null, detached.resourceIndex());

    assertTrue(resources.attachOwner(canvasId, first.id(), secondNode.id(), 0));
    assertFalse(resources.attachOwner(canvasId, first.id(), firstNode.id(), 0));
    assertTrue(resources.updateTextContent(canvasId, secondNode.id(), "updated text"));
    assertFalse(resources.updateTextContent(canvasId, UUID.randomUUID(), "missing"));
    assertEquals(
        "updated text", resources.findById(canvasId, first.id()).orElseThrow().textContent());

    assertTrue(resources.delete(canvasId, first.id()));
    assertFalse(resources.delete(canvasId, first.id()));
    assertEquals(1, resources.deleteByOwnerNode(canvasId, firstNode.id()));
    assertEquals(0, resources.deleteByOwnerNode(canvasId, firstNode.id()));

    CanvasResource ownedAgain = textResource(canvasId, secondNode.id(), 0, "owned-again");
    CanvasResource orphanAgain = textResource(canvasId, null, null, "orphan-again");
    resources.add(ownedAgain);
    resources.add(orphanAgain);
    assertEquals(3, resources.deleteByCanvas(canvasId));
    assertEquals(0, resources.deleteByCanvas(canvasId));
    assertTrue(resources.findByCanvasId(canvasId).isEmpty());
  }

  private static CanvasResource textResource(
      UUID canvasId, UUID ownerNodeId, Integer resourceIndex, String name) {
    return new CanvasResource(
        UUID.randomUUID(),
        canvasId,
        ownerNodeId,
        resourceIndex,
        null,
        name,
        name + " content",
        CREATED_AT);
  }
}
