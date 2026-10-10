package fun.fengwk.kkstudio.platform.environment.cluster;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 意图：内部认证必须把签名绑定到目标实例、方法、canonical path、时间戳与 nonce，并在任何不符时 fail-closed；重放、缺失、过期与超界头都不得 通过，秘密绝不回显。
 */
class HmacClusterInternalAuthenticationTest {

  private static final UUID NODE_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID NODE_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String SECRET = "test-cluster-secret-0123456789abcdef";
  private static final Instant NOW = Instant.parse("2026-08-30T10:00:00Z");
  private static final Duration WINDOW = Duration.ofSeconds(30);
  private static final String PATH =
      "/internal/environment-ide/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/views";

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final HmacClusterInternalAuthentication authentication =
      new HmacClusterInternalAuthentication(SECRET, NODE_B, clock, WINDOW, 8);

  private ClusterIdeOwnerRoute routeTo(UUID nodeInstanceId) {
    return ClusterIdeOwnerRoute.remote(nodeInstanceId, URI.create("http://node.internal:8080"));
  }

  @Test
  void signsAndVerifiesAgainstTheTargetInstance() {
    Map<String, String> headers = authentication.sign("POST", PATH, routeTo(NODE_B));

    assertEquals(NODE_B.toString(), headers.get(HmacClusterInternalAuthentication.HEADER_TARGET));
    assertDoesNotThrow(() -> authentication.verify("POST", PATH, headers));
  }

  @Test
  void rejectsWhenTheRequestTargetsAnotherInstance() {
    Map<String, String> headers = authentication.sign("POST", PATH, routeTo(NODE_A));

    assertThrows(
        ClusterInternalAuthenticationException.class,
        () -> authentication.verify("POST", PATH, headers));
  }

  @Test
  void rejectsWhenMethodDiffersFromTheSignedRequest() {
    Map<String, String> headers = authentication.sign("POST", PATH, routeTo(NODE_B));

    assertThrows(
        ClusterInternalAuthenticationException.class,
        () -> authentication.verify("DELETE", PATH, headers));
  }

  @Test
  void rejectsWhenPathDiffersFromTheSignedRequest() {
    Map<String, String> headers = authentication.sign("POST", PATH, routeTo(NODE_B));

    assertThrows(
        ClusterInternalAuthenticationException.class,
        () -> authentication.verify("POST", PATH + "/other", headers));
  }

  @Test
  void rejectsReplayedNonce() {
    Map<String, String> headers = authentication.sign("POST", PATH, routeTo(NODE_B));
    authentication.verify("POST", PATH, headers);

    assertThrows(
        ClusterInternalAuthenticationException.class,
        () -> authentication.verify("POST", PATH, headers));
  }

  @Test
  void rejectsWhenAuthenticationHeaderIsMissing() {
    Map<String, String> headers = new HashMap<>(authentication.sign("POST", PATH, routeTo(NODE_B)));
    headers.remove(HmacClusterInternalAuthentication.HEADER_SIGNATURE);

    assertThrows(
        ClusterInternalAuthenticationException.class,
        () -> authentication.verify("POST", PATH, headers));
  }

  @Test
  void rejectsStaleTimestampOutsideTheWindow() {
    Map<String, String> headers = authentication.sign("POST", PATH, routeTo(NODE_B));
    HmacClusterInternalAuthentication laterVerifier =
        new HmacClusterInternalAuthentication(
            SECRET, NODE_B, Clock.fixed(NOW.plusSeconds(31), ZoneOffset.UTC), WINDOW, 8);

    assertThrows(
        ClusterInternalAuthenticationException.class,
        () -> laterVerifier.verify("POST", PATH, headers));
  }

  @Test
  void rejectsOversizedHeader() {
    Map<String, String> headers = new HashMap<>(authentication.sign("POST", PATH, routeTo(NODE_B)));
    headers.put(HmacClusterInternalAuthentication.HEADER_SIGNATURE, "a".repeat(4096));

    assertThrows(
        ClusterInternalAuthenticationException.class,
        () -> authentication.verify("POST", PATH, headers));
  }

  @Test
  void rejectsTamperedSignature() {
    Map<String, String> headers = new HashMap<>(authentication.sign("POST", PATH, routeTo(NODE_B)));
    String signature = headers.get(HmacClusterInternalAuthentication.HEADER_SIGNATURE);
    headers.put(
        HmacClusterInternalAuthentication.HEADER_SIGNATURE,
        signature.charAt(0) == 'a' ? "b" + signature.substring(1) : "a" + signature.substring(1));

    assertThrows(
        ClusterInternalAuthenticationException.class,
        () -> authentication.verify("POST", PATH, headers));
  }

  @Test
  void rejectsWhenSecretIsNotConfigured() {
    HmacClusterInternalAuthentication unconfigured =
        new HmacClusterInternalAuthentication("", NODE_B, clock, WINDOW, 8);

    ClusterInternalAuthenticationException error =
        assertThrows(
            ClusterInternalAuthenticationException.class,
            () -> unconfigured.sign("POST", PATH, routeTo(NODE_B)));
    // 失败消息不得回显秘密内容。
    assertTrue(!error.getMessage().contains(SECRET));
  }

  @Test
  void rejectsWhenBoundedNonceCapacityIsExceeded() {
    HmacClusterInternalAuthentication bounded =
        new HmacClusterInternalAuthentication(SECRET, NODE_B, clock, WINDOW, 2);

    bounded.verify("POST", PATH, bounded.sign("POST", PATH, routeTo(NODE_B)));
    bounded.verify("POST", PATH, bounded.sign("POST", PATH, routeTo(NODE_B)));

    assertThrows(
        ClusterInternalAuthenticationException.class,
        () -> bounded.verify("POST", PATH, bounded.sign("POST", PATH, routeTo(NODE_B))));
  }
}
