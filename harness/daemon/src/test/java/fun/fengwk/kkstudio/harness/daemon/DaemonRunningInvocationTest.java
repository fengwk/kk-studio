package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.daemon.journal.DaemonTerminalMessage;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTerminationCause;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** 意图：一次本地执行的终态只可能被抢占一次，且终态一旦抢占，之后的资源上传控制帧绝不能越过后到达 wire。 */
class DaemonRunningInvocationTest {

  /** 未建立执行句柄（无收尾预算）时由运行时立即提交自己的终态；重复抢占与控制帧都被确定拒绝。 */
  @Test
  void claimsTheTerminalOnceAndClosesControlFrames() {
    DaemonRunningInvocation invocation = new DaemonRunningInvocation("call-1");
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      DaemonTerminalMessage message =
          new DaemonTerminalMessage(DaemonMessageType.FAILED, "{\"message\":\"timed out\"}");
      AtomicBoolean deferredFallback = new AtomicBoolean();

      assertTrue(
          invocation.claimTerminal(
              scheduler,
              EnvironmentCapabilityTerminationCause.TIMED_OUT,
              message,
              () -> deferredFallback.set(true)),
          "without a declared termination grace the runtime must submit its own terminal now");
      assertTrue(invocation.isTerminal());
      assertFalse(
          invocation.claimTerminal(scheduler, null, message, () -> deferredFallback.set(true)),
          "terminal-once: a later arbitration must be refused");
      assertFalse(
          deferredFallback.get(), "no delayed terminal window may be opened without a handle");

      AtomicBoolean controlSent = new AtomicBoolean();
      assertFalse(
          invocation.trySendControl(
              () -> {
                controlSent.set(true);
                return true;
              }),
          "a control frame must never cross an already claimed terminal");
      assertFalse(controlSent.get(), "the control frame action must not even run");
    } finally {
      scheduler.shutdownNow();
    }
  }
}
