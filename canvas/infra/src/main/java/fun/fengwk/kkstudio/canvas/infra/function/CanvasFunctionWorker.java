package fun.fengwk.kkstudio.canvas.infra.function;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasResourceMaterializer;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunStateCodecPort;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionUnknownException;
import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasFunctionWorkStore;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 执行一个 frozen run，checkpoint 与 terminal 都通过短事务 CAS + canvas version/patch 收敛。 */
@Component
@Slf4j
public class CanvasFunctionWorker {

  private static final String PUBLIC_FAILURE = "Function execution failed";
  private static final String AMBIGUOUS_SUBMISSION =
      "external submission outcome is unknown; manual verification required";

  private final CanvasFunctionRunRepository runRepository;
  private final CanvasFunctionCatalog catalog;
  private final CanvasFunctionRunStateCodecPort stateCodec;
  private final CanvasFunctionRunTransactions transactions;
  private final CanvasFunctionBlobAccess blobAccess;
  private final ObjectProvider<CanvasResourceMaterializer> materializers;
  private final CanvasFunctionWorkStore workStore;
  private final CanvasFunctionRuntimeProperties properties;
  private final Clock clock;
  private final ScheduledExecutorService heartbeatScheduler;

  public CanvasFunctionWorker(
      CanvasFunctionRunRepository runRepository,
      CanvasFunctionCatalog catalog,
      CanvasFunctionRunStateCodecPort stateCodec,
      CanvasFunctionRunTransactions transactions,
      CanvasFunctionBlobAccess blobAccess,
      ObjectProvider<CanvasResourceMaterializer> materializers,
      CanvasFunctionWorkStore workStore,
      CanvasFunctionRuntimeProperties properties,
      Clock clock,
      @Qualifier("canvasFunctionHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler) {
    this.runRepository = runRepository;
    this.catalog = catalog;
    this.stateCodec = stateCodec;
    this.transactions = transactions;
    this.blobAccess = blobAccess;
    this.materializers = materializers;
    this.workStore = workStore;
    this.properties = properties;
    this.clock = Clock.tick(clock, Duration.ofMillis(1));
    this.heartbeatScheduler = heartbeatScheduler;
  }

  public void run(ClaimedRun claim) {
    AtomicBoolean ownershipLost = new AtomicBoolean();
    long heartbeatMillis = properties.getHeartbeatIntervalMillis();
    ScheduledFuture<?> heartbeat =
        heartbeatScheduler.scheduleWithFixedDelay(
            () -> renew(claim, ownershipLost),
            heartbeatMillis,
            heartbeatMillis,
            TimeUnit.MILLISECONDS);
    try {
      CanvasFunctionCatalog.RegisteredFunction registered =
          catalog.require(stateCodec.functionName(claim.run().stateJson()));
      registered.requireAvailable();
      CanvasFunctionFrozenRun frozen =
          stateCodec.decode(claim.run().stateJson(), registered.function());
      if (frozen.submitState() == CanvasFunctionSubmitState.SUBMITTING) {
        // 提交意图已持久化但结果不明：绝不重新提交，退出自动调度等待人工核查。
        transactions.markUnknown(
            claim.nodeId(), claim.requestId().toString(), claim.leaseToken(), AMBIGUOUS_SUBMISSION);
        return;
      }
      CanvasFunctionExecutionContextImpl context =
          new CanvasFunctionExecutionContextImpl(
              runRepository,
              transactions,
              blobAccess,
              materializers,
              clock,
              claim,
              ownershipLost,
              frozen);
      if (!frozen.submitted()) {
        frozen = transactions.beginSubmit(frozen, claim.leaseToken());
        context.replace(frozen);
        registered.adapter().submit(context, frozen);
        requireOwnership(ownershipLost);
        frozen = transactions.confirmSubmitted(context.currentRun(), claim.leaseToken());
        context.replace(frozen);
      }
      List<UUID> result = List.copyOf(registered.adapter().execute(context, frozen));
      requireOwnership(ownershipLost);
      if (!result.equals(List.of(frozen.targetResourceId()))) {
        throw new IllegalArgumentException(
            "adapter result must equal the preallocated target Resource id");
      }
      transactions.completeSuccess(context.currentRun(), claim.leaseToken(), result);
    } catch (CanvasFunctionUnknownException unknown) {
      log.warn(
          "Canvas Function reported unknown outcome nodeId={} requestId={}",
          claim.nodeId(),
          claim.requestId());
      if (!ownershipLost.get()) {
        transactions.markUnknown(
            claim.nodeId(), claim.requestId().toString(), claim.leaseToken(), unknown.getMessage());
      }
    } catch (CanvasFunctionInternalCancellation cancellation) {
      log.debug(
          "Canvas Function worker stopped after CAS cancellation nodeId={} requestId={}",
          claim.nodeId(),
          claim.requestId());
    } catch (Throwable error) {
      log.warn(
          "Canvas Function worker failed nodeId={} requestId={} type={}",
          claim.nodeId(),
          claim.requestId(),
          error.getClass().getSimpleName());
      if (!ownershipLost.get()) {
        transactions.failIfRunning(
            claim.nodeId(), claim.requestId().toString(), claim.leaseToken(), PUBLIC_FAILURE);
      }
    } finally {
      heartbeat.cancel(false);
    }
  }

  private void renew(ClaimedRun claim, AtomicBoolean ownershipLost) {
    if (ownershipLost.get()) {
      return;
    }
    try {
      if (!workStore.renew(
          claim, clock.instant(), Duration.ofMillis(properties.getLeaseDurationMillis()))) {
        ownershipLost.set(true);
      }
    } catch (RuntimeException error) {
      ownershipLost.set(true);
      log.warn(
          "Canvas Function heartbeat failed nodeId={} requestId={} type={}",
          claim.nodeId(),
          claim.requestId(),
          error.getClass().getSimpleName());
    }
  }

  private static void requireOwnership(AtomicBoolean ownershipLost) {
    if (ownershipLost.get()) {
      throw new CanvasFunctionInternalCancellation("Canvas Function lease was lost");
    }
  }
}
