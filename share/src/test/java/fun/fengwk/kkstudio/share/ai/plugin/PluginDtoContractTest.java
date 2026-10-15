package fun.fengwk.kkstudio.share.ai.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** Plugin 管理面 wire 契约：固定认证类型、严格认证请求与「响应不含任何秘密」边界。 */
class PluginDtoContractTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static String configurationRequest(String secretPatchJson) {
    return "{\"expectedEpoch\":\"4\",\"expectedRevision\":\"7\",\"valuesJson\":\"{}\" ,"
        + "\"secretPatch\":"
        + secretPatchJson
        + "}";
  }

  @Test
  void prepareAndCompleteRequestsAreStrictlyDeclared() throws Exception {
    // 意图：prepare 精确为 {region}、complete 精确为 {callbackUrl}，未知字段与非字符串都 fail closed。
    PluginAuthPrepareRequestDTO prepare =
        MAPPER.readValue("{\"region\":\"CN\"}", PluginAuthPrepareRequestDTO.class);
    assertEquals("CN", prepare.getRegion());
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                "{\"region\":\"CN\",\"redirectUri\":\"x\"}", PluginAuthPrepareRequestDTO.class));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginAuthPrepareRequestDTO().rejectUnknownField("redirectUri", "x"));

    PluginAuthCompleteRequestDTO complete =
        MAPPER.readValue(
            "{\"callbackUrl\":\"minimax-cn://auth-callback?token=t\"}",
            PluginAuthCompleteRequestDTO.class);
    assertEquals("minimax-cn://auth-callback?token=t", complete.getCallbackUrl());
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                "{\"callbackUrl\":\"minimax://auth-callback\",\"accessToken\":\"x\"}",
                PluginAuthCompleteRequestDTO.class));
  }

  @Test
  void callbackUrlIsWriteOnlyAndExcludedFromToString() throws Exception {
    // 意图：回调地址只在请求内短暂存在，绝不回显到响应或日志（toString）。
    Field callbackUrl = PluginAuthCompleteRequestDTO.class.getDeclaredField("callbackUrl");
    JsonProperty annotation = callbackUrl.getAnnotation(JsonProperty.class);
    assertNotNull(annotation);
    assertEquals(JsonProperty.Access.WRITE_ONLY, annotation.access());

    String marker = "minimax-cn://auth-callback?code=secret-marker";
    PluginAuthCompleteRequestDTO dto = new PluginAuthCompleteRequestDTO();
    dto.setCallbackUrl(marker);

    // 只写字段没有可序列化属性：放宽空 bean 失败后输出为空对象，绝不回显回调地址。
    JsonMapper lenient =
        JsonMapper.builder().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS).build();
    assertFalse(lenient.writeValueAsString(dto).contains("secret-marker"));
    assertFalse(dto.toString().contains("secret-marker"));
  }

  /** 一次性 attempt 句柄属于身份，绝不进入任何文本渲染（请求与响应 DTO 同等对待）。 */
  @Test
  void attemptHandleIsExcludedFromEveryTextRendering() {
    String handle = "A".repeat(43);
    PluginAuthPrepareDTO prepare = new PluginAuthPrepareDTO();
    prepare.setAuthAttemptId(handle);
    prepare.setLoginUrl("https://login.example.com/authorize");
    assertFalse(prepare.toString().contains(handle), prepare.toString());

    PluginAuthCompleteRequestDTO complete = new PluginAuthCompleteRequestDTO();
    complete.setAuthAttemptId(handle);
    complete.setCallbackUrl("https://app.example.com/callback");
    assertFalse(complete.toString().contains(handle), complete.toString());
  }

  /** 期望坐标与 SET 值只接受 JSON 字符串：数字/布尔不做标量强制转换，KEEP/CLEAR 的显式 null 也被拒绝。 */
  @Test
  void configurationRequestOnlyAcceptsJsonStringsForCoordinatesAndSecretValues() throws Exception {
    PluginConfigurationUpdateRequestDTO accepted =
        MAPPER.readValue(
            configurationRequest("{\"apiKey\":{\"operation\":\"SET\",\"value\":\"s\"}}"),
            PluginConfigurationUpdateRequestDTO.class);
    assertEquals("4", accepted.getExpectedEpoch());
    assertEquals("7", accepted.getExpectedRevision());
    assertEquals("s", accepted.getSecretPatch().get("apiKey").getValue());

    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                "{\"expectedEpoch\":4,\"expectedRevision\":\"7\",\"valuesJson\":\"{}\"}",
                PluginConfigurationUpdateRequestDTO.class));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                "{\"expectedEpoch\":\"4\",\"expectedRevision\":true,\"valuesJson\":\"{}\"}",
                PluginConfigurationUpdateRequestDTO.class));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                configurationRequest("{\"apiKey\":{\"operation\":\"SET\",\"value\":12345678}}"),
                PluginConfigurationUpdateRequestDTO.class));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                configurationRequest("{\"apiKey\":{\"operation\":\"KEEP\",\"value\":null}}"),
                PluginConfigurationUpdateRequestDTO.class));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                configurationRequest("{\"apiKey\":{\"operation\":\"CLEAR\",\"value\":null}}"),
                PluginConfigurationUpdateRequestDTO.class));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                configurationRequest("{\"apiKey\":{\"operation\":\"SET\",\"value\":null}}"),
                PluginConfigurationUpdateRequestDTO.class));
  }

  /** 已应用能力名是两个真实列表字段：只承载快照 owner map 派生出的名字，没有贡献时为空列表。 */
  @Test
  void appliedCapabilityNamesAreTwoExplicitLists() throws Exception {
    assertEquals(List.class, PluginDTO.class.getDeclaredField("harnessToolNames").getType());
    assertEquals(List.class, PluginDTO.class.getDeclaredField("canvasFunctionNames").getType());

    PluginDTO dto = new PluginDTO();
    dto.setHarnessToolNames(List.of("read"));
    dto.setCanvasFunctionNames(List.of());
    String json = MAPPER.writeValueAsString(dto);
    assertTrue(json.contains("\"harnessToolNames\":[\"read\"]"), json);
    assertTrue(json.contains("\"canvasFunctionNames\":[]"), json);
  }

  @Test
  void safeProjectionCarriesNoSecretsOrClientIdentity() {
    // 意图：安全投影只有安装身份、认证状态与时间；密文、token、密钥与 client identity 物理上不存在。
    List<String> forbidden =
        List.of(
            "token",
            "secret",
            "credential",
            "cipher",
            "payload",
            "clientid",
            "callback",
            "lease",
            "key");
    for (Field field : PluginDTO.class.getDeclaredFields()) {
      String normalized = field.getName().toLowerCase();
      for (String forbiddenPart : forbidden) {
        assertFalse(
            normalized.contains(forbiddenPart),
            () -> "PluginDTO must not expose " + forbiddenPart + ": " + field.getName());
      }
    }
    assertThrows(NoSuchFieldException.class, () -> PluginDTO.class.getDeclaredField("accessToken"));
    assertThrows(
        NoSuchFieldException.class, () -> PluginDTO.class.getDeclaredField("encryptedPayload"));
  }

  @Test
  void authKindIsAFixedUnionOfDeepLinkAndStaticKey() {
    // 意图：认证类型是封闭 union，只有交互式 DEEP_LINK 与静态密钥 STATIC_KEY 两种真实形态。
    assertEquals(
        List.of(PluginAuthKindDTO.DeepLink.class, PluginAuthKindDTO.StaticKey.class),
        List.of(PluginAuthKindDTO.class.getPermittedSubclasses()));

    PluginAuthKindDTO.DeepLink deepLink = new PluginAuthKindDTO.DeepLink(List.of("CN", "EN"));
    assertEquals(List.of("CN", "EN"), deepLink.regionCandidates());

    PluginAuthKindDTO.StaticKey staticKey = new PluginAuthKindDTO.StaticKey("apiKey");
    assertEquals("apiKey", staticKey.secretField());
  }

  @Test
  void prepareResponseOnlyExposesTheAttemptHandleAndPublicLoginUrl() throws Exception {
    // 意图：prepare 响应只有一次性 attempt 句柄与公开登录链接，不携带 token、签名参数或插件内部 URL。
    PluginAuthPrepareDTO dto =
        MAPPER.readValue(
            "{\"loginUrl\":\"https://www.minimax.io/login\"}", PluginAuthPrepareDTO.class);
    assertEquals("https://www.minimax.io/login", dto.getLoginUrl());
    assertNull(
        dto.getAuthAttemptId(), "an absent handle must stay null, never a synthesized value");
    assertEquals(
        List.of("authAttemptId", "loginUrl"),
        Arrays.stream(PluginAuthPrepareDTO.class.getDeclaredFields()).map(Field::getName).toList(),
        "the prepare response is exactly the handle plus the login url");

    PluginAuthPrepareDTO withHandle =
        MAPPER.readValue(
            "{\"authAttemptId\":\"handle\",\"loginUrl\":\"https://www.minimax.io/login\"}",
            PluginAuthPrepareDTO.class);
    assertEquals("handle", withHandle.getAuthAttemptId());

    PluginDTO connected = new PluginDTO();
    connected.setPluginId("minimax-mavis");
    connected.setName("MiniMax Mavis");
    connected.setVersion("1.0.0");
    connected.setAuthKind(new PluginAuthKindDTO.DeepLink(List.of("CN", "EN")));
    connected.setStatus("CONNECTED");
    connected.setRegion("CN");
    connected.setLastRefreshError(null);

    String json = MAPPER.writeValueAsString(connected);
    assertTrue(json.contains("\"pluginId\":\"minimax-mavis\""), json);
    assertTrue(json.contains("\"type\":\"DEEP_LINK\""), json);
    assertTrue(json.contains("\"regionCandidates\":[\"CN\",\"EN\"]"), json);
    assertNull(connected.getLastRefreshError());
    // 到期/刷新时间在 wire 上是 Instant（Web 层按数值时间戳序列化）。
    assertEquals(Instant.class, PluginDTO.class.getDeclaredField("expiresAt").getType());
    assertEquals(Instant.class, PluginDTO.class.getDeclaredField("nextRefreshAt").getType());
    assertEquals(Instant.class, PluginDTO.class.getDeclaredField("lastRefreshedAt").getType());
  }
}
