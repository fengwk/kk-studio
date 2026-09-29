package fun.fengwk.kkstudio.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * 细粒度命令语义与并发前置条件的纯领域契约。
 *
 * <p>规划不触碰数据库，因此这些用例直接证明验收要求的行为：不同节点/不同语义组从同一基线并发成功、同组过期返回服务端权威值、 纯 no-op 批不产生变化、节点与资源生命周期（含 pin
 * 保活的历史行）按语义组独立演进。
 */
class CanvasCommandPlannerTest {

  private static final UUID CANVAS = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

  private final List<UUID> allocated = new ArrayList<>();
  private final CanvasCommandPlanner planner = new CanvasCommandPlanner(CLOCK, this::allocate);

  /** 不同节点的同一语义组、同节点的不同语义组都必须从同一编辑基线并发成功。 */
  @Test
  void unrelatedGroupsFromOneBaselineBothSucceed() {
    CanvasResourceNode first = textNode(nodeId(1), "first", "a");
    CanvasResourceNode second = textNode(nodeId(2), "second", "b");
    CanvasGraph baseline = graph(List.of(first, second));

    CanvasCommandPlan renameOther =
        planner.plan(
            baseline,
            List.of(
                new CanvasCommand.RenameNode(nodeId(2), "second", "renamed"),
                new CanvasCommand.UpdateNodeTransform(
                    nodeId(1),
                    new CanvasTransform(5, 5, 20, 20),
                    new CanvasTransform(0, 0, 10, 10))));

    assertFalse(renameOther.isRejected());
    assertTrue(renameOther.hasChanges());
    assertEquals(2, renameOther.nodes().size());
    assertTrue(
        renameOther.mutations().stream()
            .allMatch(mutation -> mutation instanceof CanvasMutation.UpdateNode));
  }

  /** 同一节点同一语义组的过期基线必须返回该组的服务端权威投影，且批内不产生任何写入。 */
  @Test
  void staleGroupReturnsAuthoritativeValueWithoutWrites() {
    CanvasResourceNode node = textNode(nodeId(1), "current", "body");

    CanvasCommandPlan plan =
        planner.plan(
            graph(List.of(node)),
            List.of(new CanvasCommand.RenameNode(nodeId(1), "stale", "next")));

    CanvasConflict.StaleNode conflict =
        assertInstanceOf(CanvasConflict.StaleNode.class, plan.conflicts().get(0));
    assertEquals(CanvasConflict.NodeGroup.NAME, conflict.group());
    assertEquals("current", conflict.current().name());
    assertTrue(plan.mutations().isEmpty());
    assertTrue(plan.nodes().isEmpty());
  }

  /** 无变化的批不得产生持久化步骤，因此 revision 只在确有提交变化时前进。 */
  @Test
  void noOpBatchProducesNoMutations() {
    CanvasResourceNode node =
        new CanvasResourceNode(
            nodeId(1),
            CANVAS,
            "node",
            new CanvasTransform(0, 0, 10, 10),
            null,
            List.of(textResource(nodeId(1), 0, "body")),
            new CanvasFunction("video.generate", CanvasJson.parseObject("{\"a\":1,\"b\":2}")),
            null);

    CanvasCommandPlan plan =
        planner.plan(
            graph(List.of(node)),
            List.of(
                new CanvasCommand.RenameNode(nodeId(1), "node", "node"),
                new CanvasCommand.UpdateNodeTransform(
                    nodeId(1), new CanvasTransform(0, 0, 10, 10), null),
                new CanvasCommand.SetNodeFunction(
                    nodeId(1),
                    new CanvasFunction(
                        "video.generate", CanvasJson.parseObject("{\"b\":2,\"a\":1}")),
                    new CanvasFunction(
                        "video.generate", CanvasJson.parseObject("{\"b\":2,\"a\":1}")))));

    assertFalse(plan.isRejected());
    assertFalse(plan.hasChanges());
    assertTrue(plan.toPatch(7L).isEmpty());
    assertEquals(7L, plan.toPatch(7L).revision());
  }

  /** 布局在线操作按服务端接受顺序收敛（无基线），带基线的重连积压过期时拒绝。 */
  @Test
  void layoutConvergesOnlineButRejectsStaleBacklog() {
    CanvasResourceNode node = textNode(nodeId(1), "node", "body");
    CanvasGraph baseline =
        graph(
            List.of(
                new CanvasResourceNode(
                    node.id(),
                    CANVAS,
                    node.name(),
                    new CanvasTransform(100, 100, 10, 10),
                    null,
                    node.resources(),
                    null,
                    null)));

    assertFalse(
        planner
            .plan(
                baseline,
                List.of(
                    new CanvasCommand.UpdateNodeTransform(
                        nodeId(1), new CanvasTransform(200, 200, 10, 10), null)))
            .isRejected());

    CanvasCommandPlan backlog =
        planner.plan(
            baseline,
            List.of(
                new CanvasCommand.UpdateNodeTransform(
                    nodeId(1),
                    new CanvasTransform(300, 300, 10, 10),
                    new CanvasTransform(0, 0, 10, 10))));

    assertInstanceOf(CanvasConflict.StaleNode.class, backlog.conflicts().get(0));
    assertEquals(
        CanvasConflict.NodeGroup.LAYOUT,
        ((CanvasConflict.StaleNode) backlog.conflicts().get(0)).group());
  }

  /** 文本编辑创建新的不可变 Resource：旧行在无 pin 时删除，保留槽位维持身份。 */
  @Test
  void textEditCreatesNewResourceAndKeepsIdentity() {
    UUID keep = resourceId(11);
    CanvasResourceNode node = textNode(nodeId(1), "node", "old");
    CanvasResourceNode twoSlots =
        new CanvasResourceNode(
            node.id(),
            CANVAS,
            node.name(),
            node.transform(),
            null,
            List.of(
                new CanvasResource(keep, CANVAS, node.id(), 0, null, "a", "first", Instant.EPOCH),
                textResource(node.id(), 1, "second")),
            null,
            null);

    CanvasCommandPlan plan =
        planner.plan(
            graph(List.of(twoSlots)),
            List.of(
                new CanvasCommand.SetNodeResources(
                    nodeId(1),
                    List.of(keep, twoSlots.resources().get(1).id()),
                    List.of(
                        new CanvasResourceInput.Keep(keep),
                        new CanvasResourceInput.Text("b", "edited")))));

    assertFalse(plan.isRejected());
    CanvasMutation.InsertResource inserted =
        assertInstanceOf(
            CanvasMutation.InsertResource.class,
            plan.mutations().stream()
                .filter(mutation -> mutation instanceof CanvasMutation.InsertResource)
                .findFirst()
                .orElseThrow());
    assertEquals(1, inserted.resource().resourceIndex());
    assertTrue(
        plan.mutations().stream()
            .filter(mutation -> mutation instanceof CanvasMutation.DeleteResource)
            .map(mutation -> ((CanvasMutation.DeleteResource) mutation).resourceId())
            .toList()
            .contains(twoSlots.resources().get(1).id()));
    assertEquals(
        List.of(keep, inserted.resource().id()),
        assertInstanceOf(CanvasNodePatch.Upsert.class, plan.nodes().get(0))
            .node()
            .resources()
            .stream()
            .map(CanvasResource::id)
            .toList());
  }

  /** 被 Run pin 的历史资源在节点替换资源时只解除挂接，行与内容保持完好。 */
  @Test
  void pinnedResourceIsDetachedInsteadOfDeleted() {
    CanvasResourceNode node = textNode(nodeId(1), "node", "pinned");
    UUID pinnedResourceId = node.resources().get(0).id();
    CanvasGraph baseline =
        new CanvasGraph(CANVAS, List.of(node), List.of(), Set.of(pinnedResourceId));

    CanvasCommandPlan plan =
        planner.plan(
            baseline,
            List.of(
                new CanvasCommand.SetNodeResources(
                    nodeId(1),
                    List.of(pinnedResourceId),
                    List.of(new CanvasResourceInput.Text("node", "replacement")))));

    assertFalse(plan.isRejected());
    assertTrue(
        plan.mutations().stream()
            .anyMatch(
                mutation ->
                    mutation instanceof CanvasMutation.DetachResource detach
                        && detach.resourceId().equals(pinnedResourceId)));
    assertFalse(
        plan.mutations().stream()
            .anyMatch(
                mutation ->
                    mutation instanceof CanvasMutation.DeleteResource delete
                        && delete.resourceId().equals(pinnedResourceId)));
  }

  /** 运行期间禁止手工换输出与删除节点；配置仍可编辑。 */
  @Test
  void runningNodeRejectsResourceSwapAndDeleteButKeepsConfigEditable() {
    CanvasResourceNode node = runningFunctionNode(nodeId(1));
    CanvasGraph baseline = graph(List.of(node));

    CanvasCommandPlan swap =
        planner.plan(
            baseline,
            List.of(
                new CanvasCommand.SetNodeResources(
                    nodeId(1),
                    List.of(node.resources().get(0).id()),
                    List.of(new CanvasResourceInput.Text("node", "x")))));
    assertInstanceOf(CanvasConflict.NodeRunning.class, swap.conflicts().get(0));

    CanvasCommandPlan delete =
        planner.plan(
            baseline,
            List.of(
                new CanvasCommand.DeleteNode(
                    nodeId(1), List.of(node.resources().get(0).id()), node.function())));
    assertInstanceOf(CanvasConflict.NodeRunning.class, delete.conflicts().get(0));

    CanvasCommandPlan configure =
        planner.plan(
            baseline,
            List.of(
                new CanvasCommand.SetNodeFunction(
                    nodeId(1),
                    node.function(),
                    new CanvasFunction(
                        "video.generate", CanvasJson.parseObject("{\"next\":true}")))));
    assertFalse(configure.isRejected());
    assertFalse(configure.mutations().isEmpty());
  }

  /** 删除仍被引用的节点必须失败；同批解除引用后可以在同一原子编辑批中删除。 */
  @Test
  void referencedNodeRequiresExplicitReleaseInTheSameBatch() {
    CanvasResourceNode source = textNode(nodeId(1), "source", "images");
    CanvasFunction referencing =
        new CanvasFunction(
            "video.generate",
            CanvasJson.parseObject(
                "{\"source\":{\"type\":\"resource\",\"nodeId\":\""
                    + nodeId(1)
                    + "\",\"index\":0}}"));
    CanvasResourceNode consumer =
        new CanvasResourceNode(
            nodeId(2),
            CANVAS,
            "consumer",
            new CanvasTransform(0, 0, 10, 10),
            null,
            List.of(),
            referencing,
            null);
    CanvasGraph baseline = graph(List.of(source, consumer));

    CanvasCommandPlan rejected =
        planner.plan(
            baseline,
            List.of(
                new CanvasCommand.DeleteNode(
                    nodeId(1), List.of(source.resources().get(0).id()), null)));

    CanvasConflict.NodeReferenced conflict =
        assertInstanceOf(CanvasConflict.NodeReferenced.class, rejected.conflicts().get(0));
    assertEquals(List.of(nodeId(2)), conflict.referencingNodeIds());

    CanvasCommandPlan accepted =
        planner.plan(
            baseline,
            List.of(
                new CanvasCommand.SetNodeFunction(
                    nodeId(2),
                    referencing,
                    new CanvasFunction("video.generate", CanvasJson.parseObject("{}"))),
                new CanvasCommand.DeleteNode(
                    nodeId(1), List.of(source.resources().get(0).id()), null)));

    assertFalse(accepted.isRejected());
    assertTrue(
        accepted.mutations().stream()
            .anyMatch(mutation -> mutation instanceof CanvasMutation.DeleteFunctionRun));
    assertTrue(
        accepted.mutations().stream()
            .anyMatch(mutation -> mutation instanceof CanvasMutation.DeleteNode));
  }

  /** 引用不存在的源节点或自引用属于结构性非法配置，而不是可刷新的内容冲突。 */
  @Test
  void functionReferencesMustResolveWithinTheCanvas() {
    CanvasResourceNode node = textNode(nodeId(1), "node", "body");

    assertThrows(
        CanvasValidationException.class,
        () ->
            planner.plan(
                graph(List.of(node)),
                List.of(
                    new CanvasCommand.SetNodeFunction(
                        nodeId(1),
                        null,
                        new CanvasFunction(
                            "video.generate",
                            CanvasJson.parseObject(
                                "{\"source\":{\"type\":\"resource\",\"nodeId\":\""
                                    + nodeId(9)
                                    + "\",\"index\":0}}"))))));
    assertThrows(
        CanvasValidationException.class,
        () ->
            planner.plan(
                graph(List.of(node)),
                List.of(
                    new CanvasCommand.SetNodeFunction(
                        nodeId(1),
                        null,
                        new CanvasFunction(
                            "video.generate",
                            CanvasJson.parseObject(
                                "{\"source\":{\"type\":\"resource\",\"nodeId\":\""
                                    + nodeId(1)
                                    + "\",\"index\":0}}"))))));
  }

  /** 节点名按 NFKC 归一化后比较，重复名或非法名称在构造命令时即被拒绝。 */
  @Test
  void nodeNamesAreUniqueAfterCompatibilityFolding() {
    CanvasResourceNode node = textNode(nodeId(1), "name", "body");
    CanvasResourceNode other = textNode(nodeId(2), "other", "body");

    assertThrows(
        CanvasValidationException.class,
        () ->
            planner.plan(
                graph(List.of(node, other)),
                List.of(new CanvasCommand.RenameNode(nodeId(1), "name", "ＯＴＨＥＲ"))));
    assertFalse(
        planner
            .plan(
                graph(List.of(node)),
                List.of(new CanvasCommand.RenameNode(nodeId(1), "name", "ＮＡＭＥ")))
            .isRejected());
    assertThrows(
        CanvasValidationException.class,
        () -> new CanvasCommand.RenameNode(nodeId(1), "name", "with\u0007control"));
    assertThrows(
        CanvasValidationException.class, () -> new CanvasCommand.RenameNode(nodeId(1), " ", "x"));
  }

  /** 分组命令只影响自己的语义组：建组、挂载成员、改名与删组保持成员节点一致。 */
  @Test
  void groupCommandsKeepMemberNodesConsistent() {
    CanvasResourceNode node = textNode(nodeId(1), "node", "body");
    UUID groupId = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    CanvasCommandPlan created =
        planner.plan(
            graph(List.of(node)),
            List.of(
                new CanvasCommand.CreateGroup(groupId, "group", new CanvasTransform(0, 0, 10, 10)),
                new CanvasCommand.SetNodeGroup(nodeId(1), null, groupId)));

    assertFalse(created.isRejected());
    CanvasGroup group =
        assertInstanceOf(CanvasGroupPatch.Upsert.class, created.groups().get(0)).group();
    assertEquals(groupId, group.id());
    CanvasResourceNode patched =
        assertInstanceOf(CanvasNodePatch.Upsert.class, created.nodes().get(0)).node();
    assertEquals(groupId, patched.groupId());

    CanvasGraph withMember = graph(List.of(patched), List.of(group));
    CanvasCommandPlan stale =
        planner.plan(withMember, List.of(new CanvasCommand.DeleteGroup(groupId, List.of())));
    assertInstanceOf(CanvasConflict.StaleNode.class, stale.conflicts().get(0));

    CanvasCommandPlan deleted =
        planner.plan(
            withMember, List.of(new CanvasCommand.DeleteGroup(groupId, List.of(nodeId(1)))));
    assertFalse(deleted.isRejected());
    assertTrue(
        deleted.mutations().stream()
            .anyMatch(mutation -> mutation instanceof CanvasMutation.DeleteGroup));
    assertNull(
        assertInstanceOf(CanvasNodePatch.Upsert.class, deleted.nodes().get(0)).node().groupId());
  }

  /** 新建、引用、改名与删除混合成批时必须仍然满足节点不变量（资源或函数至少一个）。 */
  @Test
  void batchValidatesNodeInvariants() {
    CanvasCommandPlan plan =
        planner.plan(
            graph(List.of()),
            List.of(
                new CanvasCommand.CreateNode(
                    nodeId(3),
                    "created",
                    new CanvasTransform(0, 0, 10, 10),
                    List.of(new CanvasResourceInput.Text("text", "body")))));

    assertFalse(plan.isRejected());
    CanvasResourceNode created =
        assertInstanceOf(CanvasNodePatch.Upsert.class, plan.nodes().get(0)).node();
    assertEquals(1, created.resources().size());
    assertEquals(0, created.resources().get(0).resourceIndex());

    assertThrows(
        CanvasValidationException.class,
        () ->
            planner.plan(
                graph(List.of()),
                List.of(
                    new CanvasCommand.CreateNode(
                        nodeId(4), "empty", new CanvasTransform(0, 0, 10, 10), List.of()))));
    assertThrows(
        CanvasValidationException.class,
        () ->
            planner.plan(
                graph(List.of()),
                List.of(
                    new CanvasCommand.CreateNode(
                        nodeId(5),
                        "mixed",
                        new CanvasTransform(0, 0, 10, 10),
                        List.of(
                            new CanvasResourceInput.Text("text", "body"),
                            new CanvasResourceInput.Blob("image", UUID.randomUUID()))))));
  }

  /** 缺失目标与重复创建返回可判别的冲突，便于客户端刷新或改用新 id。 */
  @Test
  void missingAndPresentTargetsAreReportedInTheSameBatch() {
    CanvasResourceNode node = textNode(nodeId(1), "node", "body");
    CanvasCommandPlan plan =
        planner.plan(
            graph(List.of(node)),
            List.of(
                new CanvasCommand.RenameNode(nodeId(7), "gone", "next"),
                new CanvasCommand.CreateNode(
                    nodeId(1),
                    "duplicate",
                    new CanvasTransform(0, 0, 10, 10),
                    List.of(new CanvasResourceInput.Text("text", "body")))));

    assertEquals(2, plan.conflicts().size());
    assertInstanceOf(CanvasConflict.TargetMissing.class, plan.conflicts().get(0));
    assertInstanceOf(CanvasConflict.TargetPresent.class, plan.conflicts().get(1));
    assertTrue(plan.mutations().isEmpty());
  }

  @Test
  void deleteThenCreateMustNotReuseIdentityInTheSameBatch() {
    // 测试意图：同一批先删后建不能复用 node/group UUID；校验失败时不得留下可提交的部分 mutation。
    CanvasResourceNode node = textNode(nodeId(1), "node", "body");
    CanvasValidationException reusedNode =
        assertThrows(
            CanvasValidationException.class,
            () ->
                planner.plan(
                    graph(List.of(node)),
                    List.of(
                        new CanvasCommand.DeleteNode(
                            nodeId(1), List.of(node.resources().get(0).id()), null),
                        new CanvasCommand.CreateNode(
                            nodeId(1),
                            "reborn",
                            new CanvasTransform(1, 1, 10, 10),
                            List.of(new CanvasResourceInput.Text("text", "next"))))));
    assertTrue(reusedNode.getMessage().toLowerCase().contains("node"));

    UUID groupId = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    CanvasGroup group =
        new CanvasGroup(groupId, CANVAS, "group", new CanvasTransform(0, 0, 10, 10));
    CanvasValidationException reusedGroup =
        assertThrows(
            CanvasValidationException.class,
            () ->
                planner.plan(
                    graph(List.of(node), List.of(group)),
                    List.of(
                        new CanvasCommand.DeleteGroup(groupId, List.of()),
                        new CanvasCommand.CreateGroup(
                            groupId, "reborn", new CanvasTransform(2, 2, 10, 10)))));
    assertTrue(reusedGroup.getMessage().toLowerCase().contains("group"));

    CanvasCommandPlan distinct =
        planner.plan(
            graph(List.of(node)),
            List.of(
                new CanvasCommand.DeleteNode(
                    nodeId(1), List.of(node.resources().get(0).id()), null),
                new CanvasCommand.CreateNode(
                    nodeId(2),
                    "created",
                    new CanvasTransform(1, 1, 10, 10),
                    List.of(new CanvasResourceInput.Text("text", "next")))));
    assertFalse(distinct.isRejected());
    assertTrue(distinct.hasChanges());
  }

  /** 两槽位文本节点，第一个槽位使用固定 id，便于断言资源身份与槽位变化。 */
  private CanvasResourceNode twoSlotNode(UUID firstResourceId) {
    return new CanvasResourceNode(
        nodeId(1),
        CANVAS,
        "node",
        new CanvasTransform(0, 0, 10, 10),
        null,
        List.of(
            new CanvasResource(
                firstResourceId, CANVAS, nodeId(1), 0, null, "a", "first", Instant.EPOCH),
            textResource(nodeId(1), 1, "second")),
        null,
        null);
  }

  /** 资源列表与编辑基线完全一致时不得产生行级步骤，否则无变化的批会白白推进 revision。 */
  @Test
  void resourceListIdenticalToBaselineIsANoOp() {
    UUID keep = resourceId(11);
    CanvasResourceNode node = twoSlotNode(keep);

    CanvasCommandPlan plan =
        planner.plan(
            graph(List.of(node)),
            List.of(
                new CanvasCommand.SetNodeResources(
                    nodeId(1),
                    List.of(keep, node.resources().get(1).id()),
                    List.of(
                        new CanvasResourceInput.Keep(keep),
                        new CanvasResourceInput.Keep(node.resources().get(1).id())))));

    assertFalse(plan.isRejected());
    assertFalse(plan.hasChanges());
    assertTrue(plan.nodes().isEmpty());
    assertEquals(5L, plan.toPatch(5L).revision());
  }

  /** 重排是真实变化且不改写内容行：同一批资源只改变挂接槽位，不产生插入或删除。 */
  @Test
  void resourceReorderReusesRowsAndChangesSlots() {
    UUID first = resourceId(11);
    CanvasResourceNode node = twoSlotNode(first);
    UUID second = node.resources().get(1).id();

    CanvasCommandPlan plan =
        planner.plan(
            graph(List.of(node)),
            List.of(
                new CanvasCommand.SetNodeResources(
                    nodeId(1),
                    List.of(first, second),
                    List.of(
                        new CanvasResourceInput.Keep(second),
                        new CanvasResourceInput.Keep(first)))));

    assertFalse(plan.isRejected());
    assertEquals(
        List.of(
            new CanvasMutation.DetachResource(second, nodeId(1)),
            new CanvasMutation.DetachResource(first, nodeId(1)),
            new CanvasMutation.AttachResource(second, nodeId(1), 0),
            new CanvasMutation.AttachResource(first, nodeId(1), 1)),
        plan.mutations());
    assertEquals(
        List.of(second, first),
        assertInstanceOf(CanvasNodePatch.Upsert.class, plan.nodes().get(0))
            .node()
            .resources()
            .stream()
            .map(CanvasResource::id)
            .toList());
  }

  private UUID allocate() {
    UUID resourceId = UUID.randomUUID();
    allocated.add(resourceId);
    return resourceId;
  }

  private static UUID nodeId(int index) {
    return UUID.fromString("00000000-0000-0000-0000-0000000000%02d".formatted(index));
  }

  private static UUID resourceId(int index) {
    return UUID.fromString("00000000-0000-0000-0000-0000000001%02d".formatted(index));
  }

  private static CanvasResource textResource(UUID nodeId, int index, String text) {
    return new CanvasResource(
        resourceIdOf(nodeId, index), CANVAS, nodeId, index, null, "text", text, Instant.EPOCH);
  }

  private static UUID resourceIdOf(UUID nodeId, int index) {
    return new UUID(nodeId.getMostSignificantBits(), nodeId.getLeastSignificantBits() + index + 1);
  }

  private CanvasResourceNode textNode(UUID nodeId, String name, String text) {
    return new CanvasResourceNode(
        nodeId,
        CANVAS,
        name,
        new CanvasTransform(0, 0, 10, 10),
        null,
        List.of(textResource(nodeId, 0, text)),
        null,
        null);
  }

  private CanvasResourceNode runningFunctionNode(UUID nodeId) {
    CanvasFunctionRun run =
        new CanvasFunctionRun(
            nodeId,
            UUID.randomUUID(),
            CanvasFunctionRunStatus.RUNNING,
            1,
            null,
            "lease",
            Instant.parse("2026-01-01T00:10:00Z"),
            "RUNNING",
            "{\"stage\":\"RUNNING\"}",
            null,
            Instant.EPOCH,
            Instant.EPOCH);
    return new CanvasResourceNode(
        nodeId,
        CANVAS,
        "consumer",
        new CanvasTransform(0, 0, 10, 10),
        null,
        List.of(
            new CanvasResource(
                resourceId(40),
                CANVAS,
                nodeId,
                0,
                UUID.randomUUID(),
                "out.png",
                null,
                Instant.EPOCH)),
        new CanvasFunction("video.generate", CanvasJson.parseObject("{}")),
        run);
  }

  private static CanvasGraph graph(List<CanvasResourceNode> nodes) {
    return new CanvasGraph(CANVAS, nodes, List.of(), Set.of());
  }

  private static CanvasGraph graph(List<CanvasResourceNode> nodes, List<CanvasGroup> groups) {
    return new CanvasGraph(CANVAS, nodes, groups, Set.of());
  }
}
