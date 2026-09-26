package fun.fengwk.kkstudio.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 节点资源数组的编辑语义。
 *
 * <p>资源数组是有序、同类内容的槽位列表：保留槽位按新顺序重挂、新内容插入新行、被移除的槽位删除行。这些用例固定数量上限、保留引用的合法性、 内容同类约束，以及「插入 / 删除 /
 * 重挂」三类持久化步骤的产生条件。
 */
class CanvasCommandPlannerResourceTest {

  private static final UUID CANVAS = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
  private static final CanvasTransform ORIGIN = new CanvasTransform(0, 0, 10, 10);

  private final List<UUID> allocated = new ArrayList<>();
  private final CanvasCommandPlanner planner = new CanvasCommandPlanner(CLOCK, this::allocate);

  /** 资源数量上限是请求结构约束：超限直接拒绝，而不是产生无法落地的持久化步骤。 */
  @Test
  void resourceCountIsCapped() {
    CanvasResourceNode node = textNode(textSlot(0, "body"));
    List<CanvasResourceInput> tooMany = new ArrayList<>();
    for (int index = 0; index <= CanvasCommandPlanner.MAX_RESOURCES_PER_NODE; index++) {
      tooMany.add(new CanvasResourceInput.Text("slot-" + index, "body"));
    }

    assertThrows(
        CanvasValidationException.class,
        () ->
            planner.plan(
                graph(List.of(node)),
                List.of(setResources(List.of(node.resources().get(0).id()), tooMany))));
  }

  /** 保留只能引用编辑起点中真实存在的槽位且不得重复，否则数组身份不明确。 */
  @Test
  void keepsMustReferenceDistinctBaselineSlots() {
    CanvasResourceNode node = textNode(textSlot(0, "body"));
    UUID baseline = node.resources().get(0).id();
    UUID unknown = UUID.randomUUID();

    assertThrows(
        CanvasValidationException.class,
        () ->
            planner.plan(
                graph(List.of(node)),
                List.of(
                    setResources(
                        List.of(baseline), List.of(new CanvasResourceInput.Keep(unknown))))));
    assertThrows(
        CanvasValidationException.class,
        () ->
            planner.plan(
                graph(List.of(node)),
                List.of(
                    setResources(
                        List.of(node.resources().get(0).id()),
                        List.of(
                            new CanvasResourceInput.Keep(node.resources().get(0).id()),
                            new CanvasResourceInput.Keep(node.resources().get(0).id()))))));
  }

  /** 同一节点的槽位必须同类：文本与媒体混排无法用单一列语义表达。 */
  @Test
  void slotsMustShareOneContentKind() {
    CanvasResourceNode node = textNode(textSlot(0, "body"));

    assertThrows(
        CanvasValidationException.class,
        () ->
            planner.plan(
                graph(List.of(node)),
                List.of(
                    setResources(
                        List.of(node.resources().get(0).id()),
                        List.of(
                            new CanvasResourceInput.Keep(node.resources().get(0).id()),
                            new CanvasResourceInput.Blob("out.png", UUID.randomUUID()))))));
  }

  /** 新槽位插入新行、被移除的槽位删除行，保留槽位重排时先脱离再按新顺序重挂。 */
  @Test
  void newSlotsAreInsertedAndRemovedSlotsAreDeleted() {
    CanvasResource dropped = textSlot(0, "body");
    CanvasResource kept = textSlot(1, "more");
    CanvasResourceNode node = textNode(dropped, kept);

    CanvasCommandPlan inserted =
        planner.plan(
            graph(List.of(node)),
            List.of(
                setResources(
                    List.of(dropped.id(), kept.id()),
                    List.of(
                        new CanvasResourceInput.Keep(kept.id()),
                        new CanvasResourceInput.Text("added", "text")))));

    assertFalse(inserted.isRejected());
    assertEquals(
        List.of(
            new CanvasMutation.DeleteResource(dropped.id()),
            new CanvasMutation.DetachResource(kept.id(), node.id()),
            new CanvasMutation.AttachResource(kept.id(), node.id(), 0)),
        inserted.mutations().subList(0, 3));
    CanvasResource added =
        assertInstanceOf(CanvasMutation.InsertResource.class, inserted.mutations().get(3))
            .resource();
    assertEquals("added", added.name());
    assertTrue(added.isText());
    assertEquals(1, added.resourceIndex());
    assertEquals(
        List.of(kept.id(), added.id()),
        assertInstanceOf(CanvasNodePatch.Upsert.class, inserted.nodes().get(0))
            .node()
            .resources()
            .stream()
            .map(CanvasResource::id)
            .toList());

    CanvasCommandPlan reordered =
        planner.plan(
            graph(List.of(node)),
            List.of(
                setResources(
                    List.of(dropped.id(), kept.id()),
                    List.of(
                        new CanvasResourceInput.Keep(kept.id()),
                        new CanvasResourceInput.Keep(dropped.id())))));
    assertEquals(
        List.of(
            new CanvasMutation.DetachResource(kept.id(), node.id()),
            new CanvasMutation.DetachResource(dropped.id(), node.id()),
            new CanvasMutation.AttachResource(kept.id(), node.id(), 0),
            new CanvasMutation.AttachResource(dropped.id(), node.id(), 1)),
        reordered.mutations());
  }

  /** 媒体槽位以 blob 身份插入，内容列与文本互斥。 */
  @Test
  void mediaSlotKeepsBlobIdentity() {
    CanvasResourceNode node =
        new CanvasResourceNode(
            nodeId(1),
            CANVAS,
            "node",
            ORIGIN,
            null,
            List.of(),
            new CanvasFunction("video.generate", CanvasJson.parseObject("{}")),
            null);
    UUID blobId = UUID.randomUUID();

    CanvasCommandPlan plan =
        planner.plan(
            graph(List.of(node)),
            List.of(
                setResources(List.of(), List.of(new CanvasResourceInput.Blob("out.mp4", blobId)))));

    CanvasResource inserted =
        assertInstanceOf(CanvasMutation.InsertResource.class, plan.mutations().get(0)).resource();
    assertEquals(blobId, inserted.blobId());
    assertFalse(inserted.isText());
    assertEquals("out.mp4", inserted.name());
    assertEquals(nodeId(1), inserted.ownerNodeId());
  }

  private UUID allocate() {
    UUID resourceId = UUID.randomUUID();
    allocated.add(resourceId);
    return resourceId;
  }

  private static CanvasCommand.SetNodeResources setResources(
      List<UUID> expected, List<CanvasResourceInput> inputs) {
    return new CanvasCommand.SetNodeResources(nodeId(1), expected, inputs);
  }

  private static UUID nodeId(int index) {
    return UUID.fromString("00000000-0000-0000-0000-0000000000%02d".formatted(index));
  }

  /** 槽位 id 由节点 id 与下标派生，使夹具资源可被同一语义组稳定引用。 */
  private static CanvasResource textSlot(int index, String text) {
    UUID nodeId = nodeId(1);
    return new CanvasResource(
        new UUID(nodeId.getMostSignificantBits(), nodeId.getLeastSignificantBits() + index + 1),
        CANVAS,
        nodeId,
        index,
        null,
        "text",
        text,
        Instant.EPOCH);
  }

  private static CanvasResourceNode textNode(CanvasResource... resources) {
    return new CanvasResourceNode(
        nodeId(1), CANVAS, "node", ORIGIN, null, List.of(resources), null, null);
  }

  private static CanvasGraph graph(List<CanvasResourceNode> nodes) {
    return new CanvasGraph(CANVAS, nodes, List.of(), Set.of());
  }
}
