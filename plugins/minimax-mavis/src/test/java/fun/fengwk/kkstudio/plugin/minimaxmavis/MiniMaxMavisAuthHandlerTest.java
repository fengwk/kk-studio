package fun.fengwk.kkstudio.plugin.minimaxmavis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * MiniMax Mavis 登录回调交互（MiniMaxMavisAuthHandler）测试。
 *
 * <p>验证 deep-link 回调解析成功时材料元数据（region、expiresAt、nextRefreshAt）准确生成、 payload 可由
 * MiniMaxMavisCredentialPayload 严格往返且无额外结构漂移； 验证回调格式错误与过期 token 确定性抛出异常且错误消息绝不包含回调原文。
 */
class MiniMaxMavisAuthHandlerTest {

  private static final Instant FIXED_NOW = Instant.ofEpochSecond(1_700_000_000L);
  private static final long FUTURE_EXP = 1_700_000_000L + 86400L * 10L; // 10 days later
  private static final Clock CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

  private FakeCatalogTransport transport;
  private MiniMaxMavisAuthHandler authHandler;

  @BeforeEach
  void setUp() {
    this.transport = new FakeCatalogTransport();
    MavisClient client = new MavisClient(transport, CLOCK, MavisDesktopEnvironment.session());
    this.authHandler = new MiniMaxMavisAuthHandler(client, CLOCK);
  }

  /** 验证中国区（minimax-cn scheme）登录回调生成 CN region 凭据材料，各字段公式准确无误。 */
  @Test
  void completeCnCallbackProducesAccurateMaterial() {
    String token = MavisTestTokens.withExp(FUTURE_EXP);
    String callbackUrl = "minimax-cn://auth-callback?accessToken=" + token;

    PluginCredentialMaterial material = authHandler.complete(callbackUrl);

    assertEquals("CN", material.region(), "Region must be CN");
    assertEquals(
        Instant.ofEpochSecond(FUTURE_EXP), material.expiresAt(), "expiresAt must match JWT exp");

    Instant expectedRefreshAt =
        MiniMaxMavisRefreshSchedule.nextRefreshAt(FIXED_NOW, Instant.ofEpochSecond(FUTURE_EXP));
    assertEquals(
        expectedRefreshAt, material.nextRefreshAt(), "nextRefreshAt must equal schedule formula");

    // payload parse 往返
    MiniMaxMavisCredentialPayload payload =
        MiniMaxMavisCredentialPayload.parse(material.payloadJson());
    assertEquals(token, payload.accessToken(), "Decoded access token must match callback");
    assertEquals(FIXED_NOW, payload.obtainedAt(), "Obtained time must equal clock now");
    assertNotNull(payload.clientUuid(), "client UUID must be generated");
    assertFalse(payload.clientUuid().isBlank());

    // 往返再编码与原 payloadJson 语义一致
    assertEquals(payload, MiniMaxMavisCredentialPayload.parse(payload.toJson()));

    // 校验 catalog 请求确实被触发了一次
    assertEquals(1, transport.requests.size());
    MavisHttpRequest request = transport.requests.get(0);
    assertEquals("https://agent.minimaxi.com/mavis/api/v1/mcp/tools", request.url());
  }

  /** 验证国际区（minimax scheme）登录回调生成 EN region 凭据材料。 */
  @Test
  void completeEnCallbackProducesEnMaterial() {
    String token = MavisTestTokens.withExp(FUTURE_EXP);
    String callbackUrl = "minimax://auth-callback?accessToken=" + token;

    PluginCredentialMaterial material = authHandler.complete(callbackUrl);

    assertEquals("EN", material.region(), "Region must be EN");
    assertEquals(Instant.ofEpochSecond(FUTURE_EXP), material.expiresAt());

    MiniMaxMavisCredentialPayload payload =
        MiniMaxMavisCredentialPayload.parse(material.payloadJson());
    assertEquals(token, payload.accessToken());

    assertEquals(1, transport.requests.size());
    MavisHttpRequest request = transport.requests.get(0);
    assertEquals("https://agent.minimax.io/mavis/api/v1/mcp/tools", request.url());
  }

  /** 验证已过期或即将过期的 token 在回调中被拒绝，且异常消息绝不泄漏回调原文。 */
  @Test
  void expiredTokenCallbackFailsAndRedactsCallbackUrl() {
    long expiredExp = FIXED_NOW.getEpochSecond() - 100L;
    String token = MavisTestTokens.withExp(expiredExp);
    String callbackUrl = "minimax-cn://auth-callback?accessToken=" + token;

    Exception error =
        assertThrows(
            MavisException.class,
            () -> authHandler.complete(callbackUrl),
            "Expired callback token must be rejected");

    assertFalse(
        error.getMessage().contains(callbackUrl),
        "Exception message must not leak full callback URL");
    assertFalse(error.getMessage().contains(token), "Exception message must not leak access token");
  }

  /** 验证非法回调 URL（缺少 token、未知 scheme、非规范路径）被拒绝且不泄漏回调原文。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "minimax-cn://auth-callback", // 缺少 accessToken
        "https://agent.minimaxi.com/auth-callback?accessToken=some-token", // 非 deep-link scheme
        "minimax-jp://auth-callback?accessToken=some-token", // 未知 scheme
        "minimax-cn://wrong-path?accessToken=some-token" // 非 auth-callback path
      })
  void malformedCallbackFailsAndRedactsCallbackUrl(String malformedCallback) {
    Exception error =
        assertThrows(
            MavisException.class,
            () -> authHandler.complete(malformedCallback),
            () -> "Malformed callback must be rejected: " + malformedCallback);

    assertFalse(
        error.getMessage().contains(malformedCallback),
        "Exception message must not contain raw callback URL");
  }

  /** 模拟 catalog 响应成功的假传输。 */
  private static final class FakeCatalogTransport implements MavisHttpTransport {
    private final List<MavisHttpRequest> requests = new ArrayList<>();

    @Override
    public MavisHttpResponse send(MavisHttpRequest request) {
      requests.add(request);
      return new MavisHttpResponse(200, "{\"tools\":[]}");
    }
  }
}
