package fun.fengwk.kkstudio.platform.plugin.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;

import java.util.concurrent.atomic.AtomicReference;

class CredentialRejectionTest {
  @Test
  void rejectsExactSnapshotAndOnlyHandlesRuntimeFailure() {
    // 不重新解析凭据；CAS 未命中和次要故障不替换认证结果，Error 必须传播。
    PluginCredentialSnapshot snapshot = new PluginCredentialSnapshot("test", null, null, 1, "{}");
    AtomicReference<Throwable> nextFailure = new AtomicReference<>();
    PluginCredentialAccess access =
        new PluginCredentialAccess() {
          @Override
          public PluginCredentialSnapshot resolve() {
            throw new AssertionError("must not resolve");
          }

          @Override
          public boolean rejectUsed(PluginCredentialSnapshot used, String message) {
            assertSame(snapshot, used);
            assertEquals("rejected", message);
            if (nextFailure.get() instanceof RuntimeException runtime) {
              throw runtime;
            }
            if (nextFailure.get() instanceof Error error) {
              throw error;
            }
            return false;
          }
        };
    AtomicReference<RuntimeException> reported = new AtomicReference<>();
    CredentialRejection.rejectUsed(access, snapshot, "rejected", reported::set);
    RuntimeException failure = new IllegalStateException("private");
    nextFailure.set(failure);
    CredentialRejection.rejectUsed(access, snapshot, "rejected", reported::set);
    assertSame(failure, reported.get());
    Error fatal = new AssertionError();
    nextFailure.set(fatal);
    assertSame(
        fatal,
        assertThrows(
            Error.class,
            () -> CredentialRejection.rejectUsed(access, snapshot, "rejected", reported::set)));
  }
}
