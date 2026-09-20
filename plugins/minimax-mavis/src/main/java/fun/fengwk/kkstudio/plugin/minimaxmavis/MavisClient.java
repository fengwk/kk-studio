package fun.fengwk.kkstudio.plugin.minimaxmavis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * MiniMax Mavis 协议客户端。
 *
 * <p>它只做协议：登录回调校验、capability catalog 读取、15 项能力的通用请求与 renewal。每次调用最多发送一个 HTTP 请求，不自动 redirect、不自动
 * retry；单次断连或超时的结果视为不确定，由调用方决定后续动作，因为生成类调用是 非幂等的。
 *
 * <p>所有进入错误消息的服务端文本都经 {@link MavisRedaction} 去敏并截断，因此消息可以安全地进入日志与响应。
 */
public final class MavisClient {

  private static final String CATALOG_PATH = "/mavis/api/v1/mcp/tools";
  private static final String MCP_PREFIX = "/mavis/api/v1/mcp/";
  private static final String USER_AGENT = "MiniMaxAgent";
  private static final String CATALOG_CONTEXT = "MiniMax tool catalog";
  private static final String RENEWAL_CONTEXT = "MiniMax renewal";
  private static final Duration CATALOG_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration RENEWAL_TIMEOUT = Duration.ofSeconds(30);
  private static final int MAX_DETAIL_CHARS = 1_000;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final MavisHttpTransport transport;
  private final Clock clock;
  private final MavisDesktopEnvironment environment;

  /** 使用系统时钟与当前 JVM 会话桌面身份构造客户端。 */
  public MavisClient(MavisHttpTransport transport) {
    this(transport, Clock.systemDefaultZone(), MavisDesktopEnvironment.session());
  }

  public MavisClient(
      MavisHttpTransport transport, Clock clock, MavisDesktopEnvironment environment) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.environment = Objects.requireNonNull(environment, "environment");
  }

  /**
   * 完成一次 deep-link 登录：严格解析回调、校验本地 JWT 时限、在线验证 token 并生成稳定 client UUID。
   *
   * <p>在线验证使用只读 capability catalog，因此登录不会触发生成类请求。
   */
  public MavisCredential completeLogin(String callbackUrl) {
    MavisCallback callback = MavisCallback.parse(callbackUrl);
    Instant now = clock.instant();
    MavisCredential credential =
        MavisCredential.fromJwt(
            callback.accessToken(), callback.region(), now, UUID.randomUUID().toString());
    fetchCatalog(credential.token(), credential.region());
    return credential;
  }

  /** 读取网关当前提供的 endpoint 目录，并校验该 token 未被拒绝。 */
  public MavisCapabilityCatalog fetchCatalog(String accessToken, MavisRegion region) {
    MavisHttpRequest request =
        request("GET", region.baseUrl() + CATALOG_PATH, accessToken, null, CATALOG_TIMEOUT);
    JsonNode payload = exchange(CATALOG_CONTEXT, request, accessToken);
    MavisStatus.privateStatus(payload)
        .ifPresent(code -> raise(code, CATALOG_CONTEXT, payload, accessToken));
    JsonNode tools = payload.get("tools");
    if (tools == null || !tools.isArray()) {
      throw new MavisProtocolException(CATALOG_CONTEXT + " response has no tools list");
    }
    Set<String> endpoints = new LinkedHashSet<>();
    for (JsonNode tool : tools) {
      if (tool.isObject() && tool.path("endpoint").isTextual()) {
        endpoints.add(tool.path("endpoint").textValue());
      }
    }
    return new MavisCapabilityCatalog(endpoints);
  }

  /**
   * 向给定能力的 MCP endpoint 发送一次严格请求。
   *
   * <p>{@code arguments} 必须是顶层 JSON object，原样作为请求体交给网关；响应同样必须是顶层 object 且业务状态成功，否则 抛出类型化错误。
   */
  public JsonNode invoke(
      MavisCredential credential, MavisCapability capability, JsonNode arguments) {
    Objects.requireNonNull(credential, "credential");
    Objects.requireNonNull(capability, "capability");
    if (arguments == null || !arguments.isObject()) {
      throw new MavisValidationException(
          "MiniMax Mavis tool arguments must be a JSON object for " + capability.toolName());
    }
    MavisHttpRequest request =
        request(
            "POST",
            credential.region().baseUrl() + MCP_PREFIX + capability.endpoint(),
            credential.token(),
            encode(arguments, capability),
            capability.requestTimeout());
    JsonNode payload = exchange(capability.endpoint(), request, credential.token());
    MavisStatus.businessStatus(payload)
        .ifPresent(code -> raise(code, capability.endpoint(), payload, credential.token()));
    return payload;
  }

  /**
   * 用当前 access token 调用一次 renewal，并返回仅替换 token 与到期时间的新凭据。
   *
   * <p>只有 HTTP、业务状态与新 JWT 都通过校验才会返回新凭据；请求发出后的断连或超时抛出传输错误，调用方不得重放。
   */
  public MavisCredential renew(MavisCredential credential) {
    Objects.requireNonNull(credential, "credential");
    Instant now = clock.instant();
    MavisRenewalRequest renewal =
        MavisRenewalRequest.sign(
            credential, now, clock.getZone(), environment.deviceId(), environment.osName());
    MavisHttpRequest request =
        new MavisHttpRequest("POST", renewal.url(), renewal.headers(), null, RENEWAL_TIMEOUT);
    JsonNode payload = exchange(RENEWAL_CONTEXT, request, credential.token());
    MavisStatus.privateStatus(payload)
        .ifPresent(code -> raise(code, RENEWAL_CONTEXT, payload, credential.token()));
    JsonNode data = payload.get("data");
    JsonNode token = data != null && data.isObject() ? data.get("token") : null;
    if (token == null || !token.isTextual() || token.textValue().isBlank()) {
      throw new MavisProtocolException(RENEWAL_CONTEXT + " response did not contain a token");
    }
    return MavisCredential.fromJwt(
        token.textValue().strip(), credential.region(), clock.instant(), credential.clientUuid());
  }

  /** 发送一次请求并完成 HTTP 状态与 JSON 顶层形状校验，不检查业务状态。 */
  private JsonNode exchange(String context, MavisHttpRequest request, String accessToken) {
    MavisHttpResponse response;
    try {
      response = transport.send(request);
    } catch (MavisTransportException error) {
      throw new MavisTransportException(
          context
              + " request failed ("
              + MavisRedaction.diagnostic(error.getMessage(), accessToken)
              + ")",
          error);
    }
    if (response.statusCode() == 401) {
      throw new MavisAuthException(MavisAuthException.REJECTED_MESSAGE);
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new MavisTransportException(
          context
              + " returned HTTP "
              + response.statusCode()
              + ": "
              + errorDetail(response.body(), accessToken));
    }
    JsonNode payload;
    try {
      payload = MAPPER.readTree(response.body());
    } catch (JsonProcessingException error) {
      throw new MavisProtocolException(context + " returned a non-JSON response", error);
    }
    if (payload == null || !payload.isObject()) {
      throw new MavisProtocolException(context + " response must be a JSON object");
    }
    return payload;
  }

  private void raise(JsonNode code, String context, JsonNode payload, String accessToken) {
    throw MavisStatus.raise(code, context, detail(payload, accessToken));
  }

  private MavisHttpRequest request(
      String method, String url, String accessToken, String body, Duration timeout) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("User-Agent", USER_AGENT);
    headers.put("Authorization", "Bearer " + accessToken);
    if (body != null) {
      headers.put("Content-Type", "application/json");
    }
    return new MavisHttpRequest(method, url, headers, body, timeout);
  }

  private static String encode(JsonNode arguments, MavisCapability capability) {
    try {
      return MAPPER.writeValueAsString(arguments);
    } catch (JsonProcessingException error) {
      throw new MavisValidationException(
          "MiniMax Mavis tool arguments cannot be encoded for " + capability.toolName(), error);
    }
  }

  /** 失败响应里的服务端消息；未知结构退化为固定文本。 */
  private static String detail(JsonNode payload, String accessToken) {
    JsonNode baseResp = payload.get("base_resp");
    if (baseResp != null && baseResp.isObject() && baseResp.path("status_msg").isValueNode()) {
      return bounded(baseResp.path("status_msg").asText(), accessToken);
    }
    JsonNode message = Optional.ofNullable(payload.get("message")).orElse(payload.get("error"));
    if (message != null && message.isValueNode()) {
      return bounded(message.asText(), accessToken);
    }
    return "unexpected error response";
  }

  /** HTTP 非 2xx 响应的服务端摘要；body 不是对象时退化为固定文本。 */
  private static String errorDetail(String body, String accessToken) {
    JsonNode payload;
    try {
      payload = MAPPER.readTree(body);
    } catch (JsonProcessingException error) {
      return "non-JSON error response";
    }
    if (payload == null || !payload.isObject()) {
      return "non-JSON error response";
    }
    return detail(payload, accessToken);
  }

  private static String bounded(String value, String accessToken) {
    String redacted = MavisRedaction.diagnostic(value, accessToken);
    return redacted.length() <= MAX_DETAIL_CHARS
        ? redacted
        : redacted.substring(0, MAX_DETAIL_CHARS);
  }
}
