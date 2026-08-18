package fun.fengwk.kkstudio.core.systemsettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

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
    // 规则对象键与 tool 名也排序。
    assertTrue(canonical.indexOf("\"action\":\"ask\",\"pattern\":\"*\"") >= 0, canonical);
    // null 可空字段省略。
    assertTrue(!canonical.contains("subagentMaxTotalConcurrency"), canonical);
    assertTrue(!canonical.contains("workspaceId"), canonical);
    assertTrue(!canonical.contains("comfyui\":{\"baseUrl"), canonical);
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
}
