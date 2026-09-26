package fun.fengwk.kkstudio.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
 * 分组生命周期与未知目标的命令语义。
 *
 * <p>分组是节点的可选父行：它的标题与几何各自是独立语义组，成员关系由节点的 MEMBERSHIP 组表达。这些用例固定「分组命令如何更新父行、过期基线
 * 如何返回权威分组，以及指向已消失实体时如何逐条报告」，使客户端可以按冲突类型精确重放。
 */
class CanvasCommandPlannerGroupTest {

  private static final UUID CANVAS = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
  private static final CanvasTransform ORIGIN = new CanvasTransform(0, 0, 10, 10);

  private final List<UUID> allocated = new ArrayList<>();
  private final CanvasCommandPlanner planner = new CanvasCommandPlanner(CLOCK, this::allocate);

  /** 不存在的节点与分组必须报告为具体目标缺失；同一目标缺失只报告一次，避免重复事实淹没冲突清单。 */
  @Test
  void unknownTargetsAreReportedPerCommand() {
    UUID node = nodeId(1);
    UUID group = groupId(2);
    UUID unknown = groupId(99);
    CanvasGraph baseline = graph(List.of(textNode(node, "node", "body")), group(group, "group"));

    CanvasCommandPlan plan =
        planner.plan(
            baseline,
            List.of(
                new CanvasCommand.RenameNode(unknown, "stale", "next"),
                new CanvasCommand.SetNodeResources(unknown, List.of(), List.of()),
                new CanvasCommand.SetNodeFunction(unknown, null, null),
                new CanvasCommand.SetNodeGroup(unknown, null, null),
                new CanvasCommand.DeleteNode(unknown, List.of(), null),
                new CanvasCommand.UpdateNodeTransform(unknown, ORIGIN, null),
                new CanvasCommand.CreateGroup(group, "duplicate", ORIGIN),
                new CanvasCommand.RenameGroup(unknown, "stale", "next"),
                new CanvasCommand.UpdateGroupTransform(unknown, ORIGIN, null),
                new CanvasCommand.DeleteGroup(unknown, List.of()),
                new CanvasCommand.SetNodeGroup(node, null, unknown)));

    assertTrue(plan.isRejected());
    assertTrue(plan.mutations().isEmpty());
    assertEquals(
        List.of(
            new CanvasConflict.TargetMissing(unknown, CanvasConflict.Target.NODE),
            new CanvasConflict.TargetPresent(group, CanvasConflict.Target.GROUP),
            new CanvasConflict.TargetMissing(unknown, CanvasConflict.Target.GROUP)),
        plan.conflicts());
  }

  /** 标题与几何是分组的两个独立语义组：各自成功后写回同一父行，过期时返回权威分组且不写入。 */
  @Test
  void groupTitleAndTransformAreIndependentGroups() {
    UUID group = groupId(2);
    CanvasGraph baseline =
        graph(List.of(textNode(nodeId(1), "node", "body")), group(group, "group"));

    CanvasCommandPlan renamed =
        planner.plan(baseline, List.of(new CanvasCommand.RenameGroup(group, "group", "renamed")));
    assertFalse(renamed.isRejected());
    CanvasGroup renamedGroup =
        assertInstanceOf(CanvasGroupPatch.Upsert.class, renamed.groups().get(0)).group();
    assertEquals("renamed", renamedGroup.title());
    assertEquals(List.of(new CanvasMutation.UpdateGroup(renamedGroup)), renamed.mutations());

    CanvasCommandPlan moved =
        planner.plan(
            baseline,
            List.of(
                new CanvasCommand.UpdateGroupTransform(
                    group, new CanvasTransform(4, 4, 20, 20), ORIGIN)));
    assertFalse(moved.isRejected());
    assertEquals(
        new CanvasTransform(4, 4, 20, 20),
        assertInstanceOf(CanvasGroupPatch.Upsert.class, moved.groups().get(0)).group().transform());

    CanvasCommandPlan staleTitle =
        planner.plan(baseline, List.of(new CanvasCommand.RenameGroup(group, "stale", "renamed")));
    CanvasConflict.StaleGroup titleConflict =
        assertInstanceOf(CanvasConflict.StaleGroup.class, staleTitle.conflicts().get(0));
    assertEquals(group, titleConflict.groupId());
    assertEquals("group", titleConflict.current().title());
    assertTrue(staleTitle.mutations().isEmpty());

    CanvasCommandPlan staleTransform =
        planner.plan(
            baseline,
            List.of(
                new CanvasCommand.UpdateGroupTransform(
                    group, new CanvasTransform(4, 4, 20, 20), new CanvasTransform(1, 1, 1, 1))));
    assertInstanceOf(CanvasConflict.StaleGroup.class, staleTransform.conflicts().get(0));
    assertTrue(staleTransform.mutations().isEmpty());
  }

  /** 与基线一致的标题或几何是 no-op：既不写父行也不推进 revision。 */
  @Test
  void groupCommandsEqualToBaselineAreNoOps() {
    UUID group = groupId(2);
    CanvasGraph baseline =
        graph(List.of(textNode(nodeId(1), "node", "body")), group(group, "group"));

    CanvasCommandPlan plan =
        planner.plan(
            baseline,
            List.of(
                new CanvasCommand.RenameGroup(group, "group", "group"),
                new CanvasCommand.UpdateGroupTransform(group, ORIGIN, ORIGIN)));

    assertFalse(plan.isRejected());
    assertFalse(plan.hasChanges());
    assertTrue(plan.groups().isEmpty());
    assertTrue(plan.toPatch(3L).isEmpty());
    assertEquals(3L, plan.toPatch(3L).revision());
  }

  private UUID allocate() {
    return UUID.randomUUID();
  }

  private static UUID nodeId(int index) {
    return UUID.fromString("00000000-0000-0000-0000-0000000000%02d".formatted(index));
  }

  private static UUID groupId(int index) {
    return UUID.fromString("00000000-0000-0000-0000-0000000000f%01d".formatted(index % 10));
  }

  private static CanvasGroup group(UUID groupId, String title) {
    return new CanvasGroup(groupId, CANVAS, title, ORIGIN);
  }

  private static CanvasResourceNode textNode(UUID nodeId, String name, String text) {
    return new CanvasResourceNode(
        nodeId,
        CANVAS,
        name,
        ORIGIN,
        null,
        List.of(
            new CanvasResource(
                new UUID(nodeId.getMostSignificantBits(), nodeId.getLeastSignificantBits() + 1),
                CANVAS,
                nodeId,
                0,
                null,
                "text",
                text,
                Instant.EPOCH)),
        null,
        null);
  }

  private static CanvasGraph graph(List<CanvasResourceNode> nodes, CanvasGroup group) {
    return new CanvasGraph(CANVAS, nodes, List.of(group), Set.of());
  }
}
