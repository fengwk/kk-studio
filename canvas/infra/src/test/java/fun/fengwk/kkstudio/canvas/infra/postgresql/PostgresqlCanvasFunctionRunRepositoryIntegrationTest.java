package fun.fengwk.kkstudio.canvas.infra.postgresql;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasStore.NodeRecord;
import fun.fengwk.kkstudio.canvas.infra.function.ClaimedRun;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * {@code canvas_function_run} 存在的 canvas 级 lease 归属查询契约。
 *
 * <p>归属以 {@code canvas_id} 连接与数据库 {@code clock_timestamp()} 判定：错误 canvas/request/token、 已终态与已过期的
 * lease 都必须为 false，不能只看 node_id 命中。
 */
class PostgresqlCanvasFunctionRunRepositoryIntegrationTest extends PostgresCanvasInfraTestSupport {

  private static final Duration LEASE = Duration.ofSeconds(30);

  @Autowired private CanvasFunctionRunRepository runs;
  @Autowired private CanvasFunctionWorkStore workStore;

  /** 只有「同 canvas + 同 request + 同 lease token + 未过期 + RUNNING」的当前 lease 才被承认。 */
  @Test
  void ownsRunningRequestRequiresLiveLeaseInTheSameCanvas() {
    UUID canvasId = addDocument();
    NodeRecord node = addNode(canvasId, true);
    UUID requestId = UUID.randomUUID();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    runs.insertReady(ready(node.id(), requestId, now.minusSeconds(1)));
    ClaimedRun claim = workStore.claimNext(now, LEASE, "owner").orElseThrow();

    assertTrue(runs.ownsRunningRequest(canvasId, node.id(), requestId, claim.leaseToken()));
    assertFalse(
        runs.ownsRunningRequest(UUID.randomUUID(), node.id(), requestId, claim.leaseToken()));
    assertFalse(
        runs.ownsRunningRequest(canvasId, node.id(), UUID.randomUUID(), claim.leaseToken()));
    assertFalse(runs.ownsRunningRequest(canvasId, node.id(), requestId, "other-token"));

    CanvasFunctionRun running = runs.findByNodeId(node.id()).orElseThrow();
    CanvasFunctionRun succeeded =
        new CanvasFunctionRun(
            running.nodeId(),
            running.requestId(),
            CanvasFunctionRunStatus.SUCCEEDED,
            running.attempt(),
            null,
            null,
            null,
            running.stage(),
            running.stateJson(),
            null,
            now,
            running.createdAt());
    // 终态释放 lease 后，同一 token 不再拥有任何在途请求。
    assertTrue(runs.transitionTerminal(succeeded, claim.leaseToken()));
    assertFalse(runs.ownsRunningRequest(canvasId, node.id(), requestId, claim.leaseToken()));
  }

  private static CanvasFunctionRun ready(UUID nodeId, UUID requestId, Instant availableAt) {
    return new CanvasFunctionRun(
        nodeId,
        requestId,
        CanvasFunctionRunStatus.READY,
        0,
        availableAt,
        null,
        null,
        "QUEUED",
        minimalRunState("QUEUED"),
        null,
        availableAt,
        availableAt);
  }
}
