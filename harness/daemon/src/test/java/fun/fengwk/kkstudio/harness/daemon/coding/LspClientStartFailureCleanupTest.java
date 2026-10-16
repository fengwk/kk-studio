package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
