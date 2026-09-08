package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.net.URI;

/** 验证 Anthropic 端点规范化与安全性校验。 */
class AnthropicEndpointsTest {

  @Test
  void resolvesStandardEndpointWithoutTrailingSlash() {
    URI uri = AnthropicEndpoints.resolveMessagesUri("https://api.anthropic.com/v1");
    assertEquals("https://api.anthropic.com/v1/messages", uri.toString());
  }

  @Test
  void resolvesStandardEndpointWithTrailingSlash() {
    URI uri = AnthropicEndpoints.resolveMessagesUri("https://api.anthropic.com/v1/");
    assertEquals("https://api.anthropic.com/v1/messages", uri.toString());
  }

  @Test
  void resolvesHttpEndpointWithCustomPort() {
    URI uri = AnthropicEndpoints.resolveMessagesUri("http://127.0.0.1:8080");
    assertEquals("http://127.0.0.1:8080/messages", uri.toString());
  }

  @Test
  void resolvesHttpEndpointWithIpv6HostAndPort() {
    URI uri = AnthropicEndpoints.resolveMessagesUri("http://[::1]:8443/v1");
    assertEquals("http://[::1]:8443/v1/messages", uri.toString());
  }

  @Test
  void rejectsBlankOrNullEndpoint() {
    assertThrows(IllegalArgumentException.class, () -> AnthropicEndpoints.resolveMessagesUri(null));
    assertThrows(
        IllegalArgumentException.class, () -> AnthropicEndpoints.resolveMessagesUri("   "));
  }

  @Test
  void rejectsInvalidScheme() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AnthropicEndpoints.resolveMessagesUri("ftp://api.anthropic.com"));
  }

  @Test
  void rejectsUserInfo() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AnthropicEndpoints.resolveMessagesUri("https://user:pass@api.anthropic.com/v1"));
  }

  @Test
  void rejectsQuery() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AnthropicEndpoints.resolveMessagesUri("https://api.anthropic.com/v1?key=val"));
  }

  @Test
  void rejectsFragment() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AnthropicEndpoints.resolveMessagesUri("https://api.anthropic.com/v1#section"));
  }

  @Test
  void rejectsMalformedUriSyntax() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> AnthropicEndpoints.resolveMessagesUri("://invalid"));
    assertNull(ex.getCause());
  }

  @Test
  void rejectsMissingHost() {
    assertThrows(
        IllegalArgumentException.class, () -> AnthropicEndpoints.resolveMessagesUri("http://"));
    assertThrows(
        IllegalArgumentException.class,
        () -> AnthropicEndpoints.resolveMessagesUri("http:///path"));
  }

  @Test
  void resolvesHostWithoutPath() {
    URI uri = AnthropicEndpoints.resolveMessagesUri("https://api.anthropic.com");
    assertEquals("https://api.anthropic.com/messages", uri.toString());
  }
}
