package fun.fengwk.kkstudio.plugin.comfyui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.json.JsonValues;

import java.util.List;

/** 配置值对象的业务边界：默认值形状、上限与固定去敏错误，绝不臆造更宽的时间/大小预算。 */
class ComfyuiSettingsTest {

  private static final String ZERO_REFERENCE_POLICY =
      "{\"allowedKinds\":[\"IMAGE\"],\"maxCount\":0,\"maxCountByKind\":[]}";

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

  /** 普通配置的校验与 {@code enabled} 无关：停用状态下的非法连接目标、args schema、输出名与引用策略同样必须在保存时失败，而不是等启用后才爆。 */
  @Test
  void disabledConfigurationsValidateEveryOrdinaryFieldPurely() {
    // 连接目标复用客户端同一份 URI 门禁：userInfo / 非 HTTP / 路径 / query / 无 scheme 都必须拒绝。
    for (String baseUrl :
        List.of(
            "http://user:pass@127.0.0.1:8188",
            "ftp://127.0.0.1:8188",
            "http://127.0.0.1:8188/api",
            "http://127.0.0.1:8188?token=1",
            "127.0.0.1:8188")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ComfyuiSettings.parse(
                  config(
                      config("enabled", "false"),
                      "baseUrl",
                      ComfyuiWorkflowFixtures.quote(baseUrl))),
          baseUrl);
    }
    // argsSchema 关键字/类型非法。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ComfyuiSettings.parse(
                ComfyuiWorkflowFixtures.settingsJson(
                    false,
                    ComfyuiWorkflowFixtures.singleWorkflowArray(
                        ComfyuiWorkflowFixtures.workflow(
                            "direct-args",
                            "DIRECT",
                            ComfyuiWorkflowFixtures.DIRECT_GRAPH,
                            "{\"type\":\"object\",\"additionalProperties\":false,"
                                + "\"properties\":{\"prompt\":{\"type\":\"uri\"}}}",
                            "[]",
                            "[]",
                            ComfyuiWorkflowFixtures.DIRECT_OUTPUTS,
                            ComfyuiWorkflowFixtures.IMAGE_POLICY)))));
    // 输出名必须唯一且不超过定义层上限。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ComfyuiSettings.parse(
                ComfyuiWorkflowFixtures.settingsJson(
                    false,
                    ComfyuiWorkflowFixtures.singleWorkflowArray(
                        ComfyuiWorkflowFixtures.workflow(
                            "direct-output",
                            "DIRECT",
                            ComfyuiWorkflowFixtures.DIRECT_GRAPH,
                            ComfyuiWorkflowFixtures.DIRECT_ARGS_SCHEMA,
                            "[]",
                            "[]",
                            "[{\"name\":\"same\",\"kind\":\"VIDEO\",\"nodeId\":\"4\","
                                + "\"field\":\"videos\",\"index\":0},"
                                + "{\"name\":\"same\",\"kind\":\"VIDEO\",\"nodeId\":\"4\","
                                + "\"field\":\"videos\",\"index\":1}]",
                            ZERO_REFERENCE_POLICY)))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ComfyuiSettings.parse(
                ComfyuiWorkflowFixtures.settingsJson(
                    false,
                    ComfyuiWorkflowFixtures.singleWorkflowArray(
                        ComfyuiWorkflowFixtures.workflow(
                            "direct-output",
                            "DIRECT",
                            ComfyuiWorkflowFixtures.DIRECT_GRAPH,
                            ComfyuiWorkflowFixtures.DIRECT_ARGS_SCHEMA,
                            "[]",
                            "[]",
                            "[{\"name\":\""
                                + "n".repeat(513)
                                + "\",\"kind\":\"VIDEO\","
                                + "\"nodeId\":\"4\",\"field\":\"videos\",\"index\":0}]",
                            ZERO_REFERENCE_POLICY)))));
    // 引用策略的单类上限不得超过总数上限：只有 Canvas 定义层校验才覆盖这条。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ComfyuiSettings.parse(
                ComfyuiWorkflowFixtures.settingsJson(
                    false,
                    ComfyuiWorkflowFixtures.singleWorkflowArray(
                        ComfyuiWorkflowFixtures.workflow(
                            "direct-policy",
                            "DIRECT",
                            ComfyuiWorkflowFixtures.DIRECT_GRAPH,
                            ComfyuiWorkflowFixtures.DIRECT_ARGS_SCHEMA,
                            "[]",
                            "[]",
                            ComfyuiWorkflowFixtures.DIRECT_OUTPUTS,
                            "{\"allowedKinds\":[\"IMAGE\"],\"maxCount\":0,"
                                + "\"maxCountByKind\":[{\"kind\":\"IMAGE\",\"maxCount\":1}]}")))));
    // 默认（停用）配置仍然合法：普通校验不放宽也不误伤。
    assertFalse(ComfyuiSettings.parse(ComfyuiWorkflowFixtures.defaultsJson()).enabled());
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
