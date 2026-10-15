package fun.fengwk.kkstudio.canvas.infra.function;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasResourceMaterializer;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalogSource;
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
  private final CanvasFunctionCatalogSource catalogSource;
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
      CanvasFunctionCatalogSource catalogSource,
      CanvasFunctionRunStateCodecPort stateCodec,
      CanvasFunctionRunTransactions transactions,
      CanvasFunctionBlobAccess blobAccess,
      ObjectProvider<CanvasResourceMaterializer> materializers,
      CanvasFunctionWorkStore workStore,
      CanvasFunctionRuntimeProperties properties,
      Clock clock,
      @Qualifier("canvasFunctionHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler) {
    this.runRepository = runRepository;
    this.catalogSource = catalogSource;
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
      CanvasFunctionFrozenRun frozen = stateCodec.decode(claim.run().stateJson());
      if (frozen.submitState() == CanvasFunctionSubmitState.SUBMITTING) {
        // 提交意图已持久化但结果不明：绝不重新提交，退出自动调度等待人工核查。
        transactions.markUnknown(
            claim.nodeId(), claim.requestId().toString(), claim.leaseToken(), AMBIGUOUS_SUBMISSION);
        return;
      }
      execute(claim, ownershipLost, frozen);
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
    } catch (Exception error) {
      // 只把可恢复的 Exception 收敛为有界公开失败；Error（如 OOM）必须原样向调用方传播，
      // 绝不能伪装成业务 FAILED 掩盖进程级故障。
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

  /**
   * 按冻结绑定的目录 epoch 取得租用，并持有整个 submit/query/checkpoint/complete 期间。
   *
   * <p>恢复路径只能通过 {@link CanvasFunctionCatalogSource.Lease#require} 取回同一 epoch、同一完整定义与来源的 adapter 实例；
   * epoch 已不可用或绑定漂移都会拒绝执行，而不是按函数名换用当前版本。
   */
  private void execute(
      ClaimedRun claim, AtomicBoolean ownershipLost, CanvasFunctionFrozenRun frozen) {
    try (CanvasFunctionCatalogSource.Lease lease =
        catalogSource.acquire(frozen.binding().catalogEpoch())) {
      CanvasFunctionCatalog.RegisteredFunction registered = lease.require(frozen.binding());
      registered.requireAvailable();
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
      CanvasFunctionFrozenRun current = frozen;
      if (!current.submitted()) {
        current = transactions.beginSubmit(current, claim.leaseToken());
        context.replace(current);
        registered.adapter().submit(context, current);
        requireOwnership(ownershipLost);
        current = transactions.confirmSubmitted(context.currentRun(), claim.leaseToken());
        context.replace(current);
      }
      List<UUID> result = List.copyOf(registered.adapter().execute(context, current));
      requireOwnership(ownershipLost);
      if (!result.equals(current.outputResourceIds())) {
        throw new IllegalArgumentException(
            "adapter result must equal the frozen output plan, in order");
      }
      transactions.completeSuccess(context.currentRun(), claim.leaseToken(), result);
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
