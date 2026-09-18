package fun.fengwk.kkstudio.platform.settings;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionRule;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSectionsDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsToolDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * System settings codec 契约：canonical 编码确定性且省略 null、任意键顺序解码到同一聚合、round-trip 恒等、未知字段 / 非法类型 / 尾随
 * token / 缺失字段全部拒绝，permission 规则顺序在 JSON 往返中保持。
 */
class SystemSettingsCodecTest {

  private final SystemSettingsCodec codec = new SystemSettingsCodec();
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void canonicalRoundTripIsIdentity() {
    String canonical = codec.encode(SystemSettings.DEFAULT);
    SystemSettings decoded = codec.decode(canonical);
    assertEquals(SystemSettings.DEFAULT, decoded);
    // canonical 编码是幂等的：重新编码已解码的聚合产出相同字节。
    assertEquals(canonical, codec.encode(decoded));
  }

  @Test
  void canonicalJsonIsSortedAndOmitsNulls() {
    String canonical = codec.encode(SystemSettings.DEFAULT);
    // 六个 section 按字母序输出。
    int advanced = canonical.indexOf("\"advanced\"");
    int aiRuntime = canonical.indexOf("\"aiRuntime\"");
    int environment = canonical.indexOf("\"environment\"");
    int integrations = canonical.indexOf("\"integrations\"");
    int storageMedia = canonical.indexOf("\"storageMedia\"");
    int tool = canonical.indexOf("\"tool\"");
    assertTrue(
        advanced >= 0 && aiRuntime > advanced && environment > aiRuntime,
        "sections must be sorted: " + canonical);
    assertTrue(
        integrations > environment && storageMedia > integrations && tool > storageMedia,
        "sections must be sorted: " + canonical);
    // 规则对象键与工具名 key 也排序。
    assertTrue(canonical.indexOf("\"action\":\"ask\",\"pattern\":\"*\"") >= 0, canonical);
    // null 可空字段省略。
    assertTrue(!canonical.contains("workspaceId"), canonical);
    assertTrue(!canonical.contains("comfyui\":{\"baseUrl"), canonical);
    // null compactionFallbackModel 也在 canonical JSON 中省略。
    assertTrue(!canonical.contains("compactionFallbackModel"), canonical);
  }

  @Test
  void compactionFallbackModelRoundTripsThroughCanonicalJsonAndDto() {
    SystemSettings settings =
        new SystemSettings(
            SystemSettings.DEFAULT.tool(),
            new SystemSettings.AiRuntime(
                SystemSettings.AiRuntime.DEFAULT.retryMaxRetries(),
                SystemSettings.AiRuntime.DEFAULT.retryBackoffStrategy(),
                SystemSettings.AiRuntime.DEFAULT.retryBaseDelayMillis(),
                SystemSettings.AiRuntime.DEFAULT.retryMaxDelayMillis(),
                SystemSettings.AiRuntime.DEFAULT.compactionKeepRecentTokens(),
                new ModelSelection("openai", "gpt-4o", "default"),
                SystemSettings.AiRuntime.DEFAULT.subagentMaxDepth(),
                SystemSettings.AiRuntime.DEFAULT.subagentMaxConcurrency(),
                SystemSettings.AiRuntime.DEFAULT.subagentMaxTotalConcurrency(),
                SystemSettings.AiRuntime.DEFAULT.subagentIdleTimeoutMillis(),
                SystemSettings.AiRuntime.DEFAULT.subagentMaxTurns()),
            SystemSettings.DEFAULT.environment(),
            SystemSettings.DEFAULT.integrations(),
            SystemSettings.DEFAULT.storageMedia(),
            SystemSettings.DEFAULT.advanced());

    // canonical JSON 往返保留 fallback。
    SystemSettings decoded = codec.decode(codec.encode(settings));
    assertEquals(settings, decoded);

    // DTO 双向映射保留 fallback（share 层 HarnessModelSelectionDTO）。
    SystemSettingsSectionsDTO dto = codec.toSections(settings);
    assertEquals("openai", dto.getAiRuntime().getCompactionFallbackModel().getProviderName());
    assertEquals("gpt-4o", dto.getAiRuntime().getCompactionFallbackModel().getModelName());
    assertEquals("default", dto.getAiRuntime().getCompactionFallbackModel().getVariant());
    assertEquals(settings, codec.fromDto(dto));

    // null fallback 在 canonical JSON 中省略；DTO 方向为 null。
    SystemSettingsSectionsDTO nullDto = codec.toSections(SystemSettings.DEFAULT);
    assertEquals(null, nullDto.getAiRuntime().getCompactionFallbackModel());
    assertEquals(null, codec.fromDto(nullDto).aiRuntime().compactionFallbackModel());
  }

  @Test
  void decodingDoesNotDependOnObjectKeyOrdering() throws Exception {
    String canonical = codec.encode(SystemSettings.DEFAULT);
    // 反转六个 section 的声明顺序，解码结果必须与顺序无关。
    String reordered = reverseTopLevelKeys(canonical);
    assertEquals(SystemSettings.DEFAULT, codec.decode(reordered));
  }

  @Test
  void preservesPermissionRuleOrderThroughJsonRoundTrip() {
    Map<String, List<PermissionRule>> permission = new LinkedHashMap<>();
    permission.put(
        "bash",
        List.of(
            new PermissionRule("*", PermissionAction.ASK),
            new PermissionRule("git ?", PermissionAction.ALLOW),
            new PermissionRule("secret/**", PermissionAction.DENY)));
    SystemSettings settings = withToolPermission(permission);

    SystemSettings decoded = codec.decode(codec.encode(settings));
    assertEquals(
        List.of(
            new PermissionRule("*", PermissionAction.ASK),
            new PermissionRule("git ?", PermissionAction.ALLOW),
            new PermissionRule("secret/**", PermissionAction.DENY)),
        decoded.tool().permission().get("bash"));
  }

  @Test
  void preservesGlobalAndToolNamePermissionKeysThroughJsonAndDtoRoundTrips() {
    Map<String, List<PermissionRule>> permission = new LinkedHashMap<>();
    permission.put("*", List.of(new PermissionRule("*", PermissionAction.ASK)));
    permission.put("bash", List.of(new PermissionRule("git *", PermissionAction.ALLOW)));
    SystemSettings settings = withToolPermission(permission);

    assertEquals(settings, codec.decode(codec.encode(settings)));
    assertEquals(settings, codec.fromDto(codec.toSections(settings)));
  }

  @Test
  void rejectsUnknownFieldsAndInvalidShapesInStoredJson() {
    String canonical = codec.encode(SystemSettings.DEFAULT);
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode(canonical.replace(",\"aiRuntime\"", ",\"unknown\":1,\"aiRuntime\"")),
        "unknown field");
    assertThrows(
        IllegalStateException.class, () -> codec.decode("{\"tool\":5}"), "wrong section type");
    assertThrows(
        IllegalStateException.class, () -> codec.decode(canonical + " {}"), "trailing tokens");
    assertThrows(IllegalStateException.class, () -> codec.decode("[]"), "non-object root");
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode("{\"tool\":null}"),
        "null required section");
    assertThrows(IllegalStateException.class, () -> codec.decode("not-json"), "malformed JSON");
    assertThrows(IllegalStateException.class, () -> codec.decode(""), "blank config");
    assertThrows(
        IllegalStateException.class,
        () ->
            codec.decode(
                canonical.replace(
                    "\"retryBaseDelayMillis\":2000", "\"retryBaseDelayMillis\":2000.5")),
        "fractional millis");
    assertThrows(
        IllegalStateException.class,
        () ->
            codec.decode(
                canonical.replace(
                    "\"retryBackoffStrategy\":\"EXPONENTIAL\"",
                    "\"retryBackoffStrategy\":\"LINEAR\"")),
        "unsupported strategy");
  }

  @Test
  void rejectsMissingRequiredPrimitiveCreatorProperties() throws Exception {
    // canonical 中删除 required primitive 后解码必须失败，而不是静默回退到 false/0（损坏 JSON 的典型形态）。
    String canonical = codec.encode(SystemSettings.DEFAULT);
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode(withoutAiRuntimeField(canonical, "compactionKeepRecentTokens")),
        "missing compactionKeepRecentTokens");
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode(withoutAiRuntimeField(canonical, "retryMaxRetries")),
        "missing retryMaxRetries");
  }

  @Test
  void rejectsNullRequiredPrimitiveCreatorProperties() throws Exception {
    // 显式 null 的 required primitive 解码必须失败；null 引用字段是合法省略/未配置语义，保持可解码。
    String canonical = codec.encode(SystemSettings.DEFAULT);
    assertThrows(
        IllegalStateException.class,
        () ->
            codec.decode(
                withAiRuntimeField(canonical, "compactionKeepRecentTokens", mapper.nullNode())),
        "null compactionKeepRecentTokens");
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode(withAiRuntimeField(canonical, "retryMaxRetries", mapper.nullNode())),
        "null retryMaxRetries");
  }

  @Test
  void rejectsBooleanScalarCoercionFromStringIntegerAndFloat() throws Exception {
    // 布尔字段只接受 JSON true/false；string/整数/浮点标量（jackson 2.19.0 默认强制转换）一律拒绝。
    String canonical = codec.encode(SystemSettings.DEFAULT);
    assertThrows(
        IllegalStateException.class,
        () ->
            codec.decode(
                withToolField(canonical, "defaultYolo", mapper.getNodeFactory().textNode("true"))),
        "string boolean");
    assertThrows(
        IllegalStateException.class,
        () ->
            codec.decode(
                withToolField(canonical, "defaultYolo", mapper.getNodeFactory().numberNode(1))),
        "integer boolean");
    assertThrows(
        IllegalStateException.class,
        () ->
            codec.decode(
                withToolField(canonical, "defaultYolo", mapper.getNodeFactory().numberNode(1.5))),
        "float boolean");
  }

  @Test
  void omittedNullableReferencesStillRoundTrip() {
    // 构造全部 nullable 引用（baseUrl、workspaceId、minimaxH3 身份字段）为 null 的配置：
    // canonical 编码必须省略这些字段，严格解码不得误报缺失，且重编码保持幂等。
    SystemSettings settings =
        new SystemSettings(
            SystemSettings.DEFAULT.tool(),
            SystemSettings.DEFAULT.aiRuntime(),
            SystemSettings.DEFAULT.environment(),
            new SystemSettings.Integrations(
                new SystemSettings.Comfyui(
                    false, null, 10_000L, 30_000L, 1_800_000L, 50L * 1024 * 1024),
                new SystemSettings.OpenCliHub(
                    false, null, 5_000L, 120_000L, 130_000L, 16 * 1024, 512 * 1024, 4096, 65_535),
                SystemSettings.DEFAULT.integrations().seedance(),
                SystemSettings.DEFAULT.integrations().gptImage2(),
                new SystemSettings.MiniMaxH3(
                    false, null, 600_000L, null, 10_000L, 30_000L, 2_000L, 1_800_000L)),
            SystemSettings.DEFAULT.storageMedia(),
            SystemSettings.DEFAULT.advanced());
    String canonical = codec.encode(settings);
    assertTrue(!canonical.contains("\"baseUrl\""), canonical);
    assertTrue(!canonical.contains("\"workspaceId\""), canonical);
    assertEquals(settings, codec.decode(canonical));
    assertEquals(canonical, codec.encode(codec.decode(canonical)));
  }

  @Test
  void explicitNullNullableReferencesStillDecode() throws Exception {
    // 显式 null 的 nullable 引用（语义等于 canonical 省略）必须仍可解码为同一聚合。
    ObjectNode root = (ObjectNode) mapper.readTree(codec.encode(SystemSettings.DEFAULT));
    ((ObjectNode) ((ObjectNode) root.get("integrations")).get("comfyui"))
        .set("baseUrl", mapper.nullNode());
    assertEquals(SystemSettings.DEFAULT, codec.decode(mapper.writeValueAsString(root)));
  }

  @Test
  void rejectsNonCanonicalEnumValues() throws Exception {
    ObjectNode invalidStrategy = (ObjectNode) mapper.readTree(codec.encode(SystemSettings.DEFAULT));
    ((ObjectNode) invalidStrategy.get("aiRuntime")).put("retryBackoffStrategy", "exponential");
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode(mapper.writeValueAsString(invalidStrategy)));

    ObjectNode invalidPermission =
        (ObjectNode) mapper.readTree(codec.encode(SystemSettings.DEFAULT));
    ObjectNode firstRule =
        (ObjectNode) invalidPermission.path("tool").path("permission").path("write").get(0);
    firstRule.put("action", "ASK");
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode(mapper.writeValueAsString(invalidPermission)));
  }

  @Test
  void rejectsForbiddenPermissionPatternsAtSaveValidation() {
    // 领域构造（fromDto 的必经路径）在持久化前拒绝 negation 形态；已构造的合法聚合不可能携带非法 pattern。
    SystemSettingsSectionsDTO sections = codec.toSections(SystemSettings.DEFAULT);
    sections
        .getTool()
        .getPermission()
        .put(
            "write",
            List.of(
                new SystemSettingsToolDTO.PermissionRuleDTO() {
                  {
                    setPattern("!logs/");
                    setAction("deny");
                  }
                }));
    assertThrows(IllegalArgumentException.class, () -> codec.fromDto(sections));
  }

  @Test
  void acceptsExactWildcardAndValidToolNamesAtDtoBoundary() {
    // DTO 允许精确 wildcard 与合法模型可见工具名（含大小写混合，与 ToolDescriptor.isValidName 完全一致）。
    SystemSettingsSectionsDTO sections = codec.toSections(SystemSettings.DEFAULT);
    for (String key : List.of("*", "read", "mcp_server_echo", "Write")) {
      SystemSettingsToolDTO.PermissionRuleDTO rule = new SystemSettingsToolDTO.PermissionRuleDTO();
      rule.setPattern("*");
      rule.setAction("ask");
      sections.getTool().getPermission().put(key, List.of(rule));
    }
    assertDoesNotThrow(() -> codec.fromDto(sections));
  }

  @Test
  void rejectsNonCanonicalPermissionKeysAtDtoBoundary() {
    // DTO 仍使用 String key，但保存边界必须只接受精确 wildcard 或合法模型可见工具名。
    for (String key : List.of("1write", "_write", " write", "write ")) {
      SystemSettingsSectionsDTO sections = codec.toSections(SystemSettings.DEFAULT);
      SystemSettingsToolDTO.PermissionRuleDTO rule = new SystemSettingsToolDTO.PermissionRuleDTO();
      rule.setPattern("*");
      rule.setAction("ask");
      sections.getTool().getPermission().put(key, List.of(rule));
      assertThrows(IllegalArgumentException.class, () -> codec.fromDto(sections), key);
    }

    SystemSettingsSectionsDTO sections = codec.toSections(SystemSettings.DEFAULT);
    sections
        .getTool()
        .getPermission()
        .put(" write", List.of(new SystemSettingsToolDTO.PermissionRuleDTO()));
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.fromDto(sections));
    assertEquals(
        "tool.permission key must be '*' or a valid model-visible tool name", error.getMessage());
  }

  @Test
  void fromDtoRequiresEverySectionAndField() {
    SystemSettingsSectionsDTO sections = codec.toSections(SystemSettings.DEFAULT);
    SystemSettingsToolDTO tool = sections.getTool();
    assertEquals(SystemSettings.DEFAULT, codec.fromDto(sections));

    sections.setTool(null);
    assertThrows(IllegalArgumentException.class, () -> codec.fromDto(sections));
    sections.setTool(tool);

    sections.getAiRuntime().setRetryBaseDelayMillis(null);
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.fromDto(sections));
    assertTrue(
        error.getMessage().contains("aiRuntime.retryBaseDelayMillis is required"),
        error.getMessage());
    sections.getAiRuntime().setRetryBaseDelayMillis(2_000L);

    sections.getIntegrations().setSeedance(null);
    assertThrows(IllegalArgumentException.class, () -> codec.fromDto(sections));
    assertThrows(IllegalArgumentException.class, () -> codec.fromDto(null));
  }

  @Test
  void fromDtoRejectsInvalidValuesWithFieldQualifiedMessages() {
    SystemSettingsSectionsDTO sections = codec.toSections(SystemSettings.DEFAULT);
    sections.getAdvanced().setProcessorHeartbeatIntervalMillis(60_000L);
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.fromDto(sections));
    assertTrue(error.getMessage().contains("processorHeartbeatIntervalMillis"), error.getMessage());
  }

  private SystemSettings withToolPermission(Map<String, List<PermissionRule>> permission) {
    SystemSettings.Tool base = SystemSettings.DEFAULT.tool();
    return new SystemSettings(
        new SystemSettings.Tool(
            permission,
            base.defaultYolo(),
            base.modelGatewayBusyRetryMillis(),
            base.toolGatewayBusyRetryMillis(),
            base.toolGatewayOverloadRetryMillis(),
            base.skillLoadTimeoutMillis()),
        SystemSettings.DEFAULT.aiRuntime(),
        SystemSettings.DEFAULT.environment(),
        SystemSettings.DEFAULT.integrations(),
        SystemSettings.DEFAULT.storageMedia(),
        SystemSettings.DEFAULT.advanced());
  }

  /** 反转顶层六个 section 的声明顺序（保持 JSON 合法），验证解码不依赖键序。 */
  private static String reverseTopLevelKeys(String json) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode root = (ObjectNode) mapper.readTree(json);
    ObjectNode reordered = mapper.createObjectNode();
    List<String> names = new ArrayList<>();
    root.fieldNames().forEachRemaining(names::add);
    for (int i = names.size() - 1; i >= 0; i--) {
      reordered.set(names.get(i), root.get(names.get(i)));
    }
    return mapper.writeValueAsString(reordered);
  }

  /** 从 canonical 文本删除 aiRuntime 的指定字段后重新序列化（基于解析后的 ObjectNode，避免脆弱字符串替换）。 */
  private String withoutAiRuntimeField(String canonical, String field) throws Exception {
    ObjectNode root = (ObjectNode) mapper.readTree(canonical);
    ((ObjectNode) root.get("aiRuntime")).remove(field);
    return mapper.writeValueAsString(root);
  }

  /** 把 canonical 文本中 aiRuntime 的指定字段替换为给定节点后重新序列化。 */
  private String withAiRuntimeField(String canonical, String field, JsonNode value)
      throws Exception {
    ObjectNode root = (ObjectNode) mapper.readTree(canonical);
    ((ObjectNode) root.get("aiRuntime")).set(field, value);
    return mapper.writeValueAsString(root);
  }

  private String withToolField(String canonical, String field, JsonNode value) throws Exception {
    ObjectNode root = (ObjectNode) mapper.readTree(canonical);
    ((ObjectNode) root.get("tool")).set(field, value);
    return mapper.writeValueAsString(root);
  }
}
