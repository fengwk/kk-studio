package fun.fengwk.kkstudio.harness.builtin.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.port.store.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.ProcessorExceptions;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Model 流式 checkpoint 的唯一落库点：在调用方事务内锁定 claimed MODEL Work 与 ModelInvocation，重校验 RUNNING + attempt
 * 后写回累计 text/thinking。
 *
 * <p>围栏失败（行缺失、claim 丢失、attempt 或状态不匹配）返回 {@code false}，由调用方 abandon；SQL 异常同样收敛为 {@code false}
 * 并记录，绝不写任何部分状态。事务锁序固定为 ModelInvocation -&gt; claimed Work，不取 Thread 或执行树锁。
 */
@Slf4j
final class ModelCheckpointCommitter {

  private final HarnessStore store;
  private final BuiltinStore builtinStore;
  private final ClaimedWork claim;
  private final UUID invocationId;
  private final int attempt;

  ModelCheckpointCommitter(
      HarnessStore store,
      BuiltinStore builtinStore,
      ClaimedWork claim,
      UUID invocationId,
      int attempt) {
    this.store = Objects.requireNonNull(store, "store");
    this.builtinStore = Objects.requireNonNull(builtinStore, "builtinStore");
    this.claim = Objects.requireNonNull(claim, "claim");
    this.invocationId = Objects.requireNonNull(invocationId, "invocationId");
    this.attempt = attempt;
  }

  /** 提交当前 attempt 的安全 checkpoint；所有权、状态或 attempt 围栏失败返回 false。 */
  boolean persist(StreamCheckpoint checkpoint) {
    try {
      return Boolean.TRUE.equals(
          store.transaction(
              tx -> {
                Instant now = tx.now();
                BuiltinStore.Transaction builtin = builtinStore.in(tx);
                ModelInvocation model = builtin.lockModelInvocation(invocationId).orElse(null);
                if (model == null || tx.work().lockClaimed(claim).isEmpty()) {
                  return false;
                }
                if (model.status() != ModelInvocationStatus.RUNNING || model.attempt() != attempt) {
                  return false;
                }
                builtin.updateModelInvocation(model.checkpoint(checkpoint, now));
                return true;
              }));
    } catch (RuntimeException failure) {
      log.warn(
          "cannot persist model checkpoint for {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      return false;
    }
  }
}
