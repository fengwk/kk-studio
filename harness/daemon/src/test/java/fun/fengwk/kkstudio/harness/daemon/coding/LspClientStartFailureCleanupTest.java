package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 启动失败清理的异常优先级；使用合成 cleanup 失败，不将它当成真实 OS 故障证据。 */
class LspClientStartFailureCleanupTest {

  /** 原始 Error 的身份不可被收尾失败取代，收尾失败只作为 suppressed 附着。 */
  @Test
  void originalErrorKeepsIdentityAndCleanupIsSuppressed() {
    Error original = new AssertionError("startup");
    RuntimeException cleanup = new IllegalStateException("cleanup");

    Throwable merged =
        LspClient.cleanupStartFailure(
            original,
            () -> {
              throw cleanup;
            });

    assertSame(original, merged);
    assertEquals(List.of(cleanup), List.of(merged.getSuppressed()));
  }

  /** 后到的 Error 优先于普通异常，并把普通异常作为 suppressed 保留。 */
  @Test
  void cleanupErrorOutranksTheOriginalException() {
    RuntimeException original = new ToolServiceFailureException("startup");
    Error cleanup = new AssertionError("cleanup");

    Throwable merged =
        LspClient.cleanupStartFailure(
            original,
            () -> {
              throw cleanup;
            });

    assertSame(cleanup, merged);
    assertEquals(List.of(original), List.of(merged.getSuppressed()));
  }

  /** 两个普通失败保持原始身份；清理重新抛出同一个失败时不自抑制。 */
  @Test
  void ordinaryFailuresAggregateWithoutSelfSuppression() {
    RuntimeException original = new ToolServiceFailureException("startup");
    RuntimeException cleanup = new IllegalStateException("cleanup");

    Throwable merged =
        LspClient.cleanupStartFailure(
            original,
            () -> {
              throw cleanup;
            });
    assertSame(original, merged);
    assertEquals(List.of(cleanup), List.of(merged.getSuppressed()));

    assertSame(
        original,
        LspClient.cleanupStartFailure(
            original,
            () -> {
              throw original;
            }));
    assertEquals(List.of(cleanup), List.of(original.getSuppressed()));
  }

  /** 两个 Error 保留首个 Error，普通清理成功也必须真实执行。 */
  @Test
  void keepsTheFirstErrorAndExecutesSuccessfulCleanup() {
    Error first = new AssertionError("first");
    Error second = new AssertionError("second");
    assertSame(
        first,
        LspClient.cleanupStartFailure(
            first,
            () -> {
              throw second;
            }));
    assertEquals(List.of(second), List.of(first.getSuppressed()));
    AtomicBoolean executed = new AtomicBoolean();
    assertSame(first, LspClient.cleanupStartFailure(first, () -> executed.set(true)));
    assertTrue(executed.get());
  }

  /** 合并两次收尾失败：{@code null} 不参与合并、也不允许触发空指针；同一个失败不得自抑制。 */
  @Test
  void mergeFailureToleratesNullsAndKeepsIdentity() {
    RuntimeException stop = new ToolServiceFailureException("stop");
    Error fatal = new AssertionError("fatal");

    assertNull(LspClient.mergeFailure(null, null), "两次收尾都成功时结论就是没有问题");
    assertSame(stop, LspClient.mergeFailure(stop, null), "空的后续失败不得改变主失败");
    assertEquals(0, stop.getSuppressed().length, "空的后续失败不得附着任何东西");
    assertSame(fatal, LspClient.mergeFailure(null, fatal), "只有后续失败时它就是结论");

    assertSame(fatal, LspClient.mergeFailure(stop, fatal), "Error 优先于普通安全失败");
    assertEquals(List.of(stop), List.of(fatal.getSuppressed()));
    assertSame(stop, LspClient.mergeFailure(stop, stop), "同一个失败不得自抑制");
    assertEquals(0, stop.getSuppressed().length);
  }
}
