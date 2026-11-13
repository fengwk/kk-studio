package fun.fengwk.kkstudio.platform.settings;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.model.ImageInputTier;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessModelSelectionDTO;
import fun.fengwk.kkstudio.platform.harness.tool.permission.PermissionAction;
import fun.fengwk.kkstudio.platform.harness.tool.permission.PermissionRule;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsBackendNetworkDTO;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSectionsDTO;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsToolDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * System settings codec 契约：canonical 编码确定性且省略 null、任意键顺序解码到同一聚合、round-trip 恒等、未知字段 / 重复字段 / 非法类型 /
 * 尾随 token / 缺失字段全部拒绝，permission 规则顺序在 JSON 往返中保持。
 */
class SystemSettingsCodecTest {

  private final SystemSettingsCodec codec = new SystemSettingsCodec();
  private final ObjectMapper mapper = JsonMapper.builder().build();

  @Test
  void missingStoredImageCountIsRejectedAsARequiredField() throws Exception {
    ObjectNode root = (ObjectNode) mapper.readTree(codec.encode(SystemSettingsTestFixture.DEFAULT));
    ((ObjectNode) root.get("storageMedia")).remove("maxImagesPerRequest");

    assertThrows(IllegalStateException.class, () -> codec.decode(root.toString()));
  }

  @Test
  void canonicalRoundTripIsIdentity() {
    String canonical = codec.encode(SystemSettingsTestFixture.DEFAULT);
    SystemSettings decoded = codec.decode(canonical);
    assertEquals(SystemSettingsTestFixture.DEFAULT, decoded);
    // canonical 编码是幂等的：重新编码已解码的聚合产出相同字节。
    assertEquals(canonical, codec.encode(decoded));
  }

  @Test
  void backendNetworkRoundTripsAndRejectsIncompleteOrNonTextCanonicalFields() throws Exception {
    // proxyUrl 可省略/null；noProxyHosts 必须存在且为字符串，空字符串保留。
    SystemSettingsSectionsDTO dto = codec.toSections(SystemSettingsTestFixture.DEFAULT);
    dto.getBackendNetwork().setProxyUrl("http://proxy:3128");
    dto.getBackendNetwork().setNoProxyHosts("");
    SystemSettings settings = codec.fromDto(dto);
    assertEquals(dto, codec.toSections(codec.decode(codec.encode(settings))));
    assertEquals("", settings.backendNetwork().noProxyHosts());

    ObjectNode root = (ObjectNode) mapper.readTree(codec.encode(SystemSettingsTestFixture.DEFAULT));
    ObjectNode backendNetwork = (ObjectNode) root.get("backendNetwork");
    backendNetwork.putNull("proxyUrl");
    assertEquals(SystemSettingsTestFixture.DEFAULT, codec.decode(root.toString()));
    backendNetwork.remove("noProxyHosts");
    assertThrows(IllegalStateException.class, () -> codec.decode(root.toString()));
    backendNetwork.putNull("noProxyHosts");
    assertThrows(IllegalStateException.class, () -> codec.decode(root.toString()));
    backendNetwork.put("noProxyHosts", 1);
    assertThrows(IllegalStateException.class, () -> codec.decode(root.toString()));
    backendNetwork.put("noProxyHosts", "");
    backendNetwork.put("proxyUrl", true);
    assertThrows(IllegalStateException.class, () -> codec.decode(root.toString()));
    backendNetwork.remove("proxyUrl");
    backendNetwork.put("enabled", false);
    assertThrows(IllegalStateException.class, () -> codec.decode(root.toString()));
    root.remove("backendNetwork");
    assertThrows(IllegalStateException.class, () -> codec.decode(root.toString()));
    root.putNull("backendNetwork");
    assertThrows(IllegalStateException.class, () -> codec.decode(root.toString()));
  }

  @Test
  void backendNetworkDtoRequiresSectionAndBypassWithoutDefaulting() {
    // 完整 PUT 不接受缺 section / 缺 required text；proxyUrl null 与 noProxyHosts 空串合法。
    SystemSettingsSectionsDTO dto = codec.toSections(SystemSettingsTestFixture.DEFAULT);
    dto.getBackendNetwork().setNoProxyHosts(null);
    assertEquals(
        "backendNetwork.noProxyHosts is required",
        assertThrows(IllegalArgumentException.class, () -> codec.fromDto(dto)).getMessage());
    dto.setBackendNetwork(null);
    assertEquals(
        "backendNetwork is required",
        assertThrows(IllegalArgumentException.class, () -> codec.fromDto(dto)).getMessage());
  }

  @Test
  void canonicalJsonIsSortedAndOmitsNulls() {
    String canonical = codec.encode(SystemSettingsTestFixture.DEFAULT);
    // 六个 section 按字母序输出。
    int advanced = canonical.indexOf("\"advanced\"");
    int aiRuntime = canonical.indexOf("\"aiRuntime\"");
    int environment = canonical.indexOf("\"environment\"");
    int backendNetwork = canonical.indexOf("\"backendNetwork\"");
    int storageMedia = canonical.indexOf("\"storageMedia\"");
    int tool = canonical.indexOf("\"tool\"");
    assertTrue(
        advanced >= 0 && aiRuntime > advanced && backendNetwork > aiRuntime,
        "sections must be sorted: " + canonical);
    assertTrue(
        environment > backendNetwork && storageMedia > environment && tool > storageMedia,
        "sections must be sorted: " + canonical);
    // 规则对象键与工具名 key 也排序。
    assertTrue(canonical.indexOf("\"action\":\"ask\",\"pattern\":\"*\"") >= 0, canonical);
    // null 可空字段省略。
    assertTrue(!canonical.contains("workspaceId"), canonical);
    assertTrue(!canonical.contains("integrations"), canonical);
    assertTrue(!canonical.contains("proxyUrl"), canonical);
  }

  @Test
  void decodingDoesNotDependOnObjectKeyOrdering() throws Exception {
    String canonical = codec.encode(SystemSettingsTestFixture.DEFAULT);
    // 反转六个 section 的声明顺序，解码结果必须与顺序无关。
    String reordered = reverseTopLevelKeys(canonical);
    assertEquals(SystemSettingsTestFixture.DEFAULT, codec.decode(reordered));
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
    String canonical = codec.encode(SystemSettingsTestFixture.DEFAULT);
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
  void rejectsDuplicateFieldsInStoredJson() {
    // 存储边界自身拒绝重复字段：同一字段出现两次时无法判断哪份值生效，属损坏数据；不依赖 Web 请求解析器的严格性。
    String canonical = codec.encode(SystemSettingsTestFixture.DEFAULT);
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode(canonical.replace("\"aiRuntime\"", "\"aiRuntime\":{},\"aiRuntime\"")),
        "duplicate top-level section");
    assertThrows(
        IllegalStateException.class,
        () ->
            codec.decode(
                canonical.replace(
                    "\"retryMaxRetries\":3", "\"retryMaxRetries\":3,\"retryMaxRetries\":3")),
        "duplicate nested field");
  }

  @Test
  void rejectsMissingRequiredPrimitiveCreatorProperties() throws Exception {
    // canonical 中删除 required primitive 后解码必须失败，而不是静默回退到 false/0（损坏 JSON 的典型形态）。
    String canonical = codec.encode(SystemSettingsTestFixture.DEFAULT);
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
    String canonical = codec.encode(SystemSettingsTestFixture.DEFAULT);
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
  void rejectsScalarCoercionFromStringFloatAndBooleanInStoredJson() throws Exception {
    // 数值字段只接受对应 JSON 数值：string/浮点/布尔标量（jackson 默认强制转换）在存储层一律拒绝。
    String canonical = codec.encode(SystemSettingsTestFixture.DEFAULT);
    assertThrows(
        IllegalStateException.class,
        () ->
            codec.decode(
                withStorageMediaField(
                    canonical, "thumbnailQuality", mapper.getNodeFactory().textNode("80"))),
        "string integer");
    assertThrows(
        IllegalStateException.class,
        () ->
            codec.decode(
                withStorageMediaField(
                    canonical, "thumbnailQuality", mapper.getNodeFactory().numberNode(80.5))),
        "fractional integer");
    assertThrows(
        IllegalStateException.class,
        () ->
            codec.decode(
                withStorageMediaField(
                    canonical, "thumbnailQuality", mapper.getNodeFactory().booleanNode(true))),
        "boolean integer");
  }

  /** 图片档位以规范 wire 名称存储：未知名称与缺失字段都必须在存储边界失败。 */
  @Test
  void rejectsUnknownAndMissingImageTier() throws Exception {
    String canonical = codec.encode(SystemSettingsTestFixture.DEFAULT);
    ObjectNode unknown = (ObjectNode) mapper.readTree(canonical);
    ((ObjectNode) unknown.get("storageMedia")).put("imageTier", "4K");
    assertThrows(
        IllegalStateException.class, () -> codec.decode(mapper.writeValueAsString(unknown)));

    ObjectNode missing = (ObjectNode) mapper.readTree(canonical);
    ((ObjectNode) missing.get("storageMedia")).remove("imageTier");
    assertThrows(
        IllegalStateException.class, () -> codec.decode(mapper.writeValueAsString(missing)));

    // 合法档位值可以往返，且聚合内部始终是强类型枚举。
    ObjectNode renamed = (ObjectNode) mapper.readTree(canonical);
    ((ObjectNode) renamed.get("storageMedia")).put("imageTier", "1080P");
    assertEquals(
        ImageInputTier.P1080,
        codec.decode(mapper.writeValueAsString(renamed)).storageMedia().imageTier());
  }

  @Test
  void omittedNullableBackendNetworkProxyStillRoundTrips() {
    // 唯一的可空引用是 backendNetwork.proxyUrl（null 即明确直连）：canonical 编码必须省略该字段，严格解码不得误报缺失，
    // 且重编码保持幂等。
    SystemSettings settings =
        new SystemSettings(
            SystemSettingsTestFixture.DEFAULT.tool(),
            SystemSettingsTestFixture.DEFAULT.aiRuntime(),
            SystemSettingsTestFixture.DEFAULT.environment(),
            new SystemSettings.BackendNetwork(null, "localhost,127.*,::1"),
            SystemSettingsTestFixture.DEFAULT.storageMedia(),
            SystemSettingsTestFixture.DEFAULT.advanced());
    String canonical = codec.encode(settings);
    assertTrue(!canonical.contains("proxyUrl"), canonical);
    assertEquals(settings, codec.decode(canonical));
    assertEquals(canonical, codec.encode(codec.decode(canonical)));
  }

  /** 可空压缩模型引用：null 省略不误报缺失；显式 null 与非 null 引用（含组/变体形状）都精确往返。 */
  @Test
  void compactionModelRoundTripsAndOmitsNull() throws Exception {
    SystemSettings withModel =
        new SystemSettings(
            SystemSettingsTestFixture.DEFAULT.tool(),
            aiRuntimeWithCompactionModel(ModelSelection.model("m", "v"), 20_000),
            SystemSettingsTestFixture.DEFAULT.environment(),
            SystemSettingsTestFixture.DEFAULT.backendNetwork(),
            SystemSettingsTestFixture.DEFAULT.storageMedia(),
            SystemSettingsTestFixture.DEFAULT.advanced());
    String canonical = codec.encode(withModel);
    assertTrue(
        canonical.contains("\"compactionModel\":{\"modelName\":\"m\",\"variant\":\"v\"}"),
        canonical);
    assertEquals(withModel, codec.decode(canonical));
    // 组引用没有 variant：canonical 省略 variant 键。
    SystemSettings withGroup =
        new SystemSettings(
            SystemSettingsTestFixture.DEFAULT.tool(),
            aiRuntimeWithCompactionModel(ModelSelection.group("g"), 20_000),
            SystemSettingsTestFixture.DEFAULT.environment(),
            SystemSettingsTestFixture.DEFAULT.backendNetwork(),
            SystemSettingsTestFixture.DEFAULT.storageMedia(),
            SystemSettingsTestFixture.DEFAULT.advanced());
    String groupCanonical = codec.encode(withGroup);
    assertTrue(
        groupCanonical.contains("\"compactionModel\":{\"modelName\":\"g\"}"), groupCanonical);
    assertEquals(withGroup, codec.decode(groupCanonical));

    // 默认（null）canonical 省略该键；显式 null 解码回同一聚合。
    String defaultCanonical = codec.encode(SystemSettingsTestFixture.DEFAULT);
    assertTrue(!defaultCanonical.contains("compactionModel"), defaultCanonical);
    ObjectNode explicitNull = (ObjectNode) mapper.readTree(defaultCanonical);
    ((ObjectNode) explicitNull.get("aiRuntime")).set("compactionModel", mapper.nullNode());
    assertEquals(
        SystemSettingsTestFixture.DEFAULT, codec.decode(mapper.writeValueAsString(explicitNull)));
  }

  /** 存储边界拒绝非 canonical 的压缩模型引用：非对象、空对象与未知/缺失字段都是损坏数据。 */
  @Test
  void rejectsNonCanonicalCompactionModelShapes() throws Exception {
    String canonical = codec.encode(SystemSettingsTestFixture.DEFAULT);
    for (JsonNode invalid :
        List.of(
            mapper.getNodeFactory().textNode("m"),
            mapper.getNodeFactory().numberNode(1),
            mapper.getNodeFactory().objectNode(),
            mapper.readTree("{\"modelName\":\"m\",\"variant\":\"v\",\"extra\":true}"),
            mapper.readTree("{\"variant\":\"v\"}"),
            mapper.readTree("{\"modelName\":\"\"}"),
            mapper.readTree("{\"modelName\":\" m \",\"variant\":null}"),
            mapper.readTree("{\"modelName\":1}"))) {
      ObjectNode root = (ObjectNode) mapper.readTree(canonical);
      ((ObjectNode) root.get("aiRuntime")).set("compactionModel", invalid);
      assertThrows(
          IllegalStateException.class,
          () -> codec.decode(mapper.writeValueAsString(root)),
          String.valueOf(invalid));
    }
  }

  /** DTO 边界：缺少必填 token 字段给出字段级错误；压缩模型引用沿用领域校验。 */
  @Test
  void fromDtoRequiresCompactionTokenFieldsAndValidatesModelSelection() {
    SystemSettingsSectionsDTO sections = codec.toSections(SystemSettingsTestFixture.DEFAULT);
    sections.getAiRuntime().setCompactionKeepRecentTokens(null);
    IllegalArgumentException missingKeep =
        assertThrows(IllegalArgumentException.class, () -> codec.fromDto(sections));
    assertEquals("aiRuntime.compactionKeepRecentTokens is required", missingKeep.getMessage());
    sections.getAiRuntime().setCompactionKeepRecentTokens(20_000);

    // 组引用没有 variant；wire 形状与 Agent 执行选择的 HarnessModelSelectionDTO 一致。
    HarnessModelSelectionDTO groupSelection = new HarnessModelSelectionDTO();
    groupSelection.setModelName("g");
    sections.getAiRuntime().setCompactionModel(groupSelection);
    SystemSettings withGroup = codec.fromDto(sections);
    assertEquals(ModelSelection.group("g"), withGroup.aiRuntime().compactionModel());
    assertEquals(sections, codec.toSections(withGroup));
  }

  @Test
  void explicitNullNullableReferencesStillDecode() throws Exception {
    // 显式 null 的 nullable 引用（语义等于 canonical 省略）必须仍可解码为同一聚合。
    ObjectNode root = (ObjectNode) mapper.readTree(codec.encode(SystemSettingsTestFixture.DEFAULT));
    ((ObjectNode) root.get("backendNetwork")).set("proxyUrl", mapper.nullNode());
    assertEquals(SystemSettingsTestFixture.DEFAULT, codec.decode(mapper.writeValueAsString(root)));
  }

  @Test
  void rejectsNonCanonicalEnumValues() throws Exception {
    ObjectNode invalidStrategy =
        (ObjectNode) mapper.readTree(codec.encode(SystemSettingsTestFixture.DEFAULT));
    ((ObjectNode) invalidStrategy.get("aiRuntime")).put("retryBackoffStrategy", "exponential");
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode(mapper.writeValueAsString(invalidStrategy)));

    ObjectNode invalidPermission =
        (ObjectNode) mapper.readTree(codec.encode(SystemSettingsTestFixture.DEFAULT));
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
    SystemSettingsSectionsDTO sections = codec.toSections(SystemSettingsTestFixture.DEFAULT);
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
    SystemSettingsSectionsDTO sections = codec.toSections(SystemSettingsTestFixture.DEFAULT);
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
      SystemSettingsSectionsDTO sections = codec.toSections(SystemSettingsTestFixture.DEFAULT);
      SystemSettingsToolDTO.PermissionRuleDTO rule = new SystemSettingsToolDTO.PermissionRuleDTO();
      rule.setPattern("*");
      rule.setAction("ask");
      sections.getTool().getPermission().put(key, List.of(rule));
      assertThrows(IllegalArgumentException.class, () -> codec.fromDto(sections), key);
    }

    SystemSettingsSectionsDTO sections = codec.toSections(SystemSettingsTestFixture.DEFAULT);
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
    SystemSettingsSectionsDTO sections = codec.toSections(SystemSettingsTestFixture.DEFAULT);
    SystemSettingsToolDTO tool = sections.getTool();
    assertEquals(SystemSettingsTestFixture.DEFAULT, codec.fromDto(sections));

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

    SystemSettingsBackendNetworkDTO backendNetwork = sections.getBackendNetwork();
    sections.setBackendNetwork(null);
    IllegalArgumentException missingSection =
        assertThrows(IllegalArgumentException.class, () -> codec.fromDto(sections));
    assertTrue(
        missingSection.getMessage().contains("backendNetwork is required"),
        missingSection.getMessage());
    sections.setBackendNetwork(backendNetwork);
    assertThrows(IllegalArgumentException.class, () -> codec.fromDto(null));
    assertEquals(SystemSettingsTestFixture.DEFAULT, codec.fromDto(sections));
  }

  @Test
  void fromDtoRejectsInvalidValuesWithFieldQualifiedMessages() {
    SystemSettingsSectionsDTO sections = codec.toSections(SystemSettingsTestFixture.DEFAULT);
    sections.getAdvanced().setProcessorHeartbeatIntervalMillis(60_000L);
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> codec.fromDto(sections));
    assertTrue(error.getMessage().contains("processorHeartbeatIntervalMillis"), error.getMessage());
  }

  private SystemSettings withToolPermission(Map<String, List<PermissionRule>> permission) {
    SystemSettings.Tool base = SystemSettingsTestFixture.DEFAULT.tool();
    return new SystemSettings(
        new SystemSettings.Tool(
            permission,
            base.maxOutputMib(),
            base.modelGatewayBusyRetryMillis(),
            base.toolGatewayOverloadRetryMillis()),
        SystemSettingsTestFixture.DEFAULT.aiRuntime(),
        SystemSettingsTestFixture.DEFAULT.environment(),
        SystemSettingsTestFixture.DEFAULT.backendNetwork(),
        SystemSettingsTestFixture.DEFAULT.storageMedia(),
        SystemSettingsTestFixture.DEFAULT.advanced());
  }

  /** 反转顶层六个 section 的声明顺序（保持 JSON 合法），验证解码不依赖键序。 */
  private static String reverseTopLevelKeys(String json) throws Exception {
    ObjectMapper mapper = JsonMapper.builder().build();
    ObjectNode root = (ObjectNode) mapper.readTree(json);
    ObjectNode reordered = mapper.createObjectNode();
    List<String> names = new ArrayList<>();
    root.propertyNames().forEach(names::add);
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

  private String withStorageMediaField(String canonical, String field, JsonNode value)
      throws Exception {
    ObjectNode root = (ObjectNode) mapper.readTree(canonical);
    ((ObjectNode) root.get("storageMedia")).set(field, value);
    return mapper.writeValueAsString(root);
  }

  /** 以默认 aiRuntime 为基座替换压缩模型引用与保留 token 预算，减少逐字段构造噪音。 */
  private static SystemSettings.AiRuntime aiRuntimeWithCompactionModel(
      ModelSelection compactionModel, int keepRecentTokens) {
    SystemSettings.AiRuntime base = SystemSettingsTestFixture.DEFAULT.aiRuntime();
    return new SystemSettings.AiRuntime(
        base.retryMaxRetries(),
        base.retryBackoffStrategy(),
        base.retryBaseDelayMillis(),
        base.retryMaxDelayMillis(),
        compactionModel,
        keepRecentTokens,
        base.subagentMaxDepth(),
        base.subagentMaxConcurrency(),
        base.subagentMaxTotalConcurrency(),
        base.subagentMaxTurns(),
        base.modelHttpRetryStatusCodes());
  }

  /**
   * 持久化 codec 使用独立 plain mapper：HTTP/convention 的 Long→十进制字符串不泄漏进 canonical JSON，时长仍是 JSON 数值
   * token；同时严格解码拒绝字符串→整数的隐式强制转换，保证严格 profile 校验。
   */
  @Test
  void canonicalProfileKeepsDurationsNumericAndStillRejectsStringCoercion() throws Exception {
    String canonical = codec.encode(SystemSettingsTestFixture.DEFAULT);
    assertFalse(canonical.contains("\"modelGatewayBusyRetryMillis\":\""));
    JsonNode tool = mapper.readTree(canonical).get("tool");
    assertTrue(tool.get("modelGatewayBusyRetryMillis").isIntegralNumber());

    ObjectNode root = (ObjectNode) mapper.readTree(canonical);
    ((ObjectNode) root.get("tool")).put("modelGatewayBusyRetryMillis", "1234");
    assertThrows(IllegalStateException.class, () -> codec.decode(root.toString()));
  }
}
