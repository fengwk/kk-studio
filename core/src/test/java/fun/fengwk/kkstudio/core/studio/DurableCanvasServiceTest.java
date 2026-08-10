package fun.fengwk.kkstudio.core.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.studio.canvas.CanvasConflictException;
import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.studio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.studio.canvas.CanvasResource;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.studio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.studio.canvas.CanvasUpload;
import fun.fengwk.kkstudio.studio.canvas.CanvasUploadRepository;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionReferencePolicy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** PostgreSQL 上的 Canvas v1 command、CAS、幂等和聚合快照覆盖。 */
@Import(DurableCanvasServiceTest.TestAdapterConfiguration.class)
public class DurableCanvasServiceTest extends PostgresSpringTestSupport {

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
  @Autowired private CanvasUploadRepository uploadRepository;

  @Test
  void createListSnapshotAndTextReplacementKeepResourcesImmutable() {
    CanvasDocument canvas = commandService.createCanvas(" board ");
    assertEquals("board", canvas.title());
    assertEquals(0L, canvas.graphRevision());
    assertTrue(queryService.listDocuments().stream().anyMatch(item -> item.id() == canvas.id()));

    CanvasSnapshot created =
        apply(canvas, 0, "text-create", new CanvasCommand.CreateTextNode("note", "old", T));
    CanvasResourceNode node = created.nodes().get(0);
    CanvasResource oldResource = node.resources().get(0);

    CanvasSnapshot updated =
        apply(
            canvas, 1, "text-update", new CanvasCommand.UpdateTextNode(node.id(), "new markdown"));
    CanvasResource newResource = updated.nodes().get(0).resources().get(0);

    assertNotEquals(oldResource.id(), newResource.id());
    assertEquals("new markdown", newResource.textContent());
    assertEquals(
        "old",
        resourceRepository.findById(canvas.id(), oldResource.id()).orElseThrow().textContent());
    assertEquals(2L, updated.document().graphRevision());
  }

  @Test
  void everyNodeFunctionTransformAndLinkCommandIsApplied() {
    CanvasDocument canvas = commandService.createCanvas("commands");
    CanvasSnapshot first =
        apply(
            canvas,
            0,
            "create-nodes",
            new CanvasCommand.CreateTextNode("source", "x", T),
            new CanvasCommand.CreateFunctionNode(
                "target", "model-a", FUNCTION_CONFIG, new CanvasTransform(200, 20, 120, 90)));
    long sourceId = first.nodes().get(0).id();
    long targetId = first.nodes().get(1).id();

    CanvasSnapshot changed =
        apply(
            canvas,
            1,
            "edit-graph",
            new CanvasCommand.UpdateFunction(targetId, "model-b", FUNCTION_CONFIG),
            new CanvasCommand.RenameNode(sourceId, "renamed"),
            new CanvasCommand.UpdateNodeTransforms(
                List.of(
                    new CanvasCommand.NodeTransformUpdate(
                        sourceId, new CanvasTransform(30, 40, 130, 100)))),
            new CanvasCommand.CreateLink(sourceId, targetId));
    assertEquals("renamed", changed.nodes().get(0).name());
    assertEquals(new CanvasTransform(30, 40, 130, 100), changed.nodes().get(0).transform());
    assertEquals("model-b", changed.nodes().get(1).function().modelKey());
    assertEquals(1, changed.links().size());

    CanvasSnapshot withoutLink =
        apply(canvas, 2, "delete-link", new CanvasCommand.DeleteLink(sourceId, targetId));
    assertTrue(withoutLink.links().isEmpty());

    CanvasSnapshot withoutSource =
        apply(canvas, 3, "delete-node", new CanvasCommand.DeleteNode(sourceId));
    assertEquals(1, withoutSource.nodes().size());
    assertTrue(
        resourceRepository
            .findById(canvas.id(), first.nodes().get(0).resources().get(0).id())
            .isPresent());
  }

  @Test
  void resourceNodePreservesRequestedOrderAndRejectsCrossCanvasOrMixedKinds() {
    CanvasDocument firstCanvas = commandService.createCanvas("first");
    CanvasDocument secondCanvas = commandService.createCanvas("second");
    CanvasResource first = addResource(firstCanvas.id(), CanvasResourceKind.IMAGE, "a");
    CanvasResource second = addResource(firstCanvas.id(), CanvasResourceKind.IMAGE, "b");
    CanvasResource audio = addResource(firstCanvas.id(), CanvasResourceKind.AUDIO, "c");
    CanvasResource foreign = addResource(secondCanvas.id(), CanvasResourceKind.IMAGE, "d");

    CanvasSnapshot snapshot =
        apply(
            firstCanvas,
            0,
            "ordered",
            new CanvasCommand.CreateResourceNode("images", List.of(second.id(), first.id()), T));
    assertEquals(
        List.of(second.id(), first.id()),
        snapshot.nodes().get(0).resources().stream().map(CanvasResource::id).toList());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            apply(
                firstCanvas,
                1,
                "mixed",
                new CanvasCommand.CreateResourceNode("mixed", List.of(first.id(), audio.id()), T)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            apply(
                firstCanvas,
                1,
                "foreign",
                new CanvasCommand.CreateResourceNode("foreign", List.of(foreign.id()), T)));
  }

  @Test
  void linkRequiresFunctionTargetAndSameCanvas() {
    CanvasDocument firstCanvas = commandService.createCanvas("first");
    CanvasDocument secondCanvas = commandService.createCanvas("second");
    CanvasSnapshot first =
        apply(
            firstCanvas,
            0,
            "nodes",
            new CanvasCommand.CreateTextNode("a", "a", T),
            new CanvasCommand.CreateTextNode("b", "b", T),
            new CanvasCommand.CreateFunctionNode("fn", "m", FUNCTION_CONFIG, T));
    CanvasSnapshot second =
        apply(
            secondCanvas,
            0,
            "foreign-node",
            new CanvasCommand.CreateFunctionNode("foreign", "m", FUNCTION_CONFIG, T));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            apply(
                firstCanvas,
                1,
                "ordinary-target",
                new CanvasCommand.CreateLink(
                    first.nodes().get(0).id(), first.nodes().get(1).id())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            apply(
                firstCanvas,
                1,
                "foreign-target",
                new CanvasCommand.CreateLink(
                    first.nodes().get(0).id(), second.nodes().get(0).id())));

    CanvasSnapshot linked =
        apply(
            firstCanvas,
            1,
            "valid-link",
            new CanvasCommand.CreateLink(first.nodes().get(0).id(), first.nodes().get(2).id()));
    assertEquals(1, linked.links().size());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            apply(
                firstCanvas,
                2,
                "long-model",
                new CanvasCommand.CreateFunctionNode("long", "m".repeat(257), FUNCTION_CONFIG, T)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            apply(
                firstCanvas,
                2,
                "unknown-model",
                new CanvasCommand.CreateFunctionNode("unknown", "unknown", FUNCTION_CONFIG, T)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            apply(
                firstCanvas,
                2,
                "unknown-parameter",
                new CanvasCommand.CreateFunctionNode(
                    "invalid",
                    "m",
                    """
                    {"prompt":{"segments":[{"type":"TEXT","text":"prompt"}]},"parameters":{"extra":"x"}}
                    """,
                    T)));
  }

  @Test
  void groupMoveUsesDeltaAndUngroupDeleteOnlyChangeMembership() {
    CanvasDocument canvas = commandService.createCanvas("groups");
    CanvasSnapshot nodes =
        apply(
            canvas,
            0,
            "nodes",
            new CanvasCommand.CreateTextNode("a", "a", new CanvasTransform(10, 20, 100, 80)),
            new CanvasCommand.CreateTextNode("b", "b", new CanvasTransform(30, 50, 110, 90)));
    long firstId = nodes.nodes().get(0).id();
    long secondId = nodes.nodes().get(1).id();

    CanvasSnapshot grouped =
        apply(
            canvas,
            1,
            "group",
            new CanvasCommand.CreateGroup(
                "G", new CanvasTransform(0, 0, 300, 200), List.of(firstId, secondId)));
    long groupId = grouped.groups().get(0).id();
    CanvasSnapshot moved =
        apply(canvas, 2, "move-group", new CanvasCommand.MoveGroup(groupId, 100, 50));
    assertEquals(new CanvasTransform(100, 50, 300, 200), moved.groups().get(0).transform());
    assertEquals(new CanvasTransform(110, 70, 100, 80), moved.nodes().get(0).transform());
    assertEquals(new CanvasTransform(130, 100, 110, 90), moved.nodes().get(1).transform());

    CanvasSnapshot ungrouped =
        apply(canvas, 3, "ungroup-one", new CanvasCommand.Ungroup(groupId, List.of(firstId)));
    assertNull(ungrouped.nodes().get(0).groupId());
    assertEquals(groupId, ungrouped.nodes().get(1).groupId());

    CanvasSnapshot deleted =
        apply(canvas, 4, "delete-group", new CanvasCommand.DeleteGroup(groupId));
    assertTrue(deleted.groups().isEmpty());
    assertNull(deleted.nodes().get(1).groupId());
  }

  @Test
  void revisionCasIdempotencyAndAtomicRollbackAreEnforced() throws Exception {
    CanvasDocument canvas = commandService.createCanvas("atomic");
    List<CanvasCommand> batch = List.of(new CanvasCommand.CreateTextNode("a", "x", T));
    CanvasSnapshot first = commandService.applyCommands(canvas.id(), 0, "same", batch);
    CanvasSnapshot replay = commandService.applyCommands(canvas.id(), 0, "same", batch);
    assertEquals(first.document().graphRevision(), replay.document().graphRevision());

    CanvasConflictException hashConflict =
        assertThrows(
            CanvasConflictException.class,
            () ->
                commandService.applyCommands(
                    canvas.id(),
                    0,
                    "same",
                    List.of(new CanvasCommand.CreateTextNode("other", "y", T))));
    assertEquals(CanvasConflictException.Reason.IDEMPOTENCY_CONFLICT, hashConflict.reason());

    CanvasConflictException revisionConflict =
        assertThrows(
            CanvasConflictException.class,
            () ->
                commandService.applyCommands(
                    canvas.id(),
                    0,
                    "stale",
                    List.of(new CanvasCommand.CreateTextNode("b", "x", T))));
    assertEquals(CanvasConflictException.Reason.REVISION_CONFLICT, revisionConflict.reason());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            commandService.applyCommands(
                canvas.id(),
                1,
                "rollback",
                List.of(
                    new CanvasCommand.CreateTextNode("temporary", "x", T),
                    new CanvasCommand.RenameNode(999_999, "missing"))));
    CanvasSnapshot afterRollback = queryService.findSnapshot(canvas.id()).orElseThrow();
    assertEquals(1L, afterRollback.document().graphRevision());
    assertEquals(1, afterRollback.nodes().size());
    assertEquals(1L, count("canvas_resource", canvas.id()));
  }

  @Test
  void normalizedNameIsUniqueAndConcurrentRaceLeavesOneWinner() throws Exception {
    CanvasDocument canvas = commandService.createCanvas("names");
    apply(canvas, 0, "first", new CanvasCommand.CreateTextNode("Ｎｏｄｅ", "x", T));
    assertThrows(
        IllegalArgumentException.class,
        () -> apply(canvas, 1, "duplicate", new CanvasCommand.CreateTextNode(" node ", "y", T)));

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
                      "race-a",
                      new CanvasCommand.CreateTextNode("Race", "a", T)));
      Future<Object> second =
          executor.submit(
              () ->
                  race(
                      start,
                      raceCanvas,
                      "race-b",
                      new CanvasCommand.CreateTextNode("race", "b", T)));
      start.countDown();
      Object firstResult = first.get();
      Object secondResult = second.get();
      long successes =
          List.of(firstResult, secondResult).stream()
              .filter(CanvasSnapshot.class::isInstance)
              .count();
      assertEquals(1L, successes);
      assertTrue(
          List.of(firstResult, secondResult).stream()
              .anyMatch(
                  result ->
                      result instanceof IllegalArgumentException
                          || result instanceof CanvasConflictException));
      CanvasSnapshot snapshot = queryService.findSnapshot(raceCanvas.id()).orElseThrow();
      assertEquals(1, snapshot.nodes().size());
      assertEquals(1L, snapshot.document().graphRevision());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void functionRunRepositoryFeedsSnapshotWithoutChangingGraphRevision() {
    CanvasDocument canvas = commandService.createCanvas("run");
    CanvasSnapshot created =
        apply(canvas, 0, "fn", new CanvasCommand.CreateFunctionNode("fn", "m", FUNCTION_CONFIG, T));
    long nodeId = created.nodes().get(0).id();
    runRepository.insertRunning(
        new CanvasFunctionRun(
            nodeId,
            "request-1",
            CanvasFunctionRunStatus.RUNNING,
            "QUEUED",
            "{\"stage\":\"QUEUED\"}",
            null,
            Instant.now()));

    CanvasSnapshot snapshot = queryService.findSnapshot(canvas.id()).orElseThrow();
    assertNotNull(snapshot.nodes().get(0).run());
    assertEquals(CanvasFunctionRunStatus.RUNNING, snapshot.nodes().get(0).run().status());
    assertEquals(1L, snapshot.document().graphRevision());

    CanvasDocument ordinaryCanvas = commandService.createCanvas("ordinary-run");
    CanvasSnapshot ordinary =
        apply(ordinaryCanvas, 0, "ordinary", new CanvasCommand.CreateTextNode("text", "x", T));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runRepository.insertRunning(
                new CanvasFunctionRun(
                    ordinary.nodes().get(0).id(),
                    "invalid",
                    CanvasFunctionRunStatus.RUNNING,
                    "QUEUED",
                    "{\"stage\":\"QUEUED\"}",
                    null,
                    Instant.now())));
  }

  @Test
  void uploadRepositoryPersistsOnlyUploadFactsWithoutGraphApi() {
    assertTrue(queryService.findSnapshot(-1).isEmpty());
    assertTrue(queryService.findSnapshot(Long.MAX_VALUE).isEmpty());
    CanvasDocument canvas = commandService.createCanvas("upload");
    Instant createdAt = Instant.parse("2026-08-10T00:00:00Z");
    CanvasUpload upload =
        new CanvasUpload(
            PostgresSchemaSupport.FIXTURE_IDS.incrementAndGet(),
            canvas.id(),
            CanvasResourceKind.VIDEO,
            "clip.mp4",
            "video/mp4",
            123,
            createdAt.plusSeconds(300),
            createdAt);
    uploadRepository.add(upload);

    assertEquals(upload, uploadRepository.findById(canvas.id(), upload.id()).orElseThrow());
    assertEquals(
        upload, uploadRepository.findByIdForUpdate(canvas.id(), upload.id()).orElseThrow());
    assertTrue(uploadRepository.delete(canvas.id(), upload.id()));
    assertTrue(uploadRepository.findById(canvas.id(), upload.id()).isEmpty());
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            uploadRepository.add(
                new CanvasUpload(
                    PostgresSchemaSupport.FIXTURE_IDS.incrementAndGet(),
                    canvas.id(),
                    CanvasResourceKind.TEXT,
                    "note.md",
                    "text/markdown",
                    1,
                    createdAt.plusSeconds(300),
                    createdAt)));
    assertEquals(
        0L, queryService.findSnapshot(canvas.id()).orElseThrow().document().graphRevision());
  }

  private CanvasSnapshot apply(
      CanvasDocument canvas, long revision, String commandId, CanvasCommand... commands) {
    return commandService.applyCommands(canvas.id(), revision, commandId, List.of(commands));
  }

  private CanvasResource addResource(long canvasId, CanvasResourceKind kind, String name) {
    long id = PostgresSchemaSupport.FIXTURE_IDS.incrementAndGet();
    String text = kind == CanvasResourceKind.TEXT ? name : null;
    CanvasResource resource =
        new CanvasResource(
            id,
            canvasId,
            kind,
            kind == CanvasResourceKind.IMAGE ? "image/png" : "audio/mpeg",
            name,
            name.getBytes(StandardCharsets.UTF_8).length,
            text,
            "{}",
            Instant.now());
    resourceRepository.add(resource);
    return resource;
  }

  private Object race(
      CountDownLatch start,
      CanvasDocument canvas,
      String commandId,
      CanvasCommand.CreateTextNode command)
      throws InterruptedException {
    start.await();
    try {
      return apply(canvas, 0, commandId, command);
    } catch (RuntimeException ex) {
      return ex;
    }
  }

  private long count(String table, long canvasId) throws Exception {
    try (Connection connection = PostgresSchemaSupport.newConnection();
        PreparedStatement statement =
            connection.prepareStatement("select count(*) from " + table + " where canvas_id = ?")) {
      statement.setLong(1, canvasId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    }
  }

  @TestConfiguration
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
        public List<Long> execute(
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
