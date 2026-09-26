package fun.fengwk.kkstudio.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 领域值对象与命令构造的校验契约。
 *
 * <p>这些不变量是持久化列的语义来源：document 的同步位置、引用连线的方向与下标、快照的不可变列表，以及命令携带集合的严格性都必须在 进入规划前被拒绝，而不是留给数据库或用例层。
 */
class CanvasValueObjectTest {

  private static final UUID CANVAS = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final UUID NODE = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

  /** revision 是同步位置：非负、标题非空白、更新时间不得早于创建时间。 */
  @Test
  void documentEnforcesTitleRevisionAndTimestamps() {
    CanvasDocument document =
        new CanvasDocument(CANVAS, "canvas", 7L, CREATED, CREATED.plusMillis(1));

    assertEquals(7L, document.revision());
    assertThrows(
        CanvasValidationException.class,
        () -> new CanvasDocument(CANVAS, "  ", 0L, CREATED, CREATED));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasDocument(CANVAS, "canvas", -1L, CREATED, CREATED));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasDocument(CANVAS, "canvas", 0L, CREATED, CREATED.minusMillis(1)));
    assertThrows(
        NullPointerException.class, () -> new CanvasDocument(CANVAS, "canvas", 0L, CREATED, null));
  }

  /** 连线方向必须明确且下标非负：自引用与负下标都是非法投影。 */
  @Test
  void referenceRejectsSelfReferenceAndNegativeIndex() {
    UUID source = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    UUID target = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    assertEquals(
        new CanvasReference(CANVAS, source, target, 0),
        new CanvasReference(CANVAS, source, target, 0));
    assertNotEquals(
        new CanvasReference(CANVAS, source, target, 0),
        new CanvasReference(CANVAS, source, target, 1));
    assertThrows(
        IllegalArgumentException.class, () -> new CanvasReference(CANVAS, source, source, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new CanvasReference(CANVAS, source, target, -1));
  }

  /** 资源引用身份是 (nodeId, index)，负下标与缺失字段同属非法配置。 */
  @Test
  void resourceReferenceRequiresIdentity() {
    UUID nodeId = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    assertEquals(new CanvasResourceReference(nodeId, 2), new CanvasResourceReference(nodeId, 2));
    assertThrows(IllegalArgumentException.class, () -> new CanvasResourceReference(nodeId, -1));
    assertThrows(NullPointerException.class, () -> new CanvasResourceReference(null, 0));
  }

  /** 快照是只读投影：列表被复制，空值在构造时即被拒绝。 */
  @Test
  void snapshotCopiesListsAndRejectsNulls() {
    CanvasDocument document = new CanvasDocument(CANVAS, "canvas", 0L, CREATED, CREATED);
    List<CanvasResourceNode> nodes = new ArrayList<>();
    CanvasSnapshot snapshot = new CanvasSnapshot(document, nodes, List.of(), List.of());

    nodes.add(
        new CanvasResourceNode(
            UUID.randomUUID(),
            CANVAS,
            "node",
            new CanvasTransform(0, 0, 1, 1),
            null,
            List.of(),
            new CanvasFunction("video.generate", CanvasJson.parseObject("{}")),
            null));

    assertTrue(snapshot.nodes().isEmpty());
    assertThrows(
        NullPointerException.class, () -> new CanvasSnapshot(document, null, List.of(), List.of()));
  }

  /** 命令携带的集合必须非空值、无重复，名称与长度上限在构造期即被拒绝。 */
  @Test
  void commandCollectionsAndNamesAreStrict() {
    UUID nodeId = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    UUID resourceId = UUID.fromString("00000000-0000-0000-0000-0000000000d2");

    assertThrows(
        CanvasValidationException.class,
        () -> new CanvasCommand.SetNodeResources(nodeId, null, List.of()));
    assertThrows(
        CanvasValidationException.class,
        () -> new CanvasCommand.SetNodeResources(nodeId, List.of(), null));
    assertThrows(
        CanvasValidationException.class,
        () ->
            new CanvasCommand.SetNodeResources(nodeId, List.of(resourceId, resourceId), List.of()));
    assertThrows(
        CanvasValidationException.class,
        () ->
            new CanvasCommand.SetNodeResources(
                nodeId, List.of(resourceId), Collections.singletonList(null)));
    assertThrows(
        NullPointerException.class, () -> new CanvasCommand.DeleteNode(null, List.of(), null));
    assertThrows(
        CanvasValidationException.class,
        () -> new CanvasCommand.RenameNode(nodeId, "node", "x".repeat(257)));
    assertThrows(
        CanvasValidationException.class,
        () -> new CanvasResourceInput.Text("x".repeat(513), "body"));
    assertThrows(NullPointerException.class, () -> new CanvasResourceInput.Blob("name", null));
  }

  /** 命令批结果只有两种形态：接受回执必带 patch，冲突清单必须非空且不可变。 */
  @Test
  void commandResultEnforcesItsTwoShapes() {
    CanvasPatch patch = CanvasPatch.receipt(3L);
    CanvasCommandResult accepted = new CanvasCommandResult.Accepted(patch);
    assertEquals(patch, ((CanvasCommandResult.Accepted) accepted).patch());
    assertThrows(NullPointerException.class, () -> new CanvasCommandResult.Accepted(null));

    CanvasConflict conflict = new CanvasConflict.TargetMissing(NODE, CanvasConflict.Target.NODE);
    List<CanvasConflict> conflicts = new ArrayList<>(List.of(conflict));
    CanvasCommandResult conflicted = new CanvasCommandResult.Conflicted(conflicts);
    conflicts.clear();
    assertEquals(List.of(conflict), ((CanvasCommandResult.Conflicted) conflicted).conflicts());
    assertThrows(
        IllegalArgumentException.class, () -> new CanvasCommandResult.Conflicted(List.of()));
  }

  /** 写入准入失败的两种原因都必须可被调用方区分，便于区分重试与幂等键复用。 */
  @Test
  void conflictExceptionCarriesItsReason() {
    CanvasConflictException missing =
        new CanvasConflictException(CanvasConflictException.Reason.CANVAS_NOT_FOUND);
    assertEquals(CanvasConflictException.Reason.CANVAS_NOT_FOUND, missing.reason());
    assertEquals("CANVAS_NOT_FOUND", missing.getMessage());

    CanvasConflictException reused =
        new CanvasConflictException(
            CanvasConflictException.Reason.IDEMPOTENCY_CONFLICT, "key reused");
    assertEquals(CanvasConflictException.Reason.IDEMPOTENCY_CONFLICT, reused.reason());
    assertEquals("key reused", reused.getMessage());
  }

  /** 幂等记账与节点行记录都拒绝不合法基线：acceptedRevision 是同步游标，必须非负。 */
  @Test
  void storeRecordsRejectInvalidBaselines() {
    UUID key = UUID.randomUUID();
    CanvasStore.CommandDedup dedup = new CanvasStore.CommandDedup(CANVAS, key, "sha256:0", 4L);
    assertEquals(4L, dedup.acceptedRevision());
    assertEquals(key, dedup.idempotencyKey());
    assertEquals("sha256:0", dedup.requestHash());
    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasStore.CommandDedup(CANVAS, key, "sha256:0", -1L));
    assertThrows(
        NullPointerException.class,
        () -> new CanvasStore.CommandDedup(CANVAS, null, "sha256:0", 0L));

    CanvasStore.NodeRecord node =
        new CanvasStore.NodeRecord(
            NODE,
            CANVAS,
            "node",
            new CanvasTransform(0, 0, 10, 10),
            null,
            new CanvasFunction("video.generate", CanvasJson.parseObject("{}")));
    assertEquals(NODE, node.id());
    assertEquals("node", node.name());
    assertEquals(CANVAS, node.canvasId());
    assertEquals(NODE, node.id());
    assertThrows(
        NullPointerException.class,
        () -> new CanvasStore.NodeRecord(NODE, CANVAS, null, null, null, null));
    assertThrows(IllegalArgumentException.class, () -> CanvasPatch.receipt(-1L));
  }

  /** 资源行必须恰好一种内容，且 owner 与槽位同时存在或同时缺失。 */
  @Test
  void resourceContentAndOwnerPairAreExclusive() {
    UUID resourceId = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    UUID nodeId = UUID.fromString("00000000-0000-0000-0000-0000000000e2");

    CanvasResource text =
        new CanvasResource(resourceId, CANVAS, nodeId, 0, null, "text", "body", CREATED);
    assertTrue(text.isText());

    assertEquals(null, text.withoutOwner().ownerNodeId());
    assertEquals(3, text.withSlot(nodeId, 3).resourceIndex());
    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasResource(resourceId, CANVAS, nodeId, null, null, "text", "body", CREATED));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasResource(resourceId, CANVAS, nodeId, -1, null, "text", "body", CREATED));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasResource(resourceId, CANVAS, null, null, null, "text", null, CREATED));
  }
}
