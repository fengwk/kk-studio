package fun.fengwk.kkstudio.plugin.comfyui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;

/** 配置值对象的业务边界：默认值形状、上限与固定去敏错误，绝不臆造更宽的时间/大小预算。 */
class ComfyuiSettingsTest {

  @Test
  void parseRequiresAJsonObjectWithEveryDeclaredField() {
    assertThrows(IllegalArgumentException.class, () -> ComfyuiSettings.parse("[]"));
    assertThrows(IllegalArgumentException.class, () -> ComfyuiSettings.parse("{}"));
    assertThrows(
        IllegalArgumentException.class, () -> ComfyuiSettings.parse(config("enabled", "\"yes\"")));
    assertThrows(
        IllegalArgumentException.class, () -> ComfyuiSettings.parse(config("enabled", "1")));
    assertThrows(
        IllegalArgumentException.class, () -> ComfyuiSettings.parse(config("baseUrl", "1")));
    assertThrows(
        IllegalArgumentException.class, () -> ComfyuiSettings.parse(config("baseUrl", "\"  \"")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ComfyuiSettings.parse(config("connectTimeoutMillis", "\"10000\"")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ComfyuiSettings.parse(config("promptAgentName", "\" x\"")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ComfyuiSettings.parse(config("authMode", "\"DIGEST\"")));
  }

  /** 连接与超时只能是既有安全预算内的正整数，且轮询间隔必须小于整体等待上限。 */
  @Test
  void parseEnforcesTheBoundedTimeoutBudgets() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ComfyuiSettings.parse(config("connectTimeoutMillis", "0")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ComfyuiSettings.parse(config("connectTimeoutMillis", "60001")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ComfyuiSettings.parse(config("requestTimeoutMillis", "0")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ComfyuiSettings.parse(config("requestTimeoutMillis", "300001")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ComfyuiSettings.parse(config("pollIntervalMillis", "60001")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ComfyuiSettings.parse(config("maxWaitMillis", "7200001")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ComfyuiSettings.parse(config("promptMaxWaitMillis", "1800001")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ComfyuiSettings.parse(config("promptMaxWaitMillis", "0")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ComfyuiSettings.parse(config("promptAgentName", "\"" + "a".repeat(65) + "\"")));
    // pollInterval 必须严格小于 maxWait。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ComfyuiSettings.parse(
                config(config("pollIntervalMillis", "2000"), "maxWaitMillis", "2000")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ComfyuiSettings.parse(
                config(config("pollIntervalMillis", "2001"), "maxWaitMillis", "2000")));
  }

  @Test
  void parseAcceptsBearerModeAndTheSchemaDefaults() {
    ComfyuiSettings defaults = ComfyuiSettings.parse(ComfyuiWorkflowFixtures.defaultsJson());
    assertFalse(defaults.enabled());
    assertEquals(ComfyuiSettings.AuthMode.NONE, defaults.authMode());

    ComfyuiSettings bearer = ComfyuiSettings.parse(config("authMode", "\"BEARER\""));
    assertEquals(ComfyuiSettings.AuthMode.BEARER, bearer.authMode());
    assertEquals("http://127.0.0.1:8188", bearer.baseUrl());
    assertEquals(1, bearer.workflows().size());
    assertTrue(bearer.workflows().get(0).graph().has("92"));
  }

  private static String config(String field, String jsonValue) {
    return config(ComfyuiWorkflowFixtures.defaultsJson(), field, jsonValue);
  }

  /** 以 schema 默认值为基线，替换一个顶层字段。 */
  private static String config(String base, String field, String jsonValue) {
    ObjectNode node = (ObjectNode) JsonValues.readTree(base);
    node.set(field, JsonValues.readTree(jsonValue));
    return JsonValues.write(node);
  }
}
