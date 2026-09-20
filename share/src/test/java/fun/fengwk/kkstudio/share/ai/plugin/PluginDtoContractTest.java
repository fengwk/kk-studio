package fun.fengwk.kkstudio.share.ai.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

/** Plugin 管理面 wire 契约：固定认证类型、严格认证请求与「响应不含任何秘密」边界。 */
class PluginDtoContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

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
    ObjectMapper lenient = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    assertFalse(lenient.writeValueAsString(dto).contains("secret-marker"));
    assertFalse(dto.toString().contains("secret-marker"));
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
  void authKindIsAFixedDeepLinkUnion() {
    // 意图：认证类型是封闭 union，当前只有 DEEP_LINK，且只声明固定 region 候选。
    assertEquals(1, PluginAuthKindDTO.class.getPermittedSubclasses().length);
    assertEquals(
        PluginAuthKindDTO.DeepLink.class, PluginAuthKindDTO.class.getPermittedSubclasses()[0]);

    PluginAuthKindDTO.DeepLink deepLink = new PluginAuthKindDTO.DeepLink(List.of("CN", "EN"));
    assertEquals(List.of("CN", "EN"), deepLink.regionCandidates());
  }

  @Test
  void prepareResponseOnlyExposesThePublicLoginUrl() throws Exception {
    // 意图：prepare 响应只给公开登录链接，不携带 token、签名参数或插件内部 URL。
    PluginAuthPrepareDTO dto =
        MAPPER.readValue(
            "{\"loginUrl\":\"https://www.minimax.io/login\"}", PluginAuthPrepareDTO.class);
    assertEquals("https://www.minimax.io/login", dto.getLoginUrl());
    assertEquals(1, PluginAuthPrepareDTO.class.getDeclaredFields().length);

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
