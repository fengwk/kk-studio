package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

/**
 * 意图：关停失败的聚合算法本身有确定结论——首个 Error 保持对象身份、后到 Error 提升到普通异常之上、其余失败作为 suppressed 附加且 同一对象不重复记录；checked
 * 失败按固定安全消息包装；没有失败时不伪造聚合结果。
 */
class DaemonShutdownFailuresTest {

  /** 尚未记录失败时既不报失败，也不伪造可抛出的聚合结果。 */
  @Test
  void reportsNoFailureBeforeAnythingIsRecorded() {
    DaemonShutdownFailures failures = new DaemonShutdownFailures();

    assertFalse(failures.hasFailure());
    assertNull(failures.error());
    assertThrows(IllegalStateException.class, failures::exception);
  }

  /** 空失败被忽略：既不进入聚合，也不产生安全日志。 */
  @Test
  void ignoresANullFailure() {
    DaemonShutdownFailures failures = new DaemonShutdownFailures();

    failures.record("connection close", null);

    assertFalse(failures.hasFailure());
  }

  /** 首个 Error 保持对象身份：先到的普通异常与后到的 Error 都作为 suppressed 附加而不是被丢弃。 */
  @Test
  void keepsTheFirstErrorIdentityAndSuppressesLaterFailures() {
    DaemonShutdownFailures failures = new DaemonShutdownFailures();
    IllegalStateException ordinary = new IllegalStateException("ordinary");
    AssertionError first = new AssertionError("first");
    OutOfMemoryError later = new OutOfMemoryError("later");

    failures.record("connection close", ordinary);
    failures.record("transport close", first);
    failures.record("invocation termination", later);

    assertSame(first, failures.error());
    assertEquals(List.of(ordinary, later), List.of(first.getSuppressed()));
  }

  /** 同一个失败对象重复出现只记录一次：绝不把它自己加为自己的 suppressed。 */
  @Test
  void recordsTheSameFailureObjectOnlyOnce() {
    DaemonShutdownFailures failures = new DaemonShutdownFailures();
    AssertionError failure = new AssertionError("only once");

    failures.record("connection close", failure);
    failures.record("transport close", failure);

    assertSame(failure, failures.error());
    assertEquals(0, failure.getSuppressed().length);
  }

  /** checked 失败不能直接抛出：按固定安全消息包装并保留原失败对象身份。 */
  @Test
  void wrapsCheckedFailuresWithTheFixedSafeMessage() {
    DaemonShutdownFailures failures = new DaemonShutdownFailures();
    IOException checked = new IOException("checked");

    failures.record("capability resource close", checked);

    RuntimeException wrapped = failures.exception();
    assertSame(checked, wrapped.getCause());
    assertEquals(DaemonShutdownFailures.SHUTDOWN_FAILURE_REASON, wrapped.getMessage());
  }

  /** 普通异常主因保持对象身份，不被包装。 */
  @Test
  void keepsTheRuntimeExceptionIdentity() {
    DaemonShutdownFailures failures = new DaemonShutdownFailures();
    IllegalStateException ordinary = new IllegalStateException("ordinary");

    failures.record("transport close", ordinary);

    assertNull(failures.error());
    assertSame(ordinary, failures.exception());
  }
}
