package fun.fengwk.kkstudio.platform.canvas.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.canvas.CanvasCommandResult;
import fun.fengwk.kkstudio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasResourceInput;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.storage.StorageMaintenance;

import java.util.List;
import java.util.UUID;

/**
 * Platform 装配的 Canvas → 全局 Storage Blob 引用释放契约。
 *
 * <p>生产上下文里的 {@code CanvasBlobReleaser} 适配真实 {@code StorageBlobManager}：Resource 行删除与 Blob
 * 引用减一必须落在同一个 Canvas 事务里，引用归零时 Blob 只切换为 DELETING（对象字节留给 Storage 维护）。Release 无法完成时必须抛出并使整个
 * Canvas 事务回滚，而不是留下已删除资源却仍被引用的 Blob。
 *
 * <p>后台维护被替换为 mock，使「释放只切换 DELETING」与「释放失败回滚」的断言不受并发 DELETING 行清扫影响；对象与行的真实回收不在本契约内。
 */
class CanvasBlobReleaseIntegrationTest extends PostgresSpringTestSupport {

  private static final CanvasTransform TRANSFORM = new CanvasTransform(10, 20, 100, 80);

  @Autowired private CanvasCommandService commandService;
  @Autowired private JdbcTemplate jdbc;
  @MockitoBean private StorageMaintenance storageBlobSweeper;

  /** 删除节点：节点 Resource 行与它持有的 Blob 引用同事务释放，引用归零后 Blob 转为 DELETING。 */
  @Test
  void deleteNodeReleasesBoundBlobReference() {
    UUID blobId = insertBlob("ACTIVE", 1L);
    CanvasDocument canvas = commandService.createCanvas("blob-release");
    UUID nodeId = UUID.randomUUID();
    apply(
        canvas,
        new CanvasCommand.CreateNode(
            nodeId, "node", TRANSFORM, List.of(new CanvasResourceInput.Blob("a.png", blobId))));
    UUID resourceId = ownedResourceId(canvas.id(), nodeId);

    apply(canvas, new CanvasCommand.DeleteNode(nodeId, List.of(resourceId), null));

    assertEquals(0L, count("canvas_resource", "canvas_id = ? and id = ?", canvas.id(), resourceId));
    assertEquals(0L, refCount(blobId), "Resource 行删除必须同事务释放 Blob 引用");
    assertEquals("DELETING", blobState(blobId));
  }

  /** 画布深删除同样回收 Blob 引用：document 行删除前每个 Resource 都已完成引用减一。 */
  @Test
  void deleteCanvasReleasesEveryBoundBlobReference() {
    UUID blobId = insertBlob("ACTIVE", 1L);
    CanvasDocument canvas = commandService.createCanvas("blob-release-canvas");
    UUID nodeId = UUID.randomUUID();
    apply(
        canvas,
        new CanvasCommand.CreateNode(
            nodeId, "node", TRANSFORM, List.of(new CanvasResourceInput.Blob("a.png", blobId))));

    commandService.deleteCanvas(canvas.id());

    assertEquals(0L, count("canvas_document", "id = ?", canvas.id()));
    assertEquals(0L, count("canvas_resource", "canvas_id = ?", canvas.id()));
    assertEquals(0L, refCount(blobId));
    assertEquals("DELETING", blobState(blobId));
  }

  /** Blob 已无法释放（非 ACTIVE）时整批失败：画布与资源行都必须回滚，绝不出现丢失 Blob 引用的资源。 */
  @Test
  void failedBlobReleaseRollsBackCanvasDeletion() {
    UUID blobId = insertBlob("DELETING", 0L);
    CanvasDocument canvas = commandService.createCanvas("blob-release-failure");
    UUID nodeId = UUID.randomUUID();
    apply(
        canvas,
        new CanvasCommand.CreateNode(
            nodeId, "node", TRANSFORM, List.of(new CanvasResourceInput.Blob("a.png", blobId))));
    UUID resourceId = ownedResourceId(canvas.id(), nodeId);

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> commandService.deleteCanvas(canvas.id()));

    assertEquals("release canvas resource blob failed: " + blobId, error.getMessage());
    assertEquals(1L, count("canvas_document", "id = ?", canvas.id()), "Blob 释放失败必须回滚画布删除");
    assertEquals(
        1L,
        count("canvas_resource", "canvas_id = ? and id = ?", canvas.id(), resourceId),
        "Blob 释放失败必须回滚资源行删除");
  }

  /** 创建和删除必须被接受；若返回冲突，类型断言立即失败。 */
  private void apply(CanvasDocument canvas, CanvasCommand... commands) {
    CanvasCommandResult result =
        commandService.applyCommands(canvas.id(), UUID.randomUUID(), List.of(commands));
    assertInstanceOf(CanvasCommandResult.Accepted.class, result);
  }

  private UUID ownedResourceId(UUID canvasId, UUID nodeId) {
    return jdbc.queryForObject(
        "select id from canvas_resource where canvas_id = ? and owner_node_id = ?",
        UUID.class,
        canvasId,
        nodeId);
  }

  private UUID insertBlob(String state, long refCount) {
    UUID blobId = UUID.randomUUID();
    jdbc.update(
        "insert into storage_blob (id, sha256, size_bytes, media_type, ref_count, state)"
            + " values (?, ?, ?, ?, ?, ?)",
        blobId,
        "0".repeat(64),
        4L,
        "image/png",
        refCount,
        state);
    return blobId;
  }

  private long refCount(UUID blobId) {
    Long value =
        jdbc.queryForObject("select ref_count from storage_blob where id = ?", Long.class, blobId);
    return value == null ? 0L : value;
  }

  private String blobState(UUID blobId) {
    return jdbc.queryForObject("select state from storage_blob where id = ?", String.class, blobId);
  }

  private long count(String table, String where, Object... args) {
    Long value =
        jdbc.queryForObject("select count(*) from " + table + " where " + where, Long.class, args);
    return value == null ? 0L : value;
  }
}
