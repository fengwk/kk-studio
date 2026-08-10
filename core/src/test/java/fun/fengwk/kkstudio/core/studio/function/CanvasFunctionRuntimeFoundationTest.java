package fun.fengwk.kkstudio.core.studio.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasNodeResourceMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasNodeResourceDO;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.studio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.studio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.studio.canvas.CanvasResource;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.studio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionParameterDefinition;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionRunException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** PostgreSQL 上验证 start freeze、每节点串行、CAS terminal apply 与 graphRevision 隔离。 */
@Import(CanvasFunctionRuntimeFoundationTest.TestAdapterConfiguration.class)
class CanvasFunctionRuntimeFoundationTest extends PostgresSpringTestSupport {

  private static final CanvasTransform TRANSFORM = new CanvasTransform(0, 0, 100, 80);
  private static final CanvasFunctionModel MODEL =
      new CanvasFunctionModel(
          "test-image",
          "Test Image",
          CanvasResourceKind.IMAGE,
          new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()),
          List.of(
              CanvasFunctionParameterDefinition.enumParameter(
                  "ratio", "Ratio", false, "AUTO", List.of("AUTO", "1:1"))));

  @Autowired private CanvasCommandService commandService;
  @Autowired private CanvasQueryService queryService;
  @Autowired private CanvasResourceRepository resourceRepository;
  @Autowired private CanvasNodeResourceMapper nodeResourceMapper;
  @Autowired private CanvasFunctionRunRepository runRepository;
  @Autowired private CanvasFunctionRunTransactions transactions;
  @Autowired private CanvasFunctionRunStateCodec stateCodec;
  @Autowired private CanvasFunctionModelRegistry registry;

  /** 相同 requestId exact replay；不同并发 request 只有一个成功；终态允许新 request 覆盖。 */
  @Test
  void serializesStartsAndKeepsRequestIdIdempotent() throws Exception {
    CanvasDocument canvas = commandService.createCanvas("starts");
    long nodeId = createFunction(canvas, "function", config("prompt", List.of(), "{}"));

    CanvasFunctionRun first = transactions.start(canvas.id(), nodeId, "request-1").run();
    CanvasFunctionRun replay = transactions.start(canvas.id(), nodeId, "request-1").run();
    assertEquals(first.requestId(), replay.requestId());
    assertEquals(target(first), target(replay));
    assertThrows(
        CanvasFunctionRunException.class,
        () -> transactions.start(canvas.id(), nodeId, "request-2"));

    CanvasFunctionRun cancelled = transactions.cancel(canvas.id(), nodeId, "request-1");
    assertEquals(CanvasFunctionRunStatus.CANCELLED, cancelled.status());
    CanvasFunctionRun terminalReplay = transactions.start(canvas.id(), nodeId, "request-1").run();
    assertEquals(CanvasFunctionRunStatus.CANCELLED, terminalReplay.status());
    assertEquals(target(cancelled), target(terminalReplay));
    CanvasFunctionRun replacement = transactions.start(canvas.id(), nodeId, "request-2").run();
    assertNotEquals(target(first), target(replacement));

    CanvasDocument raceCanvas = commandService.createCanvas("race");
    long raceNode = createFunction(raceCanvas, "race-function", config("prompt", List.of(), "{}"));
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Object> left =
          executor.submit(() -> raceStart(start, raceCanvas.id(), raceNode, "left"));
      Future<Object> right =
          executor.submit(() -> raceStart(start, raceCanvas.id(), raceNode, "right"));
      start.countDown();
      List<Object> results = List.of(left.get(), right.get());
      assertEquals(
          1L, results.stream().filter(CanvasFunctionStartResult.class::isInstance).count());
      assertEquals(
          1L, results.stream().filter(CanvasFunctionRunException.class::isInstance).count());
    } finally {
      executor.shutdownNow();
    }
  }

  /** start 重新验证 Link/same-canvas/index/kind/count/params，并执行 adapter-specific preflight。 */
  @Test
  void rejectsInvalidReferencesParametersAndPreflight() {
    CanvasDocument canvas = commandService.createCanvas("validation");
    CanvasResource first = addResource(canvas.id(), CanvasResourceKind.IMAGE, "first.png");
    CanvasResource second = addResource(canvas.id(), CanvasResourceKind.IMAGE, "second.png");
    CanvasResource audio = addResource(canvas.id(), CanvasResourceKind.AUDIO, "sound.mp3");
    long firstSource = createResourceNode(canvas, "first-source", first.id());
    long secondSource = createResourceNode(canvas, "second-source", second.id());
    long audioSource = createResourceNode(canvas, "audio-source", audio.id());

    long noLink =
        createFunction(
            canvas, "no-link", config("prompt", List.of(reference(firstSource, 0)), "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.start(canvas.id(), noLink, "missing-link"));

    long badIndex =
        createFunction(
            canvas, "bad-index", config("prompt", List.of(reference(firstSource, 1)), "{}"));
    link(canvas, firstSource, badIndex);
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.start(canvas.id(), badIndex, "bad-index"));

    long wrongKind =
        createFunction(
            canvas, "wrong-kind", config("prompt", List.of(reference(audioSource, 0)), "{}"));
    link(canvas, audioSource, wrongKind);
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.start(canvas.id(), wrongKind, "wrong-kind"));

    long tooMany =
        createFunction(
            canvas,
            "too-many",
            config("prompt", List.of(reference(firstSource, 0), reference(secondSource, 0)), "{}"));
    link(canvas, firstSource, tooMany);
    link(canvas, secondSource, tooMany);
    assertThrows(
        IllegalArgumentException.class, () -> transactions.start(canvas.id(), tooMany, "too-many"));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            createFunction(
                canvas, "bad-parameters", config("prompt", List.of(), "{\"ratio\":\"wide\"}")));

    CanvasDocument foreignCanvas = commandService.createCanvas("foreign");
    CanvasResource foreignResource =
        addResource(foreignCanvas.id(), CanvasResourceKind.IMAGE, "foreign.png");
    long foreignSource = createResourceNode(foreignCanvas, "foreign-source", foreignResource.id());
    long wrongCanvas =
        createFunction(
            canvas, "wrong-canvas", config("prompt", List.of(reference(foreignSource, 0)), "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.start(canvas.id(), wrongCanvas, "wrong-canvas"));

    long preflight = createFunction(canvas, "preflight-reject", config("prompt", List.of(), "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.start(canvas.id(), preflight, "preflight"));
  }

  /** checkpoint/cancel/失败/成功/迟到结果/节点删除均只按 node+request+RUNNING CAS 收敛。 */
  @Test
  void terminalApplyPreservesResourcesAndNeverChangesGraphRevision() {
    CanvasDocument canvas = commandService.createCanvas("terminal");
    long nodeId = createFunction(canvas, "output", config("prompt", List.of(), "{}"));
    CanvasResource oldResource = addResource(canvas.id(), CanvasResourceKind.IMAGE, "old.png");
    attach(canvas.id(), nodeId, oldResource.id());
    long revision = snapshot(canvas.id()).document().graphRevision();

    CanvasFunctionRun first = transactions.start(canvas.id(), nodeId, "first").run();
    assertTrue(
        runRepository.checkpoint(
            nodeId,
            "first",
            stateCodec.encode(
                stateCodec.checkpoint(decode(first), "SUBMITTING", Map.of("jobId", "job-1"))),
            "SUBMITTING",
            Instant.now()));
    assertFalse(
        runRepository.checkpoint(nodeId, "other", first.stateJson(), first.stage(), Instant.now()));
    CanvasFunctionFrozenRun firstFrozen = decode(runRepository.findByNodeId(nodeId).orElseThrow());
    transactions.cancel(canvas.id(), nodeId, "first");
    assertFalse(
        runRepository.checkpoint(nodeId, "first", first.stateJson(), first.stage(), Instant.now()));
    CanvasResource late = addTarget(firstFrozen);
    assertFalse(transactions.completeSuccess(firstFrozen, List.of(late.id())));
    assertEquals(List.of(oldResource.id()), resourceIds(canvas.id(), nodeId));

    CanvasFunctionRun failed = transactions.start(canvas.id(), nodeId, "failed").run();
    assertTrue(transactions.failIfRunning(nodeId, "failed", "safe failure"));
    assertEquals(
        CanvasFunctionRunStatus.FAILED, runRepository.findByNodeId(nodeId).orElseThrow().status());
    assertEquals(List.of(oldResource.id()), resourceIds(canvas.id(), nodeId));
    assertFalse(transactions.completeSuccess(decode(failed), List.of(target(failed))));

    CanvasFunctionRun success = transactions.start(canvas.id(), nodeId, "success").run();
    CanvasFunctionFrozenRun successFrozen = decode(success);
    CanvasResource generated = addTarget(successFrozen);
    assertTrue(transactions.completeSuccess(successFrozen, List.of(generated.id())));
    assertEquals(List.of(generated.id()), resourceIds(canvas.id(), nodeId));
    assertEquals(revision, snapshot(canvas.id()).document().graphRevision());
    assertEquals(
        CanvasFunctionRunStatus.SUCCEEDED,
        runRepository.findByNodeId(nodeId).orElseThrow().status());

    CanvasFunctionRun deleted = transactions.start(canvas.id(), nodeId, "deleted").run();
    CanvasFunctionFrozenRun deletedFrozen = decode(deleted);
    apply(canvas, new CanvasCommand.DeleteNode(nodeId));
    CanvasResource orphan = addTarget(deletedFrozen);
    assertFalse(transactions.completeSuccess(deletedFrozen, List.of(orphan.id())));
    assertTrue(runRepository.findByNodeId(nodeId).isEmpty());
  }

  /** manifest 按首次 mention 去重并冻结具体 Resource，不受后续 Link 删除影响。 */
  @Test
  void freezesDeduplicatedManifestBeforeGraphChanges() {
    CanvasDocument canvas = commandService.createCanvas("freeze");
    CanvasResource resource = addResource(canvas.id(), CanvasResourceKind.IMAGE, "source.png");
    long source = createResourceNode(canvas, "source", resource.id());
    long target =
        createFunction(
            canvas,
            "target",
            config(
                "prompt",
                List.of(reference(source, 0), reference(source, 0)),
                "{\"ratio\":\"1:1\"}"));
    link(canvas, source, target);

    CanvasFunctionFrozenRun frozen =
        decode(transactions.start(canvas.id(), target, "freeze").run());
    assertEquals(1, frozen.manifest().size());
    assertEquals(resource.id(), frozen.manifest().get(0).resourceId());
    apply(canvas, new CanvasCommand.DeleteLink(source, target));
    assertEquals(resource.id(), frozen.manifest().get(0).resourceId());
  }

  private Object raceStart(CountDownLatch start, long canvasId, long nodeId, String requestId)
      throws InterruptedException {
    start.await();
    try {
      return transactions.start(canvasId, nodeId, requestId);
    } catch (RuntimeException exception) {
      return exception;
    }
  }

  private long createFunction(CanvasDocument canvas, String name, String config) {
    CanvasSnapshot snapshot =
        apply(canvas, new CanvasCommand.CreateFunctionNode(name, MODEL.key(), config, TRANSFORM));
    return snapshot.nodes().stream()
        .filter(node -> node.name().equals(name))
        .findFirst()
        .orElseThrow()
        .id();
  }

  private long createResourceNode(CanvasDocument canvas, String name, long resourceId) {
    CanvasSnapshot snapshot =
        apply(canvas, new CanvasCommand.CreateResourceNode(name, List.of(resourceId), TRANSFORM));
    return snapshot.nodes().stream()
        .filter(node -> node.name().equals(name))
        .findFirst()
        .orElseThrow()
        .id();
  }

  private void link(CanvasDocument canvas, long source, long target) {
    apply(canvas, new CanvasCommand.CreateLink(source, target));
  }

  private CanvasSnapshot apply(CanvasDocument canvas, CanvasCommand... commands) {
    long revision = snapshot(canvas.id()).document().graphRevision();
    return commandService.applyCommands(
        canvas.id(), revision, UUID.randomUUID().toString(), List.of(commands));
  }

  private CanvasSnapshot snapshot(long canvasId) {
    return queryService.findSnapshot(canvasId).orElseThrow();
  }

  private CanvasResource addResource(long canvasId, CanvasResourceKind kind, String name) {
    long id = PostgresSchemaSupport.FIXTURE_IDS.incrementAndGet();
    CanvasResource resource =
        new CanvasResource(
            id,
            canvasId,
            kind,
            kind == CanvasResourceKind.AUDIO ? "audio/mpeg" : "image/png",
            name,
            3L,
            null,
            "{}",
            Instant.now());
    resourceRepository.add(resource);
    return resource;
  }

  private CanvasResource addTarget(CanvasFunctionFrozenRun frozen) {
    CanvasResource resource =
        new CanvasResource(
            frozen.targetResourceId(),
            frozen.canvasId(),
            frozen.model().outputKind(),
            "image/png",
            frozen.outputName(),
            3L,
            null,
            "{\"width\":1,\"height\":1}",
            Instant.now());
    resourceRepository.add(resource);
    return resource;
  }

  private void attach(long canvasId, long nodeId, long resourceId) {
    CanvasNodeResourceDO relation = new CanvasNodeResourceDO();
    relation.setCanvasId(canvasId);
    relation.setNodeId(nodeId);
    relation.setResourceIndex(0);
    relation.setResourceId(resourceId);
    nodeResourceMapper.insert(relation);
  }

  private List<Long> resourceIds(long canvasId, long nodeId) {
    return snapshot(canvasId).nodes().stream()
        .filter(node -> node.id() == nodeId)
        .findFirst()
        .orElseThrow()
        .resources()
        .stream()
        .map(CanvasResource::id)
        .toList();
  }

  private CanvasFunctionFrozenRun decode(CanvasFunctionRun run) {
    return stateCodec.decode(
        run.stateJson(), registry.require(stateCodec.modelKey(run.stateJson())).model());
  }

  private long target(CanvasFunctionRun run) {
    return decode(run).targetResourceId();
  }

  private static String reference(long nodeId, int index) {
    return "{\"type\":\"REFERENCE\",\"nodeId\":\"" + nodeId + "\",\"index\":" + index + "}";
  }

  private static String config(String text, List<String> references, String parameters) {
    StringBuilder segments =
        new StringBuilder("[{\"type\":\"TEXT\",\"text\":\"").append(text).append("\"}");
    for (String reference : references) {
      segments.append(',').append(reference);
    }
    return "{\"prompt\":{\"segments\":"
        + segments.append(']')
        + "},\"parameters\":"
        + parameters
        + "}";
  }

  @TestConfiguration
  static class TestAdapterConfiguration {

    @Bean
    CanvasFunctionAdapter testCanvasFunctionAdapter() {
      return new CanvasFunctionAdapter() {
        @Override
        public List<CanvasFunctionModel> models() {
          return List.of(MODEL);
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
        public void preflight(CanvasFunctionFrozenRun run) {
          if (run.nodeName().equals("preflight-reject")) {
            throw new IllegalArgumentException("adapter preflight rejected");
          }
        }

        @Override
        public List<Long> execute(
            CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
          throw new UnsupportedOperationException();
        }
      };
    }
  }
}
