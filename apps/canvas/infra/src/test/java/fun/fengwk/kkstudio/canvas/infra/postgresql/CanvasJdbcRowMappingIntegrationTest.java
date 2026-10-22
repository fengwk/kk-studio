package fun.fengwk.kkstudio.canvas.infra.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasFunction;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePin;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore.NodeRecord;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.canvas.function.CanvasJson;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 显式 RowMapper 的列映射契约：nullable UUID/Integer wrapper 读回 {@code null} 而非哨兵值、{@code timestamptz(3)}
 * 毫秒精度往返、jsonb 文本往返与多行 pin 参数绑定逐行独立。全部在真实 PostgreSQL 上断言。
 */
class CanvasJdbcRowMappingIntegrationTest extends PostgresCanvasInfraTestSupport {

  private static final Instant T0 = Instant.parse("2026-02-03T04:05:06.123Z");

  @Autowired private CanvasResourceRepository resources;
  @Autowired private CanvasFunctionRunRepository runs;
  @Autowired private CanvasFunctionResourcePinRepository pins;

  /** revision 是 Long、时间列是 {@code timestamptz(3)}：读回非空且精确到毫秒。 */
  @Test
  void documentRevisionAndTimestampsRoundTripAtMillisecondPrecision() {
    UUID canvasId = addDocument();

    CanvasDocument document = canvasStore.findDocument(canvasId).orElseThrow();
    assertEquals(0L, document.revision());
    assertEquals(0, document.createdAt().getNano() % 1_000_000);
    assertEquals(0, document.updatedAt().getNano() % 1_000_000);
    assertTrue(document.updatedAt().compareTo(document.createdAt()) >= 0);
  }

  /** 无 owner 的文本 Resource：owner_node_id/resource_index/blob_id 都是 SQL NULL，必须读回 null。 */
  @Test
  void resourceNullableUuidAndWrapperColumnsReadBackAsNull() {
    UUID canvasId = addDocument();
    UUID resourceId = UUID.randomUUID();
    resources.add(new CanvasResource(resourceId, canvasId, null, null, null, "text", "body", T0));

    CanvasResource stored = resources.findById(canvasId, resourceId).orElseThrow();
    assertNull(stored.ownerNodeId());
    assertNull(stored.resourceIndex());
    assertNull(stored.blobId());
    assertEquals("body", stored.textContent());
    assertEquals(T0, stored.createdAt());
  }

  /** blob 引用 Resource：blob_id 非空而 text_content 为 NULL，两个方向都必须原样往返。 */
  @Test
  void resourceBlobColumnRoundTripsWhileTextContentStaysNull() {
    UUID canvasId = addDocument();
    NodeFixture node = addTextNode(canvasId, "text");
    UUID blobId = UUID.randomUUID();
    jdbc.update(
        "insert into storage_blob (id, sha256, size_bytes, media_type, ref_count, state)"
            + " values (?, ?, ?, ?, ?, ?)",
        blobId,
        String.format("%064x", blobId.getLeastSignificantBits() & Long.MAX_VALUE),
        3L,
        "image/png",
        1L,
        "ACTIVE");
    UUID resourceId = UUID.randomUUID();
    resources.add(
        new CanvasResource(resourceId, canvasId, node.nodeId, 1, blobId, "image", null, T0));

    CanvasResource stored = resources.findById(canvasId, resourceId).orElseThrow();
    assertEquals(blobId, stored.blobId());
    assertEquals(node.nodeId, stored.ownerNodeId());
    assertEquals(1, stored.resourceIndex());
    assertNull(stored.textContent());
  }

  /** node 的 group_id 与 {@code function} jsonb 都可空：空/非空/清空三种形态都必须往返一致。 */
  @Test
  void nodeNullableGroupAndFunctionColumnsRoundTrip() {
    UUID canvasId = addDocument();
    NodeFixture node = addTextNode(canvasId, "body");
    UUID groupId = UUID.randomUUID();
    canvasStore.addGroup(
        new CanvasGroup(groupId, canvasId, "group", new CanvasTransform(0, 0, 10, 10)));

    NodeRecord plain = canvasStore.findNode(canvasId, node.nodeId).orElseThrow();
    assertNull(plain.groupId());
    assertNull(plain.function());

    NodeRecord withFunction =
        node.record(
            groupId,
            new CanvasFunction("video.generate", CanvasJson.parseObject("{\"a\":[1,true,null]}")));
    assertTrue(canvasStore.updateNode(withFunction));
    assertEquals(withFunction, canvasStore.findNode(canvasId, node.nodeId).orElseThrow());

    assertTrue(canvasStore.updateNode(node.record(null)));
    NodeRecord cleared = canvasStore.findNode(canvasId, node.nodeId).orElseThrow();
    assertNull(cleared.groupId());
    assertNull(cleared.function());
  }

  /** Run 的时间列与可空 error 列：READY 行读回 attempt、stage 而 error 为 null。 */
  @Test
  void functionRunNullableErrorColumnReadsBackAsNull() {
    UUID canvasId = addDocument();
    NodeRecord node = addNode(canvasId, true);
    UUID requestId = UUID.randomUUID();
    runs.insertReady(
        new CanvasFunctionRun(
            node.id(),
            requestId,
            CanvasFunctionRunStatus.READY,
            0,
            "QUEUED",
            minimalRunState("QUEUED"),
            null,
            T0,
            T0));

    CanvasFunctionRun stored = runs.findByNodeId(node.id()).orElseThrow();
    assertEquals(0, stored.attempt());
    assertEquals("QUEUED", stored.stage());
    assertNull(stored.error());
  }

  /** 生成的单条多行 pin INSERT 必须逐行独立绑定：每行 5 个值不能与相邻行串位。 */
  @Test
  void pinInsertAllBindsEveryRowWithItsOwnValues() {
    UUID canvasId = addDocument();
    NodeRecord node = addNode(canvasId, true);
    UUID requestId = UUID.randomUUID();
    runs.insertReady(
        new CanvasFunctionRun(
            node.id(),
            requestId,
            CanvasFunctionRunStatus.READY,
            0,
            "QUEUED",
            minimalRunState("QUEUED"),
            null,
            T0,
            T0));
    UUID input = addTextResource(canvasId, "input");
    UUID output = addTextResource(canvasId, "output");
    CanvasFunctionResourcePin inputPin =
        new CanvasFunctionResourcePin(
            canvasId, node.id(), requestId, input, CanvasFunctionResourcePin.Role.INPUT);
    CanvasFunctionResourcePin outputPin =
        new CanvasFunctionResourcePin(
            canvasId, node.id(), requestId, output, CanvasFunctionResourcePin.Role.OUTPUT);
    CanvasFunctionResourcePin sharedOutputPin =
        new CanvasFunctionResourcePin(
            canvasId, node.id(), requestId, input, CanvasFunctionResourcePin.Role.OUTPUT);

    pins.addAll(List.of(inputPin, outputPin, sharedOutputPin));

    assertEquals(
        Set.of(inputPin, outputPin, sharedOutputPin),
        new HashSet<>(pins.findByRun(canvasId, node.id(), requestId)));
  }

  private UUID addTextResource(UUID canvasId, String text) {
    UUID resourceId = UUID.randomUUID();
    resources.add(new CanvasResource(resourceId, canvasId, null, null, null, "text", text, T0));
    return resourceId;
  }
}
