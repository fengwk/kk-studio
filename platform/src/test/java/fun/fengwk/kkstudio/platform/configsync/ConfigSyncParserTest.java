package fun.fengwk.kkstudio.platform.configsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 测试意图：锁定 Parser 的条目级决策——不支持协议 skip、未知字段/嵌套 config 未知字段在通过 known 校验后才整体 skip、已知非法值或缺必填即使伴随未知字段
 * 也硬错、settings 完整七节校验且未知叶剔除并保留受支持 section、重复名（含被 skip 条目）硬错、present-but-null 与 absent 区分。
 */
class ConfigSyncParserTest {

  private final ConfigSyncYaml yaml = new ConfigSyncYaml();
  private final ConfigSyncParser parser = ConfigSyncFixtures.parser(yaml);

  private static final String VALID_MODEL_CONFIG =
      ConfigSyncFixtures.resourceYaml("model-config-block.yaml");

  private static String provider(String name, String protocol) {
    return "  - name: " + name + "\n    providerType: " + protocol + "\n";
  }

  @Test
  void paddedNamesCannotBypassDuplicateIdentityValidation() {
    // 目录服务会 strip 名称；文件不能用带空白的别名绕过同名条目的硬错误。
    assertThrows(
        AiValidationException.class,
        () -> parser.parse("providers:\n" + provider("p", "openai") + provider("' p '", "openai")));
  }

  @Test
  void everyConfigurationIdentityIsNonBlankAndUnpadded() {
    // 各类身份在解析期校验，不能把错误推迟到依赖判断、Git/MCP 准备或数据库写入。
    List<?> cases =
        (List<?>)
            new ConfigSyncYaml()
                .parse(ConfigSyncFixtures.resourceYaml("invalid-identities.yaml"))
                .get("cases");
    for (Object value : cases) {
      Map<?, ?> fixture = (Map<?, ?>) value;
      AiValidationException error =
          assertThrows(
              AiValidationException.class, () -> parser.parse((String) fixture.get("yaml")));
      assertTrue(
          error.getMessage().contains(fixture.get("path") + " must be non-blank and unpadded"));
    }
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
  void settingsUnknownLeafIsPrunedAndReportedWhileSupportedSectionsRemain() {
    Map<String, Object> settings =
        (Map<String, Object>) yaml.toMap(ConfigSyncFixtures.defaultSettings(), "settings");
    ((Map<String, Object>) settings.get("tool")).put("bogus", 1);
    settings.put("unknownSection", Map.of("x", 1));
    String yamlText = yaml.dump(Map.of("settings", settings));
    ConfigSyncParser.ParsedDocument document = parser.parse(yamlText);

    assertNotNull(document.settings());
    assertNotNull(document.settings().getTool());
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

  /** 未知嵌套 config 字段不能掩盖当前必填字段缺失：只有 obsolete、缺 limit/abilities/pricing/variants 必须硬拒绝。 */
  @Test
  void unknownNestedConfigWithMissingMandatoryFieldsIsRejected() {
    String yaml =
        "providers:\n"
            + provider("p", "openai")
            + "models:\n"
            + "  - providerName: p\n"
            + "    name: m\n"
            + "    modelId: gpt\n"
            + "    config:\n"
            + "      obsolete: 1\n";
    assertThrows(AiValidationException.class, () -> parser.parse(yaml));
  }

  /** 未知条目字段不能掩盖 known 非法值/缺必填：环境空 token、MCP 非法 url、model 空 modelId 都必须硬拒绝。 */
  @Test
  void unknownEntryFieldWithKnownInvalidValueIsRejected() {
    assertThrows(
        AiValidationException.class,
        () ->
            parser.parse(
                "environments:\n  - name: env\n    registrationToken: ''\n    bogus: 1\n"));
    assertThrows(
        AiValidationException.class,
        () -> parser.parse("mcpServers:\n  - name: mcp\n    url: not-a-url\n    bogus: 1\n"));
    assertThrows(
        AiValidationException.class,
        () ->
            parser.parse(
                "providers:\n"
                    + provider("p", "openai")
                    + "models:\n"
                    + "  - providerName: p\n"
                    + "    name: m\n"
                    + "    modelId: ''\n"
                    + "    config: {}\n"
                    + "    bogus: 1\n"));
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
    Map<String, String> headers = document.mcpServers().get(0).config().headers();
    assertEquals("Bearer t", headers.get("Authorization"));
    // null header 值在规范化阶段归一化为空串。
    assertEquals("", headers.get("X-Null"));

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
