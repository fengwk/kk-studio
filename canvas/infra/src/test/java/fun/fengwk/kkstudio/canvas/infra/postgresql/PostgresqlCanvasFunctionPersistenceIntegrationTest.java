package fun.fengwk.kkstudio.canvas.infra.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePin;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePin.Role;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasStore.NodeRecord;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Function Run 与 Resource Pin 的真实 PostgreSQL 生命周期测试。
 *
 * <p>测试通过 Core facts 驱动两个 repository，不依赖 Platform runtime 或其 fixture。
 */
class PostgresqlCanvasFunctionPersistenceIntegrationTest extends PostgresCanvasInfraTestSupport {

  private static final Instant STARTED_AT = Instant.parse("2026-02-03T04:05:06.123Z");

  @Autowired private CanvasFunctionRunRepository runs;
  @Autowired private CanvasFunctionResourcePinRepository pins;

  /** RUNNING 的插入、checkpoint、terminal、terminal replacement 与清理必须执行状态/请求 CAS。 */
  @Test
  void functionRunLifecycleUsesStatusAndRequestCas() {
    UUID canvasId = addDocument();
    NodeRecord node = addNode(canvasId, true);
    UUID requestId = UUID.randomUUID();
    CanvasFunctionRun running =
        run(node.id(), requestId, CanvasFunctionRunStatus.RUNNING, "QUEUED");

    assertTrue(runs.findByNodeId(node.id()).isEmpty());
    assertEquals(
        Boolean.TRUE,
        transactions.execute(status -> runs.findByNodeIdForUpdate(node.id()).isEmpty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runs.insertRunning(
                run(node.id(), requestId, CanvasFunctionRunStatus.SUCCEEDED, "DONE")));

    runs.insertRunning(running);
    assertRun(runs.findByNodeId(node.id()).orElseThrow(), requestId, "QUEUED");
    assertRun(
        transactions.execute(status -> runs.findByNodeIdForUpdate(node.id()).orElseThrow()),
        requestId,
        "QUEUED");
    assertEquals(
        List.of(node.id()),
        runs.findByCanvasId(canvasId).stream().map(CanvasFunctionRun::nodeId).toList());
    assertEquals(
        List.of(node.id()), runs.findRunning().stream().map(CanvasFunctionRun::nodeId).toList());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            runs.checkpoint(
                node.id(), requestId, state("GENERATING"), "MISMATCH", STARTED_AT.plusSeconds(1)));
    assertFalse(
        runs.checkpoint(
            node.id(),
            UUID.randomUUID(),
            state("GENERATING"),
            "GENERATING",
            STARTED_AT.plusSeconds(1)));
    assertTrue(
        runs.checkpoint(
            node.id(), requestId, state("GENERATING"), "GENERATING", STARTED_AT.plusSeconds(1)));
    assertRun(runs.findByNodeId(node.id()).orElseThrow(), requestId, "GENERATING");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            runs.transitionTerminal(
                run(node.id(), requestId, CanvasFunctionRunStatus.RUNNING, "GENERATING")));
    assertFalse(
        runs.transitionTerminal(
            run(node.id(), UUID.randomUUID(), CanvasFunctionRunStatus.FAILED, "FAILED")));
    assertTrue(
        runs.transitionTerminal(
            run(node.id(), requestId, CanvasFunctionRunStatus.SUCCEEDED, "DONE")));
    assertTrue(runs.findRunning().isEmpty());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            runs.replaceTerminalWithRunning(
                run(node.id(), requestId, CanvasFunctionRunStatus.FAILED, "FAILED")));
    UUID replacementRequest = UUID.randomUUID();
    assertTrue(
        runs.replaceTerminalWithRunning(
            run(node.id(), replacementRequest, CanvasFunctionRunStatus.RUNNING, "QUEUED")));
    assertFalse(
        runs.replaceTerminalWithRunning(
            run(node.id(), UUID.randomUUID(), CanvasFunctionRunStatus.RUNNING, "QUEUED")));
    assertRun(runs.findByNodeId(node.id()).orElseThrow(), replacementRequest, "QUEUED");

    assertTrue(runs.deleteByNodeId(node.id()));
    assertTrue(runs.deleteByNodeId(node.id()), "repository delete is idempotent");
    NodeRecord secondNode = addNode(canvasId, true);
    runs.insertRunning(
        run(secondNode.id(), UUID.randomUUID(), CanvasFunctionRunStatus.RUNNING, "QUEUED"));
    assertTrue(runs.deleteByCanvasId(canvasId));
    assertTrue(runs.deleteByCanvasId(canvasId), "repository aggregate delete is idempotent");
    assertTrue(runs.findByCanvasId(canvasId).isEmpty());
  }

  /** Pin 必须可按 run/node/resource 读取，并且 running OUTPUT 查询必须与 Function Run 状态联结。 */
  @Test
  void resourcePinsFollowRunAndAggregateLifecycle() {
    UUID canvasId = addDocument();
    NodeRecord firstNode = addNode(canvasId, true);
    NodeRecord secondNode = addNode(canvasId, true);
    UUID requestId = UUID.randomUUID();
    UUID inputResourceId = UUID.randomUUID();
    UUID outputResourceId = UUID.randomUUID();
    CanvasFunctionResourcePin input =
        new CanvasFunctionResourcePin(
            canvasId, firstNode.id(), requestId, inputResourceId, Role.INPUT);
    CanvasFunctionResourcePin output =
        new CanvasFunctionResourcePin(
            canvasId, firstNode.id(), requestId, outputResourceId, Role.OUTPUT);

    pins.addAll(List.of());
    runs.insertRunning(run(firstNode.id(), requestId, CanvasFunctionRunStatus.RUNNING, "QUEUED"));
    pins.addAll(List.of(input, output));
    assertEquals(List.of(input, output), pins.findByRun(canvasId, firstNode.id(), requestId));
    assertEquals(List.of(input, output), pins.findByNode(canvasId, firstNode.id()));
    assertEquals(1, pins.countByResource(canvasId, inputResourceId));
    assertEquals(List.of(output), pins.findRunningOutputPins(canvasId, outputResourceId));
    assertTrue(pins.findRunningOutputPins(canvasId, inputResourceId).isEmpty());

    assertTrue(
        runs.transitionTerminal(
            run(firstNode.id(), requestId, CanvasFunctionRunStatus.SUCCEEDED, "DONE")));
    assertTrue(pins.findRunningOutputPins(canvasId, outputResourceId).isEmpty());
    assertTrue(pins.deleteByRun(canvasId, firstNode.id(), requestId));
    assertTrue(
        pins.deleteByRun(canvasId, firstNode.id(), requestId), "pin run delete is idempotent");

    UUID firstReplacementRequest = UUID.randomUUID();
    UUID secondRequest = UUID.randomUUID();
    pins.addAll(
        List.of(
            new CanvasFunctionResourcePin(
                canvasId, firstNode.id(), firstReplacementRequest, UUID.randomUUID(), Role.INPUT),
            new CanvasFunctionResourcePin(
                canvasId, firstNode.id(), firstReplacementRequest, UUID.randomUUID(), Role.OUTPUT),
            new CanvasFunctionResourcePin(
                canvasId, secondNode.id(), secondRequest, UUID.randomUUID(), Role.INPUT)));
    assertEquals(2, pins.deleteByNode(canvasId, firstNode.id()));
    assertEquals(0, pins.deleteByNode(canvasId, firstNode.id()));
    assertEquals(1, pins.deleteByCanvas(canvasId));
    assertEquals(0, pins.deleteByCanvas(canvasId));
    assertTrue(pins.findByNode(canvasId, secondNode.id()).isEmpty());
  }

  private static CanvasFunctionRun run(
      UUID nodeId, UUID requestId, CanvasFunctionRunStatus status, String stage) {
    return new CanvasFunctionRun(
        nodeId,
        requestId,
        status,
        stage,
        state(stage),
        status == CanvasFunctionRunStatus.FAILED ? "failed" : null,
        STARTED_AT);
  }

  private static String state(String stage) {
    return "{\"stage\":\"" + stage + "\"}";
  }

  private static void assertRun(CanvasFunctionRun actual, UUID requestId, String stage) {
    assertEquals(requestId, actual.requestId());
    assertEquals(stage, actual.stage());
  }
}
