package fun.fengwk.kkstudio.plugin.minimaxmavis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * MiniMax Mavis 凭据的 opaque JSON 载荷。
 *
 * <p>它是 Platform 加密 envelope 的明文内容，也是 Plugin 自己的私有格式：{@code accessToken}、稳定 {@code clientUuid}
 * 与取得时间。Platform 只把它整体加解密，因此这里必须做严格编解码（未知字段、缺失字段、非数值时间都拒绝），并用 {@link #toString()} 屏蔽 token。
 */
public record MiniMaxMavisCredentialPayload(
    String accessToken, String clientUuid, Instant obtainedAt) {

  private static final String ACCESS_TOKEN_FIELD = "accessToken";
  private static final String CLIENT_UUID_FIELD = "clientUuid";
  private static final String OBTAINED_AT_FIELD = "obtainedAt";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  static {
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public MiniMaxMavisCredentialPayload {
    if (accessToken == null || accessToken.isBlank()) {
      throw new MavisValidationException("credential payload must contain an access token");
    }
    if (clientUuid == null || clientUuid.isBlank()) {
      throw new MavisValidationException("credential payload must contain a client uuid");
    }
    if (obtainedAt == null) {
      throw new MavisValidationException("credential payload must contain the obtained time");
    }
  }

  /** 由一次成功的登录或 renewal 结果构造载荷。 */
  public static MiniMaxMavisCredentialPayload of(MavisCredential credential, Instant obtainedAt) {
    return new MiniMaxMavisCredentialPayload(
        credential.token(), credential.clientUuid(), obtainedAt);
  }

  /** 序列化为规范 JSON；字段顺序固定，便于测试与人工排查。 */
  public String toJson() {
    try {
      return MAPPER.writeValueAsString(
          MAPPER
              .createObjectNode()
              .put(ACCESS_TOKEN_FIELD, accessToken)
              .put(CLIENT_UUID_FIELD, clientUuid)
              .put(OBTAINED_AT_FIELD, obtainedAt.toEpochMilli()));
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode the MiniMax Mavis credential payload", error);
    }
  }

  /** 严格解析 Platform 解出的载荷；任何缺失、类型错误或未知字段都拒绝。 */
  public static MiniMaxMavisCredentialPayload parse(String json) {
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new MavisValidationException(
          "MiniMax Mavis credential payload is not valid JSON", error);
    }
    if (root == null || !root.isObject()) {
      throw new MavisValidationException("MiniMax Mavis credential payload must be a JSON object");
    }
    int fields = root.size();
    if (fields != 3
        || !root.has(ACCESS_TOKEN_FIELD)
        || !root.has(CLIENT_UUID_FIELD)
        || !root.has(OBTAINED_AT_FIELD)) {
      throw new MavisValidationException("MiniMax Mavis credential payload has unexpected fields");
    }
    JsonNode token = root.get(ACCESS_TOKEN_FIELD);
    JsonNode clientUuid = root.get(CLIENT_UUID_FIELD);
    JsonNode obtainedAt = root.get(OBTAINED_AT_FIELD);
    if (!token.isTextual() || !clientUuid.isTextual() || !obtainedAt.isIntegralNumber()) {
      throw new MavisValidationException("MiniMax Mavis credential payload has invalid fields");
    }
    Instant obtained;
    try {
      obtained = Instant.ofEpochMilli(obtainedAt.longValue());
    } catch (RuntimeException error) {
      throw new MavisValidationException(
          "MiniMax Mavis credential payload has an invalid obtained time", error);
    }
    return new MiniMaxMavisCredentialPayload(token.textValue(), clientUuid.textValue(), obtained);
  }

  @Override
  public String toString() {
    return "MiniMaxMavisCredentialPayload[clientUuid="
        + clientUuid
        + ", obtainedAt="
        + obtainedAt
        + ", accessToken="
        + MavisRedaction.REDACTED
        + "]";
  }
}
