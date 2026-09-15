package fun.fengwk.kkstudio.harness.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;

/**
 * 测试意图：验证应用层最终 UTF-8 请求体字节上限守卫的边界语义——恰好在限内通过、恰好超一字节即拒绝，且拒绝时错误消息 只包含上限数值而不回显请求体内容；同时确认共享默认实例使用 192
 * MiB 应用上限。
 */
class RequestBodySizeGuardTest {

  @Test
  void defaultGuardUsesApplicationWideLimit() {
    assertEquals(192L * 1024 * 1024, RequestBodySizeGuard.MAX_REQUEST_BODY_BYTES);
    assertEquals(192L * 1024 * 1024, RequestBodySizeGuard.DEFAULT.limitBytes());
    assertEquals(
        RequestBodySizeGuard.MAX_REQUEST_BODY_BYTES, RequestBodySizeGuard.DEFAULT.limitBytes());
  }

  @Test
  void acceptsBodyExactlyAtLimitAndRejectsOneByteOver() {
    RequestBodySizeGuard guard = new RequestBodySizeGuard(8);

    guard.enforce(new byte[7]);
    guard.enforce(new byte[8]);

    ProviderException exception =
        assertThrows(ProviderException.class, () -> guard.enforce(new byte[9]));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exception.kind());
    assertEquals("request body exceeds 8 bytes limit", exception.getMessage());
  }

  @Test
  void rejectsNonPositiveLimit() {
    assertThrows(IllegalArgumentException.class, () -> new RequestBodySizeGuard(0));
    assertThrows(IllegalArgumentException.class, () -> new RequestBodySizeGuard(-1));
  }

  @Test
  void rejectsNullBody() {
    assertThrows(NullPointerException.class, () -> new RequestBodySizeGuard(16).enforce(null));
  }

  @Test
  void guardInstancesAreIndependent() {
    // 可注入的小阈值绝不影响共享默认实例，避免测试阈值泄漏到生产路径
    RequestBodySizeGuard tiny = new RequestBodySizeGuard(1);
    assertNotNull(RequestBodySizeGuard.DEFAULT);
    assertEquals(192L * 1024 * 1024, RequestBodySizeGuard.DEFAULT.limitBytes());
    assertEquals(1, tiny.limitBytes());
  }
}
