package fun.fengwk.kkstudio.plugin.minimaxmavis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 协议客户端在假传输上的全部安全与协议边界。
 *
 * <p>本测试不访问网络：{@link RecordingTransport} 记录每次请求并返回预设响应或失败，因此可以逐条断言请求形状、单次发送 （无自动
 * retry/redirect）、状态分层、错误类型与去敏结果。
 */
class MavisClientTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Instant NOW = Instant.ofEpochMilli(MavisTestTokens.FIXED_EPOCH_MILLIS);
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final String CATALOG_URL = "https://agent.minimaxi.com/mavis/api/v1/mcp/tools";
  private static final String OPAQUE_TOKEN = "header.payload.signature";
  private static final long VALID_EXP = 1_800_000_000L;

  private static JsonNode json(String payload) {
    try {
      return MAPPER.readTree(payload);
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  private static MavisClient client(RecordingTransport transport) {
    return new MavisClient(
        transport, Clock.fixed(NOW, ZONE), new MavisDesktopEnvironment("12345678", "Linux"));
  }

  private static MavisCredential credential() {
    return new MavisCredential(
        MavisTestTokens.withExp(VALID_EXP),
        MavisRegion.CN,
        Instant.ofEpochSecond(VALID_EXP),
        "uuid-1");
  }

  /** 记录请求并返回预设响应的假传输；行为上等价于「一次 send 只发一个请求」。 */
  private static final class RecordingTransport implements MavisHttpTransport {

    private final List<MavisHttpRequest> requests = new ArrayList<>();
    private final List<MavisHttpResponse> responses = new ArrayList<>();
    private MavisTransportException failure;
    private int served;

    RecordingTransport responder(String json) {
      responses.add(new MavisHttpResponse(200, json));
      return this;
    }

    RecordingTransport responder(MavisHttpResponse response) {
      responses.add(response);
      return this;
    }

    RecordingTransport failingWith(String message) {
      failure = new MavisTransportException(message);
      return this;
    }

    @Override
    public MavisHttpResponse send(MavisHttpRequest request) {
      requests.add(request);
      if (failure != null) {
        throw failure;
      }
      return responses.get(served++);
    }

    MavisHttpRequest onlyRequest() {
      assertEquals(1, requests.size(), "client must send exactly one request");
      return requests.get(0);
    }
  }

  /** 登录：解析回调、校验 JWT、用只读 catalog 在线验证，并生成稳定 client uuid。 */
  @Test
  void completeLoginVerifiesTokenAgainstCatalog() {
    RecordingTransport transport = new RecordingTransport().responder("{\"tools\":[]}");

    MavisCredential credential =
        client(transport)
            .completeLogin(
                "minimax-cn://auth-callback?accessToken=" + MavisTestTokens.withExp(VALID_EXP));

    assertEquals(MavisRegion.CN, credential.region());
    assertEquals(Instant.ofEpochSecond(VALID_EXP), credential.expiresAt());
    assertFalse(credential.clientUuid().isBlank());

    MavisHttpRequest request = transport.onlyRequest();
    assertEquals("GET", request.method());
    assertEquals(CATALOG_URL, request.url());
    assertEquals("Bearer " + credential.token(), request.headers().get("Authorization"));
    assertEquals("MiniMaxAgent", request.headers().get("User-Agent"));
    assertEquals(Duration.ofSeconds(30), request.timeout());
  }

  /** 登录使用回调 scheme 决定的 region origin。 */
  @Test
  void completeLoginUsesCallbackRegionOrigin() {
    RecordingTransport transport = new RecordingTransport().responder("{\"tools\":[]}");

    MavisCredential credential =
        client(transport)
            .completeLogin(
                "minimax://auth-callback?accessToken=" + MavisTestTokens.withExp(VALID_EXP));

    assertEquals(MavisRegion.EN, credential.region());
    assertEquals("https://agent.minimax.io/mavis/api/v1/mcp/tools", transport.onlyRequest().url());
  }

  /** 本地即可判定的 token 问题在发出任何请求之前失败。 */
  @Test
  void completeLoginRejectsLocallyInvalidTokensWithoutRequests() {
    RecordingTransport expired = new RecordingTransport();
    assertThrows(
        MavisAuthException.class,
        () ->
            client(expired)
                .completeLogin(
                    "minimax-cn://auth-callback?accessToken="
                        + MavisTestTokens.withExp(1_699_999_000L)));
    assertTrue(expired.requests.isEmpty());

    RecordingTransport opaque = new RecordingTransport();
    assertThrows(
        MavisAuthException.class,
        () ->
            client(opaque)
                .completeLogin(
                    "minimax-cn://auth-callback?accessToken=" + MavisTestTokens.withPayload("{}")));
    assertTrue(opaque.requests.isEmpty());

    RecordingTransport malformed = new RecordingTransport();
    assertThrows(
        MavisValidationException.class,
        () -> client(malformed).completeLogin("minimax-cn://auth-callback"));
    assertTrue(malformed.requests.isEmpty());
  }

  /** 在线验证拒绝被网关驳回的 token：401 与 1004 都映射为认证错误。 */
  @Test
  void completeLoginFailsOnRejectedToken() {
    assertThrows(
        MavisAuthException.class,
        () ->
            client(new RecordingTransport().responder(new MavisHttpResponse(401, "{}")))
                .completeLogin(
                    "minimax-cn://auth-callback?accessToken="
                        + MavisTestTokens.withExp(VALID_EXP)));
    assertThrows(
        MavisAuthException.class,
        () ->
            client(new RecordingTransport().responder("{\"base_resp\":{\"status_code\":1004}}"))
                .completeLogin(
                    "minimax-cn://auth-callback?accessToken="
                        + MavisTestTokens.withExp(VALID_EXP)));
  }

  /** catalog 的其他失败按类型区分：非 2xx 是传输错误、非 JSON/非对象是协议错误、非零业务状态是业务错误。 */
  @Test
  void catalogFailuresAreTyped() {
    assertThrows(
        MavisTransportException.class,
        () ->
            client(new RecordingTransport().responder(new MavisHttpResponse(500, "{}")))
                .fetchCatalog(OPAQUE_TOKEN, MavisRegion.CN));
    assertThrows(
        MavisProtocolException.class,
        () ->
            client(new RecordingTransport().responder("not-json"))
                .fetchCatalog(OPAQUE_TOKEN, MavisRegion.CN));
    assertThrows(
        MavisProtocolException.class,
        () ->
            client(new RecordingTransport().responder("[1,2]"))
                .fetchCatalog(OPAQUE_TOKEN, MavisRegion.CN));
    assertThrows(
        MavisProtocolException.class,
        () ->
            client(new RecordingTransport().responder("{\"tools\":{}}"))
                .fetchCatalog(OPAQUE_TOKEN, MavisRegion.CN));

    MavisBusinessException business =
        assertThrows(
            MavisBusinessException.class,
            () ->
                client(
                        new RecordingTransport()
                            .responder("{\"statusInfo\":{\"code\":500,\"message\":\"boom\"}}"))
                    .fetchCatalog(OPAQUE_TOKEN, MavisRegion.CN));
    assertEquals("500", business.code());
  }

  /** catalog 结果只包含文本 endpoint，用于判断能力是否仍在线上目录中。 */
  @Test
  void catalogExposesSupportedEndpoints() {
    MavisCapabilityCatalog catalog =
        client(
                new RecordingTransport()
                    .responder(
                        "{\"tools\":[{\"endpoint\":\"web_search\"},{\"endpoint\":\"synthesize_speech\"},"
                            + "{\"endpoint\":42},\"garbage\"]}"))
            .fetchCatalog(OPAQUE_TOKEN, MavisRegion.CN);

    assertTrue(catalog.supports(MavisCapability.WEB_SEARCH));
    assertTrue(catalog.supports(MavisCapability.TTS));
    assertFalse(catalog.supports(MavisCapability.TTS_BATCH));
    assertEquals(2, catalog.endpoints().size());
  }

  /** 通用 invoke：参数原样作为 body 发往能力 endpoint，并使用该能力的超时。 */
  @Test
  void invokePostsArgumentsToCapabilityEndpoint() {
    RecordingTransport transport =
        new RecordingTransport().responder("{\"base_resp\":{\"status_code\":0},\"results\":[]}");

    JsonNode payload =
        client(transport)
            .invoke(credential(), MavisCapability.WEB_SEARCH, json("{\"query\":\"mavis\"}"));

    assertEquals(0, payload.path("base_resp").path("status_code").asInt());
    MavisHttpRequest request = transport.onlyRequest();
    assertEquals("POST", request.method());
    assertEquals("https://agent.minimaxi.com/mavis/api/v1/mcp/web_search", request.url());
    assertEquals("{\"query\":\"mavis\"}", request.body());
    assertEquals("application/json", request.headers().get("Content-Type"));
    assertEquals("Bearer " + credential().token(), request.headers().get("Authorization"));
    assertEquals(MavisCapability.WEB_SEARCH.requestTimeout(), request.timeout());
  }

  /** 非 object 参数或非 object 响应都不会被接受。 */
  @Test
  void invokeRequiresObjectArgumentsAndObjectResponse() {
    RecordingTransport rejected = new RecordingTransport();
    assertThrows(
        MavisValidationException.class,
        () -> client(rejected).invoke(credential(), MavisCapability.TTS, json("[1]")));
    assertThrows(
        MavisValidationException.class,
        () -> client(rejected).invoke(credential(), MavisCapability.TTS, null));
    assertTrue(rejected.requests.isEmpty());

    assertThrows(
        MavisProtocolException.class,
        () ->
            client(new RecordingTransport().responder("\"ok\""))
                .invoke(credential(), MavisCapability.TTS, json("{}")));
  }

  /** 401、非 2xx、非 JSON 与非零业务状态都按类型失败，且任何路径都只发送一次请求。 */
  @Test
  void invokeFailuresAreTypedAndNeverRetried() {
    RecordingTransport unauthorized =
        new RecordingTransport().responder(new MavisHttpResponse(401, "{}"));
    assertThrows(
        MavisAuthException.class,
        () -> client(unauthorized).invoke(credential(), MavisCapability.TTS, json("{}")));
    assertEquals(1, unauthorized.requests.size());

    // 302 不会被自动跟随：客户端把它当作普通非 2xx 失败并停止。
    RecordingTransport redirected =
        new RecordingTransport().responder(new MavisHttpResponse(302, ""));
    assertThrows(
        MavisTransportException.class,
        () -> client(redirected).invoke(credential(), MavisCapability.TTS, json("{}")));
    assertEquals(1, redirected.requests.size());

    RecordingTransport broken = new RecordingTransport().responder("<html>");
    assertThrows(
        MavisProtocolException.class,
        () -> client(broken).invoke(credential(), MavisCapability.TTS, json("{}")));
    assertEquals(1, broken.requests.size());

    RecordingTransport failing = new RecordingTransport().failingWith("ConnectException");
    MavisTransportException transportError =
        assertThrows(
            MavisTransportException.class,
            () -> client(failing).invoke(credential(), MavisCapability.TTS, json("{}")));
    assertTrue(
        transportError
            .getMessage()
            .contains("synthesize_speech request failed (ConnectException)"));
    assertEquals(1, failing.requests.size());
  }

  /** 业务失败携带状态码与去敏消息：401/1004 是认证错误，402 提示停止生成。 */
  @Test
  void invokeBusinessFailuresCarryRedactedDetails() {
    MavisCredential credential = credential();

    MavisAuthException authFailure =
        assertThrows(
            MavisAuthException.class,
            () ->
                client(new RecordingTransport().responder("{\"base_resp\":{\"status_code\":1004}}"))
                    .invoke(credential, MavisCapability.TTS, json("{}")));
    assertFalse(authFailure.getMessage().contains(credential.token()));

    MavisBusinessException insufficientCredits =
        assertThrows(
            MavisBusinessException.class,
            () ->
                client(new RecordingTransport().responder("{\"code\":402,\"message\":\"quota\"}"))
                    .invoke(credential, MavisCapability.TTS, json("{}")));
    assertEquals("402", insufficientCredits.code());
    assertTrue(insufficientCredits.getMessage().contains("Do not retry"));

    MavisBusinessException leaked =
        assertThrows(
            MavisBusinessException.class,
            () ->
                client(
                        new RecordingTransport()
                            .responder(
                                "{\"code\":500,\"message\":\"token "
                                    + credential.token()
                                    + " see https://agent.minimaxi.com/x?token="
                                    + credential.token()
                                    + "\"}"))
                    .invoke(credential, MavisCapability.TTS, json("{}")));
    assertEquals("500", leaked.code());
    assertFalse(leaked.getMessage().contains(credential.token()));
    assertFalse(leaked.getMessage().contains("?token="));
    assertEquals("token [REDACTED] see https://agent.minimaxi.com/x", leaked.serverMessage());
  }

  /** 非 2xx 响应里的服务端摘要同样被去敏并受长度约束。 */
  @Test
  void invokeHttpErrorDetailIsRedacted() {
    MavisCredential credential = credential();
    RecordingTransport transport =
        new RecordingTransport()
            .responder(
                new MavisHttpResponse(
                    500,
                    "{\"base_resp\":{\"status_msg\":\"denied for " + credential.token() + "\"}}"));

    MavisTransportException error =
        assertThrows(
            MavisTransportException.class,
            () -> client(transport).invoke(credential, MavisCapability.TTS, json("{}")));

    assertEquals(
        "synthesize_speech returned HTTP 500: denied for " + MavisRedaction.REDACTED,
        error.getMessage());
    assertEquals(1, transport.requests.size());
  }

  /** renewal 成功时只替换 token 与到期时间，client uuid 与 region 保持不变。 */
  @Test
  void renewReplacesTokenUnderReferenceRequestShape() {
    MavisCredential credential = credential();
    String renewedToken = MavisTestTokens.withExp(1_900_000_000L);
    RecordingTransport transport =
        new RecordingTransport().responder("{\"data\":{\"token\":\"" + renewedToken + "\"}}");

    MavisCredential renewed = client(transport).renew(credential);

    assertEquals(renewedToken, renewed.token());
    assertEquals(Instant.ofEpochSecond(1_900_000_000L), renewed.expiresAt());
    assertEquals(credential.clientUuid(), renewed.clientUuid());
    assertEquals(credential.region(), renewed.region());

    MavisHttpRequest request = transport.onlyRequest();
    assertEquals("POST", request.method());
    assertNull(request.body());
    assertTrue(request.url().startsWith("https://agent.minimaxi.com/v1/api/user/renewal?"));
    assertTrue(request.url().contains("device_id=12345678"));
    assertEquals(credential.token(), request.headers().get("token"));
    assertEquals("MiniMaxAgent", request.headers().get("User-Agent"));
    assertNotNull(request.headers().get("x-signature"));
    assertEquals(Duration.ofSeconds(30), request.timeout());
    assertTrue(request.url().contains("timezone_offset=28800"));
  }

  /** renewal 的认证拒绝、协议错误与传输失败都被区分，且不重放。 */
  @Test
  void renewFailuresAreTypedAndNeverRetried() {
    RecordingTransport unauthorized =
        new RecordingTransport().responder(new MavisHttpResponse(401, "{}"));
    assertThrows(MavisAuthException.class, () -> client(unauthorized).renew(credential()));
    assertEquals(1, unauthorized.requests.size());

    RecordingTransport rejected =
        new RecordingTransport().responder("{\"statusInfo\":{\"code\":1004}}");
    assertThrows(MavisAuthException.class, () -> client(rejected).renew(credential()));
    assertEquals(1, rejected.requests.size());

    assertThrows(
        MavisProtocolException.class,
        () -> client(new RecordingTransport().responder("{")).renew(credential()));
    assertThrows(
        MavisProtocolException.class,
        () -> client(new RecordingTransport().responder("{\"data\":{}}")).renew(credential()));
    assertThrows(
        MavisProtocolException.class,
        () ->
            client(new RecordingTransport().responder("{\"data\":{\"token\":\" \"}}"))
                .renew(credential()));

    // 新 token 的本地时限同样被校验；不透明 token 视为认证失败。
    assertThrows(
        MavisAuthException.class,
        () ->
            client(
                    new RecordingTransport()
                        .responder("{\"data\":{\"token\":\"" + OPAQUE_TOKEN + "\"}}"))
                .renew(credential()));

    MavisTransportException transportError =
        assertThrows(
            MavisTransportException.class,
            () ->
                client(new RecordingTransport().failingWith("HttpTimeoutException"))
                    .renew(credential()));
    assertTrue(
        transportError
            .getMessage()
            .contains("MiniMax renewal request failed (HttpTimeoutException)"));
    assertFalse(transportError.getMessage().contains(credential().token()));
  }
}
