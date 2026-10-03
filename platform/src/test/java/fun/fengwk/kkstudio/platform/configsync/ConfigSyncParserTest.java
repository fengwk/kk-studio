package fun.fengwk.kkstudio.platform.configsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncSkipped;

import java.util.List;
import java.util.Map;

/**
 * 测试意图：锁定 Parser 的条目级决策——不支持协议与未知字段 skip、嵌套 config 未知字段整体 skip、settings 未知叶剔除并保留受支持字段、 重复名（含被 skip
 * 条目）硬错、present-but-null 与 absent 区分。
 */
class ConfigSyncParserTest {

  private final ConfigSyncParser parser = new ConfigSyncParser(new ConfigSyncYaml());

  private static final String VALID_MODEL_CONFIG =
      ConfigSyncFixtures.resourceYaml("model-config-block.yaml");

  private static String provider(String name, String protocol) {
    return "  - name: " + name + "\n    providerType: " + protocol + "\n";
  }

  @Test
  void unsupportedProviderProtocolIsSkippedNotFatal() {
    ConfigSyncParser.ParsedDocument document =
        parser.parse("providers:\n" + provider("p", "unknown_protocol"));

    assertTrue(document.providers().isEmpty());
    assertEquals(1, document.skipped().size());
    assertEquals("providers", document.skipped().get(0).getKind());
    assertEquals("p", document.skipped().get(0).getName());
  }

  @Test
  void unknownProviderFieldSkipsEntry() {
    ConfigSyncParser.ParsedDocument document =
        parser.parse("providers:\n  - name: p\n    providerType: openai\n    bogus: 1\n");

    assertTrue(document.providers().isEmpty());
    assertEquals(1, document.skipped().size());
  }

  @Test
  void nestedUnknownModelConfigFieldSkipsWholeEntry() {
    String yaml =
        "providers:\n"
            + provider("p", "openai")
            + "models:\n"
            + "  - providerName: p\n"
            + "    name: m\n"
            + "    modelId: gpt\n"
            + VALID_MODEL_CONFIG
            + "      bogus: 1\n";
    ConfigSyncParser.ParsedDocument document = parser.parse(yaml);

    assertTrue(document.models().isEmpty());
    assertTrue(
        document.skipped().stream()
            .anyMatch(skip -> "models".equals(skip.getKind()) && "p/m".equals(skip.getName())));
  }

  @Test
  void nestedUnknownAgentConfigFieldSkipsWholeEntry() {
    String yaml =
        "agents:\n"
            + "  - name: a\n"
            + "    model: p/m\n"
            + "    config:\n"
            + "      tools: []\n"
            + "      skills: []\n"
            + "      subagents: []\n"
            + "      bogus: 1\n";
    ConfigSyncParser.ParsedDocument document = parser.parse(yaml);

    assertTrue(document.agents().isEmpty());
    assertTrue(
        document.skipped().stream()
            .anyMatch(skip -> "agents".equals(skip.getKind()) && "a".equals(skip.getName())));
  }

  @Test
  void settingsUnknownLeafIsPrunedAndReportedWhileSupportedFieldsRemain() {
    String yaml =
        "settings:\n"
            + "  tool:\n"
            + "    defaultYolo: true\n"
            + "    bogus: 1\n"
            + "  unknownSection:\n"
            + "    x: 1\n";
    ConfigSyncParser.ParsedDocument document = parser.parse(yaml);

    assertNotNull(document.settings());
    assertTrue(document.settings().containsKey("tool"));
    assertFalse(document.settings().containsKey("unknownSection"));
    List<ConfigSyncSkipped> settingsSkips =
        document.skipped().stream().filter(skip -> "settings".equals(skip.getKind())).toList();
    assertEquals(2, settingsSkips.size());
  }

  @Test
  void duplicateNameIsRejectedEvenWhenFirstEntryIsSkipped() {
    String yaml = "providers:\n" + provider("p", "unknown_protocol") + provider("p", "openai");
    assertThrows(AiValidationException.class, () -> parser.parse(yaml));
  }

  @Test
  void duplicateModelKeyIsRejected() {
    String yaml =
        "providers:\n"
            + provider("p", "openai")
            + "models:\n"
            + "  - providerName: p\n"
            + "    name: m\n"
            + "    modelId: gpt\n"
            + VALID_MODEL_CONFIG
            + "  - providerName: p\n"
            + "    name: m\n"
            + "    modelId: gpt2\n"
            + VALID_MODEL_CONFIG;
    assertThrows(AiValidationException.class, () -> parser.parse(yaml));
  }

  @Test
  void presentButNullCategoryIsATypeError() {
    assertThrows(AiValidationException.class, () -> parser.parse("providers:\n"));
    assertThrows(AiValidationException.class, () -> parser.parse("settings:\n"));
  }

  @Test
  void absentCategoryIsAllowed() {
    ConfigSyncParser.ParsedDocument document = parser.parse("providers: []\n");
    assertTrue(document.providers().isEmpty());
    assertTrue(document.models().isEmpty());
    assertNull(document.settings());
    assertTrue(document.skipped().isEmpty());
  }

  @Test
  void unknownTopLevelCategoryIsReported() {
    ConfigSyncParser.ParsedDocument document = parser.parse("bogus: []\n");
    assertEquals(1, document.skipped().size());
    assertEquals("bogus", document.skipped().get(0).getKind());
  }

  @Test
  void wrongProviderTypeValueTypeIsAHardError() {
    assertThrows(
        AiValidationException.class,
        () -> parser.parse("providers:\n  - name: p\n    providerType: 7\n"));
  }

  @Test
  void invalidCommitIsAHardError() {
    String yaml =
        "skillPackages:\n"
            + "  - packageName: skill\n"
            + "    repositoryUrl: https://example.com/s.git\n"
            + "    branch: main\n"
            + "    currentCommit: deadbeef\n";
    assertThrows(AiValidationException.class, () -> parser.parse(yaml));
  }

  @Test
  void missingRequiredFieldIsAHardError() {
    assertThrows(
        AiValidationException.class, () -> parser.parse("providers:\n  - providerType: openai\n"));
  }

  @Test
  void validProviderAndModelParse() {
    String yaml =
        "providers:\n"
            + provider("p", "openai")
            + "models:\n"
            + "  - providerName: p\n"
            + "    name: m\n"
            + "    modelId: gpt\n"
            + VALID_MODEL_CONFIG;
    ConfigSyncParser.ParsedDocument document = parser.parse(yaml);

    assertEquals(1, document.providers().size());
    assertEquals(1, document.models().size());
    assertEquals("p", document.providers().get(0).name());
    assertEquals("m", document.models().get(0).name());
  }

  /** 意图：类别不是 list、条目不是 map 都属于结构错误而非条目 skip。 */
  @Test
  void nonListCategoryAndNonMapEntryAreHardErrors() {
    assertThrows(AiValidationException.class, () -> parser.parse("providers: {}\n"));
    assertThrows(AiValidationException.class, () -> parser.parse("providers:\n  - 1\n"));
  }

  /** 意图：每个类别的条目级未声明顶层字段都整体 skip（不静默丢弃字段继续导入）。 */
  @Test
  void unknownEntryFieldsSkipEachCategory() {
    ConfigSyncParser.ParsedDocument model =
        parser.parse(
            "providers:\n"
                + provider("p", "openai")
                + "models:\n"
                + "  - providerName: p\n"
                + "    name: m\n"
                + "    modelId: gpt\n"
                + VALID_MODEL_CONFIG
                + "    bogus: 1\n");
    assertTrue(model.models().isEmpty());

    ConfigSyncParser.ParsedDocument agent =
        parser.parse(
            "agents:\n"
                + "  - name: a\n"
                + "    model: p/m\n"
                + "    config:\n"
                + "      tools: []\n"
                + "      skills: []\n"
                + "      subagents: []\n"
                + "    bogus: 1\n");
    assertTrue(agent.agents().isEmpty());

    ConfigSyncParser.ParsedDocument skill =
        parser.parse(
            "skillPackages:\n"
                + "  - packageName: pkg\n"
                + "    repositoryUrl: https://example.com/pkg.git\n"
                + "    branch: main\n"
                + "    currentCommit: "
                + "a".repeat(40)
                + "\n"
                + "    bogus: 1\n");
    assertTrue(skill.skillPackages().isEmpty());

    ConfigSyncParser.ParsedDocument environment =
        parser.parse(
            "environments:\n"
                + "  - name: env\n"
                + "    registrationToken: tok\n"
                + "    bogus: 1\n");
    assertTrue(environment.environments().isEmpty());

    ConfigSyncParser.ParsedDocument mcp =
        parser.parse(
            "mcpServers:\n"
                + "  - name: mcp\n"
                + "    url: https://mcp.example.com/mcp\n"
                + "    bogus: 1\n");
    assertTrue(mcp.mcpServers().isEmpty());
  }

  @Test
  void agentModelRefWithoutSeparatorIsAHardError() {
    assertThrows(
        AiValidationException.class,
        () ->
            parser.parse(
                "agents:\n"
                    + "  - name: a\n"
                    + "    model: bad\n"
                    + "    config:\n"
                    + "      tools: []\n"
                    + "      skills: []\n"
                    + "      subagents: []\n"));
  }

  @Test
  void agentConfigMustBeAnObject() {
    assertThrows(
        AiValidationException.class,
        () -> parser.parse("agents:\n  - name: a\n    model: p/m\n    config: []\n"));
  }

  @Test
  void settingsMustBeAnObject() {
    assertThrows(AiValidationException.class, () -> parser.parse("settings:\n  - 1\n"));
  }

  /** 意图：optLong 只接受精确整数——小数、超 64 位大整数与字符串都必须拒绝，合法整数正常读取。 */
  @Test
  void optLongRejectsDecimalOversizedAndStringValues() {
    assertThrows(
        AiValidationException.class,
        () ->
            parser.parse(
                "providers:\n  - name: p\n    providerType: openai\n    modelCallTimeoutMillis: 1.5\n"));
    assertThrows(
        AiValidationException.class,
        () ->
            parser.parse(
                "providers:\n"
                    + "  - name: p\n"
                    + "    providerType: openai\n"
                    + "    modelCallTimeoutMillis: 99999999999999999999999999\n"));
    assertThrows(
        AiValidationException.class,
        () ->
            parser.parse(
                "providers:\n  - name: p\n    providerType: openai\n    modelCallTimeoutMillis: abc\n"));

    ConfigSyncParser.ParsedDocument document =
        parser.parse(
            "providers:\n  - name: p\n    providerType: openai\n    modelCallTimeoutMillis: 1000\n");
    assertEquals(1000L, document.providers().get(0).properties().getModelCallTimeoutMillis());
  }

  @Test
  void optBooleanRejectsNonBooleanValue() {
    assertThrows(
        AiValidationException.class,
        () ->
            parser.parse(
                "mcpServers:\n  - name: mcp\n    url: https://mcp.example.com/mcp\n    enabled: 1\n"));
  }

  /** 意图：optStringMap 只接受字符串键值并保留显式 null；非 map 或非字符串值必须拒绝。 */
  @Test
  void optStringMapValidatesShapeAndAllowsNullValues() {
    ConfigSyncParser.ParsedDocument document =
        parser.parse(
            "mcpServers:\n"
                + "  - name: mcp\n"
                + "    url: https://mcp.example.com/mcp\n"
                + "    headers:\n"
                + "      Authorization: Bearer t\n"
                + "      X-Null: null\n");
    Map<String, String> headers = document.mcpServers().get(0).headers();
    assertEquals("Bearer t", headers.get("Authorization"));
    assertNull(headers.get("X-Null"));

    assertThrows(
        AiValidationException.class,
        () ->
            parser.parse(
                "mcpServers:\n  - name: mcp\n    url: https://mcp.example.com/mcp\n    headers: 5\n"));
    assertThrows(
        AiValidationException.class,
        () ->
            parser.parse(
                "mcpServers:\n"
                    + "  - name: mcp\n"
                    + "    url: https://mcp.example.com/mcp\n"
                    + "    headers:\n"
                    + "      Authorization: 5\n"));
  }
}
