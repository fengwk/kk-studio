package fun.fengwk.kkstudio.harness.daemon.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** DaemonLocalMcpParser 严格解析、默认值、identity 与防泄漏契约测试。 */
class DaemonLocalMcpParserTest {

  /** canonical 小写带连字符 UUID：本地 MCP identity 只接受唯一表示。 */
  private static final String SERVER_ID = "11111111-1111-4111-8111-111111111111";

  private static final String ENVIRONMENT_ID = "22222222-2222-4222-8222-222222222222";

  private static final Map<String, String> TEST_ENV =
      Map.of(
          "TEST_HOME", "/opt/test",
          "MY_BIN", "/usr/local/bin/my-mcp",
          "API_KEY", "secret-token-12345",
          "EMPTY_VALUE", "");

  private final DaemonLocalMcpParser parser = new DaemonLocalMcpParser(TEST_ENV::get);

  private static String discoverJson(String serverId, String configVersion, String configJson) {
    return "{\"serverId\":\""
        + serverId
        + "\",\"configVersion\":"
        + configVersion
        + ",\"config\":"
        + configJson
        + "}";
  }

  private static String fullConfig(
      String commandJson, String cwdJson, String envJson, String extraJson) {
    return "{\"type\":\"local\",\"environmentId\":\""
        + ENVIRONMENT_ID
        + "\",\"command\":"
        + commandJson
        + ",\"cwd\":\""
        + cwdJson
        + "\""
        + (envJson == null ? "" : ",\"env\":" + envJson)
        + extraJson
        + "}";
  }

  /** 完整请求解析成功，字段逐项保持原义。 */
  @Test
  void parsesValidDiscoverRequest() {
    String json =
        discoverJson(
            SERVER_ID,
            "1",
            fullConfig("[\"node\",\"index.js\"]", "/opt/test/mcp", "{\"FOO\":\"bar\"}", ""));

    DaemonLocalMcpConfig config = parser.parseDiscover(json);
    assertEquals(SERVER_ID, config.serverId());
    assertEquals(1, config.configVersion());
    assertEquals(ENVIRONMENT_ID, config.environmentId());
    assertEquals(List.of("node", "index.js"), config.command());
    assertEquals("/opt/test/mcp", config.cwd());
    assertEquals(Map.of("FOO", "bar"), config.env());
    assertTrue(config.enabled());
    assertEquals(60_000L, config.timeoutMillis(), "未提供的 timeoutMillis 必须回落到 60000 默认值");
  }

  /** call 请求额外要求工具名与 arguments JSON object。 */
  @Test
  void parsesValidCallRequest() {
    String json =
        "{\"serverId\":\""
            + SERVER_ID
            + "\",\"configVersion\":2,\"config\":"
            + fullConfig("[\"node\",\"index.js\"]", "/opt/test/mcp", null, "")
            + ",\"toolName\":\"check_cwd\",\"arguments\":{\"name\":\"TEST_HOME\"}}";

    DaemonLocalMcpCallRequest request = parser.parseCall(json);
    assertEquals(SERVER_ID, request.config().serverId());
    assertEquals("check_cwd", request.toolName());
    assertEquals("{\"name\":\"TEST_HOME\"}", request.argumentsJson());
  }

  /**
   * 可选字段缺省：env 为空映射、enabled 为 true、timeoutMillis 为 60000； 只有 type/environmentId/command/cwd 是必填。
   */
  @Test
  void appliesDefaultsForOptionalConfigFields() {
    String json = discoverJson(SERVER_ID, "0", fullConfig("[\"node\"]", "/opt/test", null, ""));

    DaemonLocalMcpConfig config = parser.parseDiscover(json);
    assertEquals(Map.of(), config.env());
    assertTrue(config.enabled(), "enabled 缺省必须为 true");
    assertEquals(60_000L, config.timeoutMillis(), "timeoutMillis 缺省必须为 60000");
  }

  /** 显式给出的可选字段被尊重，不得被默认值覆盖。 */
  @Test
  void honoursExplicitOptionalFields() {
    String json =
        discoverJson(
            SERVER_ID,
            "0",
            "{\"type\":\"local\",\"environmentId\":\""
                + ENVIRONMENT_ID
                + "\",\"command\":[\"node\"],\"cwd\":\"/opt/test\",\"env\":{\"A\":\"b\"},"
                + "\"enabled\":true,\"timeoutMillis\":1234}");

    DaemonLocalMcpConfig config = parser.parseDiscover(json);
    assertEquals(Map.of("A", "b"), config.env());
    assertEquals(1234L, config.timeoutMillis());
  }

  /** 缺少任一必填字段必须失败。 */
  @Test
  void rejectsMissingRequiredFields() {
    for (String configJson :
        List.of(
            "{\"environmentId\":\""
                + ENVIRONMENT_ID
                + "\",\"command\":[\"node\"],\"cwd\":\"/opt\"}",
            "{\"type\":\"local\",\"command\":[\"node\"],\"cwd\":\"/opt\"}",
            "{\"type\":\"local\",\"environmentId\":\"" + ENVIRONMENT_ID + "\",\"cwd\":\"/opt\"}",
            "{\"type\":\"local\",\"environmentId\":\""
                + ENVIRONMENT_ID
                + "\",\"command\":[\"node\"]}")) {
      assertThrows(
          DaemonMcpValidationException.class,
          () -> parser.parseDiscover(discoverJson(SERVER_ID, "1", configJson)),
          configJson);
    }
  }

  /** 任何未知字段都必须拒绝，避免调用方以为传递了生效的配置。 */
  @Test
  void rejectsUnknownFields() {
    assertThrows(
        DaemonMcpValidationException.class,
        () ->
            parser.parseDiscover(
                discoverJson(
                    SERVER_ID,
                    "1",
                    fullConfig("[\"node\"]", "/opt/test", null, ",\"workdir\":\"/opt/test\""))),
        "config 不得接受 workdir 等未知字段");

    assertThrows(
        DaemonMcpValidationException.class,
        () ->
            parser.parseDiscover(
                "{\"serverId\":\""
                    + SERVER_ID
                    + "\",\"configVersion\":1,\"config\":"
                    + fullConfig("[\"node\"]", "/opt/test", null, "")
                    + ",\"workdir\":\"/opt/test\"}"),
        "顶层不得接受 workdir 等未知字段");
  }

  /** serverId/environmentId 只接受 canonical 小写带连字符 UUID。 */
  @Test
  void rejectsNonCanonicalIdentities() {
    for (String invalid :
        List.of(
            "11111111111141118111111111111111",
            "ABCDEF01-1234-4ABC-8ABC-ABCDEFABCDEF",
            "{11111111-1111-4111-8111-111111111111}",
            "not-a-uuid",
            "")) {
      assertThrows(
          DaemonMcpValidationException.class,
          () ->
              parser.parseDiscover(
                  discoverJson(invalid, "1", fullConfig("[\"node\"]", "/opt/test", null, ""))),
          "serverId 必须是 canonical UUID: " + invalid);
    }

    assertThrows(
        DaemonMcpValidationException.class,
        () ->
            parser.parseDiscover(
                discoverJson(
                    SERVER_ID,
                    "1",
                    "{\"type\":\"local\",\"environmentId\":\"ENV-1\",\"command\":[\"node\"],\"cwd\":\"/opt\"}")),
        "environmentId 必须是 canonical UUID");
  }

  /** configVersion 必须是可无损转 long 的非负整数。 */
  @Test
  void rejectsInvalidConfigVersions() {
    for (String invalid : List.of("-1", "1.5", "\"1\"", "99999999999999999999999999")) {
      assertThrows(
          DaemonMcpValidationException.class,
          () ->
              parser.parseDiscover(
                  discoverJson(
                      SERVER_ID, invalid, fullConfig("[\"node\"]", "/opt/test", null, ""))),
          "configVersion 必须是非负整数: " + invalid);
    }
  }

  /** timeoutMillis 必须是可无损转 long 的正整数。 */
  @Test
  void rejectsInvalidTimeouts() {
    for (String invalid : List.of("0", "-1", "1.5", "\"1000\"", "99999999999999999999999999")) {
      assertThrows(
          DaemonMcpValidationException.class,
          () ->
              parser.parseDiscover(
                  discoverJson(
                      SERVER_ID,
                      "1",
                      fullConfig(
                          "[\"node\"]", "/opt/test", null, ",\"timeoutMillis\":" + invalid))),
          "timeoutMillis 必须是正整数: " + invalid);
    }
  }

  /** 重复键必须被拒绝，避免 JSON 语义歧义导致「校验值」与「生效值」不一致。 */
  @Test
  void rejectsDuplicateKeys() {
    assertThrows(
        DaemonMcpValidationException.class,
        () ->
            parser.parseDiscover(
                "{\"serverId\":\""
                    + SERVER_ID
                    + "\",\"serverId\":\""
                    + SERVER_ID
                    + "\",\"configVersion\":1,\"config\":"
                    + fullConfig("[\"node\"]", "/opt/test", null, "")
                    + "}"));
  }

  /** 尾随内容与畸形 JSON 必须被拒绝，且异常不保留可能回显原始文本的底层 cause。 */
  @Test
  void rejectsMalformedJsonWithoutLeakingCause() {
    DaemonMcpValidationException trailing =
        assertThrows(
            DaemonMcpValidationException.class,
            () -> parser.parseDiscover("{\"serverId\":\"" + SERVER_ID + "\"} trailing"));
    assertNull(trailing.getCause(), "malformed JSON 不得保留 Jackson cause");

    DaemonMcpValidationException malformed =
        assertThrows(
            DaemonMcpValidationException.class,
            () ->
                parser.parseDiscover(
                    "{\"serverId\":\"" + SERVER_ID + "\",\"configVersion\": broken}"));
    assertNull(malformed.getCause());
    assertFalse(malformed.getMessage().contains("broken"), "异常文本不得回显 payload 片段");
  }

  /** cwd 必须是绝对路径；命令必须是非空字符串数组。 */
  @Test
  void rejectsRelativeCwdAndEmptyCommand() {
    assertThrows(
        DaemonMcpValidationException.class,
        () ->
            parser.parseDiscover(
                discoverJson(SERVER_ID, "1", fullConfig("[\"node\"]", "relative/path", null, ""))));

    for (String commandJson : List.of("[]", "[\"\"]", "[1]", "[\"  \"]")) {
      assertThrows(
          DaemonMcpValidationException.class,
          () ->
              parser.parseDiscover(
                  discoverJson(SERVER_ID, "1", fullConfig(commandJson, "/opt/test", null, ""))));
    }
  }

  /** config type 只接受 local。 */
  @Test
  void rejectsNonLocalType() {
    String json =
        discoverJson(
            SERVER_ID,
            "1",
            "{\"type\":\"remote\",\"environmentId\":\""
                + ENVIRONMENT_ID
                + "\",\"command\":[\"node\"],\"cwd\":\"/opt/test\"}");
    assertThrows(DaemonMcpValidationException.class, () -> parser.parseDiscover(json));
  }

  /** 显式 disabled 的 server 必须拒绝执行。 */
  @Test
  void rejectsDisabledServer() {
    assertThrows(
        DaemonMcpValidationException.class,
        () ->
            parser.parseDiscover(
                discoverJson(
                    SERVER_ID,
                    "1",
                    fullConfig("[\"node\"]", "/opt/test", null, ",\"enabled\":false"))));
  }

  /** 整值 ${VAR} 引用从环境提供方解析；缺失时报错，且错误不回显变量值。 */
  @Test
  void resolvesWholeVariableReferences() {
    String json =
        discoverJson(
            SERVER_ID,
            "1",
            "{\"type\":\"local\",\"environmentId\":\""
                + ENVIRONMENT_ID
                + "\",\"command\":[\"${MY_BIN}\",\"serve\"],\"cwd\":\"${TEST_HOME}\","
                + "\"env\":{\"TOKEN\":\"${API_KEY}\"}}");

    DaemonLocalMcpConfig config = parser.parseDiscover(json);
    assertEquals(List.of("/usr/local/bin/my-mcp", "serve"), config.command());
    assertEquals("/opt/test", config.cwd());
    assertEquals(Map.of("TOKEN", "secret-token-12345"), config.env());

    DaemonMcpValidationException missing =
        assertThrows(
            DaemonMcpValidationException.class,
            () ->
                parser.parseDiscover(
                    discoverJson(
                        SERVER_ID,
                        "1",
                        fullConfig("[\"${MISSING_VAR_XYZ}\"]", "/opt/test", null, ""))));
    assertFalse(missing.getMessage().contains("MISSING_VAR_XYZ"), "异常不得回显变量名或值");
  }

  /** env 允许空覆盖值，但 command 的变量引用解析后仍必须满足非空命令参数约束。 */
  @Test
  void preservesEmptyEnvironmentValuesButRejectsEmptyResolvedCommand() {
    String valid =
        discoverJson(
            SERVER_ID,
            "1",
            fullConfig(
                "[\"node\"]",
                "/opt/test",
                "{\"EMPTY_DIRECT\":\"\",\"EMPTY_REFERENCE\":\"${EMPTY_VALUE}\"}",
                ""));
    assertEquals(
        Map.of("EMPTY_DIRECT", "", "EMPTY_REFERENCE", ""),
        parser.parseDiscover(valid).env(),
        "子进程环境覆盖必须保留合法空值");

    assertThrows(
        DaemonMcpValidationException.class,
        () ->
            parser.parseDiscover(
                discoverJson(
                    SERVER_ID, "1", fullConfig("[\"${EMPTY_VALUE}\"]", "/opt/test", null, ""))),
        "环境变量解析不能绕过 command 非空约束");
  }

  /** 只有整值引用会被解析：复合表达式与环境变量名片段都保持字面量。 */
  @Test
  void onlyWholeValueReferencesAreResolved() {
    String json =
        discoverJson(
            SERVER_ID,
            "1",
            fullConfig(
                "[\"prefix-${TEST_HOME}\",\"${TEST_HOME}-suffix\",\"$TEST_HOME\"]",
                "/opt/test",
                null,
                ""));

    DaemonLocalMcpConfig config = parser.parseDiscover(json);
    assertEquals(
        List.of("prefix-${TEST_HOME}", "${TEST_HOME}-suffix", "$TEST_HOME"), config.command());
  }

  /** 绑定已知时 environmentId 必须与当前 Daemon 绑定严格一致；绑定未知时只校验 canonical 形状。 */
  @Test
  void enforcesBoundEnvironmentWhenAvailable() {
    AtomicReference<String> bound = new AtomicReference<>();
    DaemonLocalMcpParser bindingAware = new DaemonLocalMcpParser(TEST_ENV::get, bound::get);

    String matchingConfig = fullConfig("[\"node\"]", "/opt/test", null, "");
    // 绑定未知（WELCOME 之前）：只校验 canonical UUID
    assertEquals(
        ENVIRONMENT_ID,
        bindingAware.parseDiscover(discoverJson(SERVER_ID, "1", matchingConfig)).environmentId());

    bound.set(ENVIRONMENT_ID);
    assertEquals(
        ENVIRONMENT_ID,
        bindingAware.parseDiscover(discoverJson(SERVER_ID, "1", matchingConfig)).environmentId());

    // 绑定已知但不匹配：必须拒绝
    String otherConfig =
        "{\"type\":\"local\",\"environmentId\":\"33333333-3333-4333-8333-333333333333\","
            + "\"command\":[\"node\"],\"cwd\":\"/opt/test\"}";
    DaemonMcpValidationException mismatch =
        assertThrows(
            DaemonMcpValidationException.class,
            () -> bindingAware.parseDiscover(discoverJson(SERVER_ID, "1", otherConfig)));
    assertFalse(mismatch.getMessage().contains("33333333"), "异常不得回显请求的 environmentId");
  }

  /** 非 object 的 arguments 与空白输入必须失败。 */
  @Test
  void rejectsNonObjectArguments() {
    for (String json : List.of("[]", "\"text\"", "", "   ")) {
      assertThrows(DaemonMcpValidationException.class, () -> parser.parseDiscover(json));
    }
  }
}
