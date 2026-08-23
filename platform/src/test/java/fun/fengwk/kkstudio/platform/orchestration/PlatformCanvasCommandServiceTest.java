package fun.fengwk.kkstudio.platform.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import fun.fengwk.kkstudio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.canvas.CanvasConflictException;
import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasGroup;
import fun.fengwk.kkstudio.canvas.CanvasGroupPatch;
import fun.fengwk.kkstudio.canvas.CanvasLinkPatch;
import fun.fengwk.kkstudio.canvas.CanvasNodePatch;
import fun.fengwk.kkstudio.canvas.CanvasPatch;
import fun.fengwk.kkstudio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** PostgreSQL 上的 Canvas v1 command、CAS、幂等和聚合快照覆盖。 */
@Import(PlatformCanvasCommandServiceTest.TestAdapterConfiguration.class)
@TestPropertySource(properties = "kk-studio.test.durable-canvas-service=true")
public class PlatformCanvasCommandServiceTest extends PostgresSpringTestSupport {

  private static final CanvasTransform T = new CanvasTransform(10, 20, 100, 80);
  private static final String FUNCTION_CONFIG =
      """
      {"prompt":{"segments":[{"type":"TEXT","text":"prompt"}]},"parameters":{}}
      """;
  private static final CanvasFunctionReferencePolicy NO_REFERENCES =
      new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 0, Map.of());

  @Autowired private CanvasCommandService commandService;
  @Autowired private CanvasQueryService queryService;
  @Autowired private CanvasResourceRepository resourceRepository;
  @Autowired private CanvasFunctionRunRepository runRepository;

  @Test
  void createListSnapshotAndTextUpdateKeepResourceIdentity() {
    CanvasDocument canvas = commandService.createCanvas(" board ");
    assertEquals("board", canvas.title());
    assertEquals(0L, canvas.version());
    assertTrue(
        queryService.listDocuments().stream().anyMatch(item -> item.id().equals(canvas.id())));

    UUID textNode = UUID.randomUUID();
    CanvasPatch created =
        apply(
            canvas,
            0,
            uuid("text-create"),
            new CanvasCommand.CreateTextNode(textNode, "note", "old", T));
    assertEquals(1L, created.version());
    CanvasResourceNode node = snapshot(canvas).nodes().get(0);
    CanvasResource oldResource = node.resources().get(0);
    assertEquals("old", oldResource.textContent());

    CanvasPatch updated =
        apply(
            canvas,
            1,
            uuid("text-update"),
            new CanvasCommand.UpdateTextNode(textNode, "new markdown"));
    assertEquals(2L, updated.version());
    CanvasResourceNode updatedNode = snapshot(canvas).nodes().get(0);
    assertEquals(
        oldResource.id(),
        updatedNode.resources().get(0).id(),
        "text update keeps resource identity");
    assertEquals("new markdown", updatedNode.resources().get(0).textContent());
  }

  @Test
  void everyNodeFunctionTransformAndLinkCommandIsApplied() {
    CanvasDocument canvas = commandService.createCanvas("commands");
    UUID sourceId = UUID.randomUUID();
    UUID targetId = UUID.randomUUID();
    apply(
        canvas,
        0,
        uuid("create-nodes"),
        new CanvasCommand.CreateTextNode(sourceId, "source", "x", T),
        new CanvasCommand.CreateFunctionNode(
            targetId, "target", "model-a", FUNCTION_CONFIG, new CanvasTransform(200, 20, 120, 90)));

    CanvasPatch changed =
        apply(
            canvas,
            1,
            uuid("edit-graph"),
            new CanvasCommand.UpdateFunction(targetId, "model-b", FUNCTION_CONFIG),
            new CanvasCommand.RenameNode(sourceId, "renamed"),
            new CanvasCommand.UpdateNodeTransforms(
                List.of(
                    new CanvasCommand.NodeTransformUpdate(
                        sourceId, new CanvasTransform(30, 40, 130, 100)))),
            new CanvasCommand.CreateLink(sourceId, targetId));
    CanvasSnapshot afterEdit = snapshot(canvas);
    CanvasResourceNode source = node(afterEdit, sourceId);
    CanvasResourceNode target = node(afterEdit, targetId);
    UUID sourceResourceId = source.resources().get(0).id();
    assertEquals("renamed", source.name());
    assertEquals(new CanvasTransform(30, 40, 130, 100), source.transform());
    assertEquals("model-b", target.function().modelKey());
    assertEquals(1, afterEdit.links().size());
    assertEquals(
        new CanvasLinkKey(sourceId, targetId),
        new CanvasLinkKey(
            afterEdit.links().get(0).sourceNodeId(), afterEdit.links().get(0).targetNodeId()));

    CanvasPatch withoutLink =
        apply(canvas, 2, uuid("delete-link"), new CanvasCommand.DeleteLink(sourceId, targetId));
    assertEquals(List.of(new CanvasLinkPatch.Remove(sourceId, targetId)), withoutLink.links());
    assertTrue(snapshot(canvas).links().isEmpty());

    apply(canvas, 3, uuid("restore-link"), new CanvasCommand.CreateLink(sourceId, targetId));
    CanvasPatch withoutSource =
        apply(canvas, 4, uuid("delete-node"), new CanvasCommand.DeleteNode(sourceId));
    CanvasSnapshot afterDelete = snapshot(canvas);
    assertEquals(1, afterDelete.nodes().size());
    assertTrue(
        resourceRepository.findById(canvas.id(), sourceResourceId).isEmpty(),
        "delete node removes its owned resources");
    assertEquals(
        List.of(new CanvasLinkPatch.Remove(sourceId, targetId)),
        withoutSource.links(),
        "delete node patch must remove every incident link");
    assertEquals(5L, withoutSource.version());
  }

  @Test
  void linkRequiresFunctionTargetAndSameCanvas() {
    CanvasDocument firstCanvas = commandService.createCanvas("first");
    CanvasDocument secondCanvas = commandService.createCanvas("second");
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID fn = UUID.randomUUID();
    apply(
        firstCanvas,
        0,
        uuid("nodes"),
        new CanvasCommand.CreateTextNode(a, "a", "a", T),
        new CanvasCommand.CreateTextNode(b, "b", "b", T),
        new CanvasCommand.CreateFunctionNode(fn, "fn", "m", FUNCTION_CONFIG, T));
    UUID foreign = UUID.randomUUID();
    apply(
        secondCanvas,
        0,
        uuid("foreign-node"),
        new CanvasCommand.CreateFunctionNode(foreign, "foreign", "m", FUNCTION_CONFIG, T));

    assertThrows(
        IllegalArgumentException.class,
        () -> apply(firstCanvas, 1, uuid("ordinary-target"), new CanvasCommand.CreateLink(a, b)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            apply(
                firstCanvas, 1, uuid("foreign-target"), new CanvasCommand.CreateLink(a, foreign)));

    CanvasPatch linked =
        apply(firstCanvas, 1, uuid("valid-link"), new CanvasCommand.CreateLink(a, fn));
    assertEquals(1, snapshot(firstCanvas).links().size());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            apply(
                firstCanvas,
                2,
                uuid("long-model"),
                new CanvasCommand.CreateFunctionNode(
                    UUID.randomUUID(), "long", "m".repeat(257), FUNCTION_CONFIG, T)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            apply(
                firstCanvas,
                2,
                uuid("unknown-model"),
                new CanvasCommand.CreateFunctionNode(
                    UUID.randomUUID(), "unknown", "unknown", FUNCTION_CONFIG, T)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            apply(
                firstCanvas,
                2,
                uuid("unknown-parameter"),
                new CanvasCommand.CreateFunctionNode(
                    UUID.randomUUID(),
                    "invalid",
                    "m",
                    """
                    {"prompt":{"segments":[{"type":"TEXT","text":"prompt"}]},"parameters":{"extra":"x"}}
                    """,
                    T)));
  }

  @Test
  void linksMayFormCyclesBecauseTheyAreOnlyReferenceCandidates() {
    CanvasDocument canvas = commandService.createCanvas("cycles");
    UUID left = UUID.randomUUID();
    UUID right = UUID.randomUUID();
    apply(
        canvas,
        0,
        uuid("cycle-functions"),
        new CanvasCommand.CreateFunctionNode(left, "left", "m", FUNCTION_CONFIG, T),
        new CanvasCommand.CreateFunctionNode(right, "right", "m", FUNCTION_CONFIG, T));
    resourceRepository.add(
        new CanvasResource(
            UUID.randomUUID(), canvas.id(), left, 0, null, "left", "left", Instant.now()));
    resourceRepository.add(
        new CanvasResource(
            UUID.randomUUID(), canvas.id(), right, 0, null, "right", "right", Instant.now()));

    apply(canvas, 1, uuid("left-right"), new CanvasCommand.CreateLink(left, right));
    CanvasPatch cycle =
        apply(canvas, 2, uuid("right-left"), new CanvasCommand.CreateLink(right, left));

    assertEquals(1, cycle.links().size());
    assertEquals(2, snapshot(canvas).links().size());
  }

  @Test
  void groupMoveUsesDeltaAndUngroupSupportsMemberSubsets() {
    CanvasDocument canvas = commandService.createCanvas("groups");
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    apply(
        canvas,
        0,
        uuid("nodes"),
        new CanvasCommand.CreateTextNode(firstId, "a", "a", new CanvasTransform(10, 20, 100, 80)),
        new CanvasCommand.CreateTextNode(secondId, "b", "b", new CanvasTransform(30, 50, 110, 90)));

    UUID groupId = UUID.randomUUID();
    CanvasPatch grouped =
        apply(
            canvas,
            1,
            uuid("group"),
            new CanvasCommand.CreateGroup(
                groupId, "G", new CanvasTransform(0, 0, 300, 200), List.of(firstId, secondId)));
    CanvasSnapshot afterGroup = snapshot(canvas);
    assertEquals(groupId, afterGroup.groups().get(0).id());
    assertEquals(groupId, node(afterGroup, firstId).groupId());
    CanvasPatch moved =
        apply(canvas, 2, uuid("move-group"), new CanvasCommand.MoveGroup(groupId, 100, 50));
    CanvasSnapshot afterMove = snapshot(canvas);
    assertEquals(new CanvasTransform(100, 50, 300, 200), afterMove.groups().get(0).transform());
    assertEquals(new CanvasTransform(110, 70, 100, 80), node(afterMove, firstId).transform());
    assertEquals(new CanvasTransform(130, 100, 110, 90), node(afterMove, secondId).transform());

    CanvasPatch partiallyUngrouped =
        apply(
            canvas,
            3,
            uuid("partial-ungroup"),
            new CanvasCommand.Ungroup(groupId, List.of(secondId)));
    CanvasSnapshot afterPartialUngroup = snapshot(canvas);
    assertEquals(groupId, node(afterPartialUngroup, firstId).groupId());
    assertNull(node(afterPartialUngroup, secondId).groupId());
    assertEquals(1, afterPartialUngroup.groups().size(), "group remains while it has a member");
    assertTrue(partiallyUngrouped.groups().isEmpty());
    assertEquals(
        new CanvasNodePatch.Upsert(node(afterPartialUngroup, secondId)),
        partiallyUngrouped.nodes().get(0));

    apply(
        canvas,
        4,
        uuid("move-after-partial-ungroup"),
        new CanvasCommand.MoveGroup(groupId, 200, 100));
    CanvasSnapshot afterSecondMove = snapshot(canvas);
    assertEquals(
        new CanvasTransform(210, 120, 100, 80),
        node(afterSecondMove, firstId).transform(),
        "remaining member follows the group");
    assertEquals(
        new CanvasTransform(130, 100, 110, 90),
        node(afterSecondMove, secondId).transform(),
        "detached member must not follow later group moves");

    CanvasPatch ungrouped =
        apply(
            canvas, 5, uuid("last-ungroup"), new CanvasCommand.Ungroup(groupId, List.of(firstId)));
    CanvasSnapshot afterUngroup = snapshot(canvas);
    assertNull(node(afterUngroup, firstId).groupId());
    assertTrue(afterUngroup.groups().isEmpty(), "last member removal deletes the group row");
    assertEquals(List.of(new CanvasGroupPatch.Remove(groupId)), ungrouped.groups());
    assertEquals(new CanvasNodePatch.Upsert(node(afterUngroup, firstId)), ungrouped.nodes().get(0));

    UUID secondGroupId = UUID.randomUUID();
    apply(
        canvas,
        6,
        uuid("re-group"),
        new CanvasCommand.CreateGroup(
            secondGroupId, "G", new CanvasTransform(0, 0, 300, 200), List.of(secondId)));
    CanvasPatch deleted =
        apply(canvas, 7, uuid("delete-group"), new CanvasCommand.DeleteGroup(secondGroupId));
    CanvasSnapshot afterDelete = snapshot(canvas);
    assertTrue(afterDelete.groups().isEmpty());
    assertNull(node(afterDelete, secondId).groupId());
    assertEquals(new CanvasNodePatch.Upsert(node(afterDelete, secondId)), deleted.nodes().get(0));
  }

  @Test
  void ungroupRejectsUnknownOrDuplicateMembers() {
    CanvasDocument canvas = commandService.createCanvas("invalid-ungroup");
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    apply(
        canvas,
        0,
        uuid("invalid-ungroup-nodes"),
        new CanvasCommand.CreateTextNode(firstId, "a", "a", new CanvasTransform(10, 20, 100, 80)),
        new CanvasCommand.CreateTextNode(secondId, "b", "b", new CanvasTransform(30, 50, 110, 90)));
    UUID groupId = UUID.randomUUID();
    apply(
        canvas,
        1,
        uuid("invalid-ungroup-group"),
        new CanvasCommand.CreateGroup(
            groupId, "G", new CanvasTransform(0, 0, 300, 200), List.of(firstId)));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            apply(
                canvas,
                2,
                uuid("invalid-ungroup-foreign"),
                new CanvasCommand.Ungroup(groupId, List.of(secondId))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            apply(
                canvas,
                2,
                uuid("invalid-ungroup-duplicate"),
                new CanvasCommand.Ungroup(groupId, List.of(firstId, firstId))));
  }

  @Test
  void renameGroupUpdatesTitleAndKeepsMembersAndGeometry() {
    CanvasDocument canvas = commandService.createCanvas("rename-group");
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    apply(
        canvas,
        0,
        uuid("members"),
        new CanvasCommand.CreateTextNode(firstId, "a", "a", new CanvasTransform(10, 20, 100, 80)),
        new CanvasCommand.CreateTextNode(secondId, "b", "b", new CanvasTransform(30, 50, 110, 90)));
    UUID groupId = UUID.randomUUID();
    apply(
        canvas,
        1,
        uuid("group"),
        new CanvasCommand.CreateGroup(
            groupId, "G", new CanvasTransform(0, 0, 300, 200), List.of(firstId, secondId)));
    apply(canvas, 2, uuid("move-group"), new CanvasCommand.MoveGroup(groupId, 100, 50));
    CanvasSnapshot beforeRename = snapshot(canvas);

    CanvasPatch renamed =
        apply(
            canvas,
            3,
            uuid("rename-group"),
            new CanvasCommand.RenameGroup(groupId, " Renamed Group "));
    assertEquals(4L, renamed.version());
    assertEquals(
        List.of(
            new CanvasGroupPatch.Upsert(
                new CanvasGroup(
                    groupId,
                    canvas.id(),
                    "Renamed Group",
                    new CanvasTransform(100, 50, 300, 200)))),
        renamed.groups());
    assertTrue(renamed.nodes().isEmpty(), "title rename must not touch node patches");
    assertTrue(renamed.links().isEmpty(), "title rename must not touch link patches");

    CanvasSnapshot afterRename = snapshot(canvas);
    CanvasGroup group = afterRename.groups().get(0);
    assertEquals("Renamed Group", group.title());
    assertEquals(new CanvasTransform(100, 50, 300, 200), group.transform());
    assertEquals(groupId, node(afterRename, firstId).groupId());
    assertEquals(groupId, node(afterRename, secondId).groupId());
    assertEquals(
        beforeRename.nodes().stream().map(CanvasResourceNode::transform).toList(),
        afterRename.nodes().stream().map(CanvasResourceNode::transform).toList(),
        "title rename must keep member geometry");
  }

  @Test
  void renameGroupRejectsBlankTitleUnknownGroupAndStaleVersion() {
    CanvasDocument canvas = commandService.createCanvas("rename-errors");
    UUID nodeId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    apply(
        canvas,
        0,
        uuid("group"),
        new CanvasCommand.CreateTextNode(nodeId, "a", "a", T),
        new CanvasCommand.CreateGroup(
            groupId, "G", new CanvasTransform(0, 0, 300, 200), List.of(nodeId)));

    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasCommand.RenameGroup(groupId, " "),
        "blank title is rejected by the domain command constructor");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            apply(
                canvas,
                1,
                uuid("unknown-group"),
                new CanvasCommand.RenameGroup(UUID.randomUUID(), "x")),
        "missing group is rejected deterministically");
    assertThrows(
        CanvasConflictException.class,
        () -> apply(canvas, 3, uuid("stale-rename"), new CanvasCommand.RenameGroup(groupId, "x")),
        "stale expectedVersion conflicts without mutation");

    CanvasSnapshot after = snapshot(canvas);
    assertEquals(1L, after.document().version());
    assertEquals("G", after.groups().get(0).title());
    assertEquals(groupId, node(after, nodeId).groupId());
  }

  @Test
  void versionCasIdempotencyAndAtomicRollbackAreEnforced() throws Exception {
    CanvasDocument canvas = commandService.createCanvas("atomic");
    UUID nodeA = UUID.randomUUID();
    UUID commandId = UUID.randomUUID();
    List<CanvasCommand> batch = List.of(new CanvasCommand.CreateTextNode(nodeA, "a", "x", T));
    CanvasPatch first = commandService.applyCommands(canvas.id(), 0, commandId, batch);
    assertEquals(1L, first.version());
    CanvasPatch replay = commandService.applyCommands(canvas.id(), 0, commandId, batch);
    assertTrue(replay.isEmpty(), "exact replay returns a deterministic no-op patch");
    assertEquals(1L, replay.version());
    assertEquals(1L, snapshot(canvas).document().version());

    CanvasConflictException hashConflict =
        assertThrows(
            CanvasConflictException.class,
            () ->
                commandService.applyCommands(
                    canvas.id(),
                    0,
                    commandId,
                    List.of(new CanvasCommand.CreateTextNode(UUID.randomUUID(), "other", "y", T))));
    assertEquals(CanvasConflictException.Reason.IDEMPOTENCY_CONFLICT, hashConflict.reason());

    CanvasConflictException versionConflict =
        assertThrows(
            CanvasConflictException.class,
            () ->
                commandService.applyCommands(
                    canvas.id(),
                    0,
                    UUID.randomUUID(),
                    List.of(new CanvasCommand.CreateTextNode(UUID.randomUUID(), "b", "x", T))));
    assertEquals(CanvasConflictException.Reason.VERSION_CONFLICT, versionConflict.reason());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            commandService.applyCommands(
                canvas.id(),
                1,
                UUID.randomUUID(),
                List.of(
                    new CanvasCommand.CreateTextNode(UUID.randomUUID(), "temporary", "x", T),
                    new CanvasCommand.RenameNode(UUID.randomUUID(), "missing"))));
    CanvasSnapshot afterRollback = snapshot(canvas);
    assertEquals(1L, afterRollback.document().version());
    assertEquals(1, afterRollback.nodes().size());
    assertEquals(1L, count("canvas_resource", canvas.id()));
  }

  @Test
  void concurrentRaceLeavesOneVersionWinner() throws Exception {
    CanvasDocument raceCanvas = commandService.createCanvas("race");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<Object> first =
          executor.submit(
              () ->
                  race(
                      start,
                      raceCanvas,
                      new CanvasCommand.CreateTextNode(UUID.randomUUID(), "Race", "a", T)));
      Future<Object> second =
          executor.submit(
              () ->
                  race(
                      start,
                      raceCanvas,
                      new CanvasCommand.CreateTextNode(UUID.randomUUID(), "race", "b", T)));
      start.countDown();
      Object firstResult = first.get();
      Object secondResult = second.get();
      long successes =
          List.of(firstResult, secondResult).stream().filter(CanvasPatch.class::isInstance).count();
      assertEquals(1L, successes);
      assertTrue(
          List.of(firstResult, secondResult).stream()
              .anyMatch(result -> result instanceof CanvasConflictException));
      CanvasSnapshot snapshot = snapshot(raceCanvas);
      assertEquals(1, snapshot.nodes().size());
      assertEquals(1L, snapshot.document().version());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void functionRunRepositoryFeedsSnapshot() {
    CanvasDocument canvas = commandService.createCanvas("run");
    UUID nodeId = UUID.randomUUID();
    apply(
        canvas,
        0,
        uuid("fn"),
        new CanvasCommand.CreateFunctionNode(nodeId, "fn", "m", FUNCTION_CONFIG, T));
    runRepository.insertRunning(
        new CanvasFunctionRun(
            nodeId,
            UUID.randomUUID(),
            CanvasFunctionRunStatus.RUNNING,
            "QUEUED",
            "{\"stage\":\"QUEUED\"}",
            null,
            Instant.now()));

    CanvasSnapshot snapshot = snapshot(canvas);
    assertNotNull(node(snapshot, nodeId).run());
    assertEquals(CanvasFunctionRunStatus.RUNNING, node(snapshot, nodeId).run().status());
    assertEquals(1L, snapshot.document().version());
  }

  @Test
  void snapshotNeverMixesFunctionRunAndResourceGenerations() throws Exception {
    // 分别卡住 Run 与 Resource 查询，证明 terminal commit 穿过聚合过程时只返回完整的新一代事实。
    assertStableSnapshotAcrossFunctionCommit("canvas_function_run", "run-lock");
    assertStableSnapshotAcrossFunctionCommit("canvas_resource", "resource-lock");
  }

  private CanvasSnapshot snapshot(CanvasDocument canvas) {
    return queryService.findSnapshot(canvas.id()).orElseThrow();
  }

  private static CanvasResourceNode node(CanvasSnapshot snapshot, UUID nodeId) {
    return snapshot.nodes().stream()
        .filter(node -> node.id().equals(nodeId))
        .findFirst()
        .orElseThrow();
  }

  private static UUID uuid(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private CanvasPatch apply(
      CanvasDocument canvas, long expectedVersion, UUID commandId, CanvasCommand... commands) {
    return commandService.applyCommands(canvas.id(), expectedVersion, commandId, List.of(commands));
  }

  private void assertStableSnapshotAcrossFunctionCommit(String lockedTable, String suffix)
      throws Exception {
    CanvasDocument canvas = commandService.createCanvas("snapshot-" + suffix);
    UUID nodeId = UUID.randomUUID();
    apply(
        canvas,
        0,
        uuid("function-" + suffix),
        new CanvasCommand.CreateFunctionNode(
            nodeId, "function-" + suffix, "m", FUNCTION_CONFIG, T));
    UUID requestId = UUID.randomUUID();
    CanvasResource oldResource =
        new CanvasResource(
            UUID.randomUUID(), canvas.id(), nodeId, 0, null, "old-" + suffix, "old", Instant.now());
    CanvasResource newResource =
        new CanvasResource(
            UUID.randomUUID(),
            canvas.id(),
            null,
            null,
            null,
            "new-" + suffix,
            "new",
            Instant.now());
    resourceRepository.add(oldResource);
    resourceRepository.add(newResource);
    runRepository.insertRunning(
        new CanvasFunctionRun(
            nodeId,
            requestId,
            CanvasFunctionRunStatus.RUNNING,
            "QUEUED",
            "{\"stage\":\"QUEUED\"}",
            null,
            Instant.now()));

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection updater = PostgresSchemaSupport.newConnection()) {
      updater.setAutoCommit(false);
      execute(updater, "lock table " + lockedTable + " in access exclusive mode");
      replaceOwnedResource(updater, canvas.id(), nodeId, oldResource.id(), newResource.id());
      transitionRunSucceeded(updater, nodeId, requestId);
      incrementVersion(updater, canvas.id());

      Future<CanvasSnapshot> read =
          executor.submit(() -> queryService.findSnapshot(canvas.id()).orElseThrow());
      assertTrue(awaitBlockedQuery(lockedTable), "snapshot query did not reach " + lockedTable);
      updater.commit();

      CanvasSnapshot snapshot = read.get(5, TimeUnit.SECONDS);
      CanvasResourceNode node = node(snapshot, nodeId);
      assertEquals(2L, snapshot.document().version());
      assertNotNull(node.run());
      assertEquals(CanvasFunctionRunStatus.SUCCEEDED, node.run().status());
      assertEquals(
          List.of(newResource.id()), node.resources().stream().map(CanvasResource::id).toList());
    } finally {
      executor.shutdownNow();
    }
  }

  private void replaceOwnedResource(
      Connection connection, UUID canvasId, UUID nodeId, UUID oldResourceId, UUID newResourceId)
      throws Exception {
    try (PreparedStatement detach =
            connection.prepareStatement(
                """
                update canvas_resource
                set owner_node_id = null, resource_index = null
                where canvas_id = ? and id = ? and owner_node_id = ?
                """);
        PreparedStatement attach =
            connection.prepareStatement(
                """
                update canvas_resource
                set owner_node_id = ?, resource_index = 0
                where canvas_id = ? and id = ? and owner_node_id is null
                """)) {
      detach.setObject(1, canvasId);
      detach.setObject(2, oldResourceId);
      detach.setObject(3, nodeId);
      assertEquals(1, detach.executeUpdate());
      attach.setObject(1, nodeId);
      attach.setObject(2, canvasId);
      attach.setObject(3, newResourceId);
      assertEquals(1, attach.executeUpdate());
    }
  }

  private void transitionRunSucceeded(Connection connection, UUID nodeId, UUID requestId)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            update canvas_function_run
            set status = 'SUCCEEDED',
                state_json = cast(? as jsonb),
                error = null,
                updated_at = clock_timestamp()
            where node_id = ? and request_id = ? and status = 'RUNNING'
            """)) {
      statement.setString(1, "{\"stage\":\"SUCCEEDED\"}");
      statement.setObject(2, nodeId);
      statement.setObject(3, requestId);
      assertEquals(1, statement.executeUpdate());
    }
  }

  private void incrementVersion(Connection connection, UUID canvasId) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            update canvas_document
            set version = version + 1,
                updated_at = greatest(updated_at, clock_timestamp())
            where id = ?
            """)) {
      statement.setObject(1, canvasId);
      assertEquals(1, statement.executeUpdate());
    }
  }

  private boolean awaitBlockedQuery(String table) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() - deadline < 0L) {
      try (Connection connection = PostgresSchemaSupport.newConnection();
          PreparedStatement statement =
              connection.prepareStatement(
                  """
                  select exists (
                      select 1
                      from pg_stat_activity
                      where datname = current_database()
                        and pid <> pg_backend_pid()
                        and state = 'active'
                        and wait_event_type = 'Lock'
                        and query ilike ?
                  )
                  """)) {
        statement.setString(1, "%" + table + "%");
        try (ResultSet resultSet = statement.executeQuery()) {
          resultSet.next();
          if (resultSet.getBoolean(1)) {
            return true;
          }
        }
      }
      Thread.sleep(10L);
    }
    return false;
  }

  private void execute(Connection connection, String sql) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.execute();
    }
  }

  private Object race(
      CountDownLatch start, CanvasDocument canvas, CanvasCommand.CreateTextNode command)
      throws InterruptedException {
    start.await();
    try {
      return commandService.applyCommands(canvas.id(), 0, UUID.randomUUID(), List.of(command));
    } catch (RuntimeException ex) {
      return ex;
    }
  }

  private long count(String table, UUID canvasId) throws Exception {
    try (Connection connection = PostgresSchemaSupport.newConnection();
        PreparedStatement statement =
            connection.prepareStatement("select count(*) from " + table + " where canvas_id = ?")) {
      statement.setObject(1, canvasId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    }
  }

  private record CanvasLinkKey(UUID sourceNodeId, UUID targetNodeId) {}

  @TestConfiguration
  @ConditionalOnProperty(name = "kk-studio.test.durable-canvas-service", havingValue = "true")
  static class TestAdapterConfiguration {

    @Bean
    CanvasFunctionAdapter durableCanvasTestAdapter() {
      List<CanvasFunctionModel> models = List.of(model("m"), model("model-a"), model("model-b"));
      return new CanvasFunctionAdapter() {
        @Override
        public List<CanvasFunctionModel> models() {
          return models;
        }

        @Override
        public boolean enabled() {
          return true;
        }

        @Override
        public String unavailableReason() {
          return null;
        }

        @Override
        public void preflight(CanvasFunctionFrozenRun run) {}

        @Override
        public List<UUID> execute(
            CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
          throw new UnsupportedOperationException();
        }
      };
    }

    private static CanvasFunctionModel model(String key) {
      return new CanvasFunctionModel(key, key, CanvasResourceKind.IMAGE, NO_REFERENCES, List.of());
    }
  }
}
