package fun.fengwk.kkstudio.canvas.infra.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code canvas_resource} 在真实 PostgreSQL 上的不可变内容与 owner 槽位契约。 */
class PostgresqlCanvasResourceRepositoryIntegrationTest extends PostgresCanvasInfraTestSupport {

  @Autowired private CanvasResourceRepository resources;

  /** owner 槽位是唯一的：插入、读取、按节点排序、挂接与解除都围绕 {@code (canvas, owner, index)} 展开，重复槽位必须被数据库拒绝。 */
  @Test
  void ownerSlotsAreUniqueAndSorted() {
    UUID canvasId = addDocument();
    NodeFixture node = addTextNode(canvasId, "first");
    CanvasResource first = resources.findByOwnerNode(canvasId, node.nodeId).get(0);
    CanvasResource detached =
        new CanvasResource(
            UUID.randomUUID(), canvasId, null, null, null, "text", "history", Instant.now());
    resources.add(detached);

    assertTrue(resources.attachOwner(canvasId, detached.id(), node.nodeId, 1));
    assertEquals(
        List.of(first.id(), detached.id()),
        resources.findByOwnerNode(canvasId, node.nodeId).stream().map(CanvasResource::id).toList());
    assertThrows(
        DataAccessException.class,
        () ->
            resources.add(
                new CanvasResource(
                    UUID.randomUUID(),
                    canvasId,
                    node.nodeId,
                    1,
                    null,
                    "text",
                    "conflict",
                    Instant.now())));

    assertTrue(resources.detachOwner(canvasId, detached.id(), node.nodeId));
    assertFalse(resources.detachOwner(canvasId, detached.id(), node.nodeId));
    // owner 组合外键保证资源不可能挂到不存在的节点上，而不是静默失败。
    assertThrows(
        DataAccessException.class,
        () -> resources.attachOwner(canvasId, detached.id(), UUID.randomUUID(), 0));
  }

  /** 行内容不可变：同一 canvas 内按 id 读取，跨 canvas 读取必须为空；内容形状由列约束兜底。 */
  @Test
  void contentShapeAndCanvasScopedLookup() {
    UUID canvasId = addDocument();
    NodeFixture node = addTextNode(canvasId, "body");
    CanvasResource resource = resources.findByOwnerNode(canvasId, node.nodeId).get(0);

    assertEquals(resource, resources.findById(canvasId, resource.id()).orElseThrow());
    assertEquals(resource, resources.findByIdForUpdate(canvasId, resource.id()).orElseThrow());
    assertEquals(List.of(resource), resources.findByCanvasId(canvasId));
    assertTrue(resources.findById(UUID.randomUUID(), resource.id()).isEmpty());
    assertFalse(resources.addIfAbsent(resource));
    // 内容列约束由领域校验先兜住：blob 与文本必须恰好一个存在。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasResource(
                UUID.randomUUID(), canvasId, null, null, null, "text", null, Instant.now()));
  }

  /** 删除按资源、节点与画布三种粒度收敛，且不越出 canvas 作用域。 */
  @Test
  void deleteIsScopedToResourceNodeAndCanvas() {
    UUID canvasId = addDocument();
    NodeFixture node = addTextNode(canvasId, "body");
    CanvasResource resource = resources.findByOwnerNode(canvasId, node.nodeId).get(0);

    assertFalse(resources.delete(UUID.randomUUID(), resource.id()));
    assertTrue(resources.delete(canvasId, resource.id()));
    assertTrue(resources.findByCanvasId(canvasId).isEmpty());

    CanvasResource kept =
        new CanvasResource(
            UUID.randomUUID(), canvasId, null, null, null, "text", "kept", Instant.now());
    resources.add(kept);
    assertEquals(0, resources.deleteByOwnerNode(canvasId, node.nodeId));
    assertEquals(1, resources.deleteByCanvas(canvasId));
    assertTrue(resources.findByCanvasId(canvasId).isEmpty());
  }
}
