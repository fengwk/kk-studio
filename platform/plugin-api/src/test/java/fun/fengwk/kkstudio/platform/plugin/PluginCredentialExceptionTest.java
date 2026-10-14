package fun.fengwk.kkstudio.platform.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Plugin 凭据异常族保留消息、原因与刷新失败的类型化语义。 */
class PluginCredentialExceptionTest {

  private static final RuntimeException CAUSE = new IllegalStateException("cause");

  @Test
  void everyCredentialExceptionPreservesMessageAndCause() {
    List<PluginCredentialException> exceptions =
        List.of(
            new PluginCredentialException("msg", CAUSE),
            new PluginAuthRejectedException("msg", CAUSE),
            new PluginRenewalNotSentException("msg", CAUSE),
            new PluginKeyUnavailableException("msg", CAUSE),
            new PluginCredentialIntegrityException("msg", CAUSE));

    for (PluginCredentialException exception : exceptions) {
      assertEquals("msg", exception.getMessage());
      assertSame(CAUSE, exception.getCause());
    }
  }

  @Test
  void messageOnlyConstructorsKeepNullCause() {
    List<PluginCredentialException> exceptions =
        List.of(
            new PluginCredentialException("msg"),
            new PluginAuthRejectedException("msg"),
            new PluginRenewalNotSentException("msg"),
            new PluginKeyUnavailableException("msg"),
            new PluginCredentialIntegrityException("msg"));

    for (PluginCredentialException exception : exceptions) {
      assertEquals("msg", exception.getMessage());
      assertNull(exception.getCause());
    }
  }

  @Test
  void unavailableExceptionCarriesDeterministicReason() {
    PluginCredentialUnavailableException exception =
        new PluginCredentialUnavailableException(
            PluginCredentialUnavailableReason.EXPIRED, "expired");

    assertEquals(PluginCredentialUnavailableReason.EXPIRED, exception.reason());
    assertEquals("expired", exception.getMessage());
    assertNull(exception.getCause());

    PluginCredentialUnavailableException withCause =
        new PluginCredentialUnavailableException(
            PluginCredentialUnavailableReason.REFRESH_UNCERTAIN, "uncertain", CAUSE);

    assertEquals(PluginCredentialUnavailableReason.REFRESH_UNCERTAIN, withCause.reason());
    assertSame(CAUSE, withCause.getCause());
  }

  @Test
  void unavailableExceptionRequiresReason() {
    assertThrows(
        NullPointerException.class,
        () -> new PluginCredentialUnavailableException(null, "missing reason"));
  }
}
