package fun.fengwk.kkstudio.platform.configsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncKind;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncSkipped;

import java.util.List;
import java.util.Map;

/**
 * 测试意图：锁定 Parser 的条目级决策——不支持协议 skip、未知字段/嵌套 config 未知字段在通过 known 校验后才整体 skip、已知非法值或缺必填即使伴随未知字段
 * 也硬错、settings 完整六节校验且未知叶剔除并保留受支持 section、重复名（含被 skip 条目）硬错、present-but-null 与 absent
 * 区分；插件条目先严格校验字段与 未知字段，再判未安装 skip，已安装按插件 schema 解析。
 */
class ConfigSyncParserTest {

  private static final String PLUGIN_ID = "acme.plugin";

  private final ConfigSyncYaml yaml = new ConfigSyncYaml();
  private final ConfigSyncParser parser = ConfigSyncFixtures.parser(yaml);

  private ConfigSyncParser pluginParser() {
    return ConfigSyncFixtures.parser(
        yaml, ConfigSyncFixtures.registry(new ConfigSyncFixtures.AgentDependencyPlugin(PLUGIN_ID)));
  }

  private static String pluginYaml(String body) {
    return "plugins:\n  - pluginId: " + PLUGIN_ID + "\n" + body;
  }

  private static final String VALID_MODEL_CONFIG =
      ConfigSyncFixtures.resourceYaml("model-config-block.yaml");

  @Test
  void daemonConfigIsRequiredAndDeepKnownInvalidIsNotSkipped() {
    assertThrows(
        DomainException.class,
        () ->
            parser.parse(
                "environments:\n  - name: env\n    registrationTokenHash: f21d92ece713505af79fce6804dafd72b239be0d023924c022006c892bdf4d42\n"));
    String valid = ConfigSyncFixtures.resourceYaml("environment-card.yaml");
    var environment = parser.parse(valid).environments().get(0);
    assertNotNull(environment.daemonConfig());
    assertEquals("日常开发环境 'quoted'", environment.note());
    assertEquals("https://studio.example.com", environment.daemonConfig().getStudioUrl());
    for (String invalid :
        List.of(
            valid.replace("http://127.0.0.1:7890", "https://proxy:7890"),
            // bashExecutable 只接受非空白字符串：即使条目另含未知字段，空白值也必须硬错。
            valid.replace("bashExecutable: /bin/bash", "bashExecutable: \"  \""),
            valid.replace("\"pom.xml\"", "\"../outside\""),
            valid.replace("\".java\"", "\"java\""),
            valid.replace("https://studio.example.com/", "https://studio.example.com/private"))) {
      // 每个 mutation 都必须真正改动 fixture，否则断言会退化为对同一份合法文档的重复解析。
      assertNotEquals(valid, invalid, () -> "mutation must change the fixture: " + invalid);
      assertThrows(
          DomainException.class,
          () ->
              parser.parse(
                  invalid.replace("    daemonConfig:", "    bogus: true\n    daemonConfig:")));
    }
  }

  @Test
  void daemonConfigAndNoteAreParsedInCanonicalForm() {
    String yamlText = ConfigSyncFixtures.resourceYaml("environment-card-normalization.yaml");
    var environment = parser.parse(yamlText).environments().get(0);

    assertEquals("trimmed note", environment.note());
    assertEquals("https://studio.example.com", environment.daemonConfig().getStudioUrl());
    assertNull(environment.daemonConfig().getProxyUrl());
    assertEquals(
        List.of(".java"),
        environment.daemonConfig().getLsp().getServers().get("jdtls").getExtensions());
  }

  @Test
  void removedOldInstallShapeAndSettingsNetworkAreValidationFailures() {
    String oldEnvironment =
        "environments:\n"
            + "  - name: env\n"
            + "    registrationTokenHash: f21d92ece713505af79fce6804dafd72b239be0d023924c022006c892bdf4d42\n"
            + "    installConfig:\n"
            + "      operatingSystem: linux\n";
    assertThrows(DomainException.class, () -> parser.parse(oldEnvironment));
    assertThrows(
        DomainException.class,
        () -> parser.parse(oldEnvironment.replace("installConfig:", "daemonConfig:")));
    assertThrows(
        DomainException.class,
        () -> parser.parse("settings:\n  network:\n    proxyUrl: http://127.0.0.1:7890\n"));
    // 一般未知字段仍保持条目级 skip 语义，不扩大为所有未知字段都硬错。
    ConfigSyncParser.ParsedDocument skipped =
        parser.parse(
            ConfigSyncFixtures.resourceYaml("environment-card.yaml")
                .replace(
                    "    registrationTokenHash:", "    bogus: true\n    registrationTokenHash:"));
    assertTrue(skipped.environments().isEmpty());
    assertEquals(1, skipped.skipped().size());
  }

  @Test
  void unknownNestedDaemonConfigFieldSkipsTheWholeEntry() {
    ConfigSyncParser.ParsedDocument document =
        parser.parse(
            ConfigSyncFixtures.resourceYaml("environment-card.yaml")
                .replace(
                    "      bashExecutable: /bin/bash",
                    "      bashExecutable: /bin/bash\n      bogus: 1"));
    assertTrue(document.environments().isEmpty());
    assertEquals(ConfigSyncKind.ENVIRONMENTS, document.skipped().get(0).getKind());
  }

  private static String provider(String name, String protocol) {
    return "  - name: " + name + "\n    providerType: " + protocol + "\n";
  }

  @Test
  void providerHttpRetryOverrideIsParsedAsPresenceAwareList() {
    var document =
        parser.parse(
            "providers:\n"
                + "  - name: p\n"
                + "    providerType: openai\n"
                + "    modelHttpRetryStatusCodes: [500, 503]\n");

    var properties = document.providers().get(0).properties();
    assertEquals(List.of(500, 503), properties.getModelHttpRetryStatusCodes());
  }

  @Test
  void providerWithoutHttpRetryOverrideParsesAsClearedForImport() {
    var document = parser.parse("providers:\n" + provider("p", "openai"));

    var properties = document.providers().get(0).properties();
    assertNull(properties.getModelHttpRetryStatusCodes());
  }

  @Test
  void providerWithInvalidHttpRetryOverrideIsHardRejected() {
    assertThrows(
        DomainException.class,
        () ->
            parser.parse(
                "providers:\n"
                    + "  - name: p\n"
                    + "    providerType: openai\n"
                    + "    modelHttpRetryStatusCodes: [600]\n"));
    assertThrows(
        DomainException.class,
        () ->
            parser.parse(
                "providers:\n"
                    + "  - name: p\n"
                    + "    providerType: openai\n"
                    + "    modelHttpRetryStatusCodes: [429, 429]\n"));
    assertThrows(
        DomainException.class,
        () ->
            parser.parse(
                "providers:\n"
                    + "  - name: p\n"
                    + "    providerType: openai\n"
                    + "    modelHttpRetryStatusCodes: 429\n"));
  }

  @Test
  void paddedNamesCannotBypassDuplicateIdentityValidation() {
    // 目录服务会 strip 名称；文件不能用带空白的别名绕过同名条目的硬错误。
    assertThrows(
        DomainException.class,
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
      DomainException error =
          assertThrows(DomainException.class, () -> parser.parse((String) fixture.get("yaml")));
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
    assertEquals(ConfigSyncKind.PROVIDERS, document.skipped().get(0).getKind());
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
            + "  - kind: MODEL\n    providerName: p\n"
            + "    name: m\n"
            + "    modelId: gpt\n"
            + VALID_MODEL_CONFIG
            + "      bogus: 1\n";
    ConfigSyncParser.ParsedDocument document = parser.parse(yaml);

    assertTrue(document.models().isEmpty());
    assertTrue(
        document.skipped().stream()
            .anyMatch(
                skip -> skip.getKind() == ConfigSyncKind.MODELS && "m".equals(skip.getName())));
  }

  @Test
  void nestedUnknownAgentConfigFieldSkipsWholeEntry() {
    String yaml =
        "agents:\n"
            + "  - name: a\n"
            + "    execution: {driver: builtin, settings: {modelName: m, variant: null}}\n"
            + "    config:\n"
            + "      tools: []\n"
            + "      skills: []\n"
            + "      subagents: []\n"
            + "      bogus: 1\n";
    ConfigSyncParser.ParsedDocument document = parser.parse(yaml);

    assertTrue(document.agents().isEmpty());
    assertTrue(
        document.skipped().stream()
            .anyMatch(
                skip -> skip.getKind() == ConfigSyncKind.AGENTS && "a".equals(skip.getName())));
  }

  @Test
  void settingsUnknownLeafIsPrunedAndReportedWhileSupportedSectionsRemain() {
    Map<String, Object> settings =
        (Map<String, Object>) yaml.toMap(ConfigSyncFixtures.defaultSettings(), "settings");
    ((Map<String, Object>) settings.get("tool")).put("bogus", 1);
    settings.put("unknownSection", Map.of("x", 1));
    // 未知设置段与其它未知字段同规则：明确报告为 unsupported field，绝不静默忽略。
    settings.put("extraSection", Map.of("comfyui", Map.of("enabled", true)));
    String yamlText = yaml.dump(Map.of("settings", settings));
    ConfigSyncParser.ParsedDocument document = parser.parse(yamlText);

    assertNotNull(document.settings());
    assertNotNull(document.settings().getTool());
    List<ConfigSyncSkipped> settingsSkips =
        document.skipped().stream()
            .filter(skip -> skip.getKind() == ConfigSyncKind.SETTINGS)
            .toList();
    assertEquals(3, settingsSkips.size());
    assertTrue(
        settingsSkips.stream().anyMatch(skip -> skip.getReason().contains("extraSection")),
        document.skipped().toString());
  }

  @Test
  void duplicateNameIsRejectedEvenWhenFirstEntryIsSkipped() {
    String yaml = "providers:\n" + provider("p", "unknown_protocol") + provider("p", "openai");
    assertThrows(DomainException.class, () -> parser.parse(yaml));
  }

  @Test
  void duplicateModelKeyIsRejected() {
    String yaml =
        "providers:\n"
            + provider("p", "openai")
            + "models:\n"
            + "  - kind: MODEL\n    providerName: p\n"
            + "    name: m\n"
            + "    modelId: gpt\n"
            + VALID_MODEL_CONFIG
            + "  - kind: MODEL\n    providerName: p\n"
            + "    name: m\n"
            + "    modelId: gpt2\n"
            + VALID_MODEL_CONFIG;
    assertThrows(DomainException.class, () -> parser.parse(yaml));
  }

  @Test
  void presentButNullCategoryIsATypeError() {
    assertThrows(DomainException.class, () -> parser.parse("providers:\n"));
    assertThrows(DomainException.class, () -> parser.parse("settings:\n"));
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
    // 未知顶层类别不属于任何配置集合：kind 为空，原始类别名保留在 name 中。
    assertNull(document.skipped().get(0).getKind());
    assertEquals("bogus", document.skipped().get(0).getName());
  }

  @Test
  void wrongProviderTypeValueTypeIsAHardError() {
    assertThrows(
        DomainException.class,
        () -> parser.parse("providers:\n  - name: p\n    providerType: 7\n"));
  }

  @Test
  void invalidRepositoryUrlIsAHardError() {
    String yaml =
        "skillPackages:\n"
            + "  - packageName: skill\n"
            + "    repositoryUrl: not-a-valid-url\n"
            + "    branch: main\n";
    assertThrows(DomainException.class, () -> parser.parse(yaml));
  }

  @Test
  void missingRequiredFieldIsAHardError() {
    assertThrows(
        DomainException.class, () -> parser.parse("providers:\n  - providerType: openai\n"));
  }

  @Test
  void validProviderAndModelParse() {
    String yaml =
        "providers:\n"
            + provider("p", "openai")
            + "models:\n"
            + "  - kind: MODEL\n    providerName: p\n"
            + "    name: m\n"
            + "    modelId: gpt\n"
            + VALID_MODEL_CONFIG;
    ConfigSyncParser.ParsedDocument document = parser.parse(yaml);

    assertEquals(1, document.providers().size());
    assertEquals(1, document.models().size());
    assertEquals("p", document.providers().get(0).name());
    assertEquals("m", document.models().get(0).name());
  }

  /** 模型 config 的可选 limit.input 是 known 字段：合法值随 config 解析，非法值走硬校验拒绝。 */
  @Test
  void modelConfigInputLimitRoundTripsThroughKnownValidation() {
    // fixture limit: {context: 1000, output: 100}；合法 input 追加为普通整数字段。
    String yaml =
        "providers:\n"
            + provider("p", "openai")
            + "models:\n"
            + "  - kind: MODEL\n    providerName: p\n"
            + "    name: m\n"
            + "    modelId: gpt\n"
            + VALID_MODEL_CONFIG.replace(
                "      limit: {context: 1000, output: 100}",
                "      limit: {context: 1000, input: 100, output: 100}");
    ConfigSyncParser.ParsedDocument document = parser.parse(yaml);

    assertEquals(
        List.of(),
        document.skipped().stream().map(Object::toString).toList(),
        () -> "model entry must not be skipped: " + yaml);
    assertEquals(1, document.models().size());
    assertEquals(100, document.models().get(0).config().getLimit().getInput());

    // input 超过 context 属于已知非法值：必须硬拒绝，而不是当作未知字段静默 skip。
    String invalidInputYaml =
        "providers:\n"
            + provider("p", "openai")
            + "models:\n"
            + "  - kind: MODEL\n    providerName: p\n"
            + "    name: m\n"
            + "    modelId: gpt\n"
            + VALID_MODEL_CONFIG.replace(
                "      limit: {context: 1000, output: 100}",
                "      limit: {context: 1000, input: 1001, output: 100}");
    assertNotEquals(yaml, invalidInputYaml, "invalid mutation must change the document");
    assertThrows(DomainException.class, () -> parser.parse(invalidInputYaml));
  }

  /** 意图：类别不是 list、条目不是 map 都属于结构错误而非条目 skip。 */
  @Test
  void nonListCategoryAndNonMapEntryAreHardErrors() {
    assertThrows(DomainException.class, () -> parser.parse("providers: {}\n"));
    assertThrows(DomainException.class, () -> parser.parse("providers:\n  - 1\n"));
  }

  /** 意图：每个类别的条目级未声明顶层字段都整体 skip（不静默丢弃字段继续导入）。 */
  @Test
  void unknownEntryFieldsSkipEachCategory() {
    ConfigSyncParser.ParsedDocument model =
        parser.parse(
            "providers:\n"
                + provider("p", "openai")
                + "models:\n"
                + "  - kind: MODEL\n    providerName: p\n"
                + "    name: m\n"
                + "    modelId: gpt\n"
                + VALID_MODEL_CONFIG
                + "    bogus: 1\n");
    assertTrue(model.models().isEmpty());

    ConfigSyncParser.ParsedDocument agent =
        parser.parse(
            "agents:\n"
                + "  - name: a\n"
                + "    execution: {driver: builtin, settings: {modelName: m, variant: null}}\n"
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
                + "    bogus: 1\n");
    assertTrue(skill.skillPackages().isEmpty());

    ConfigSyncParser.ParsedDocument environment =
        parser.parse(
            "environments:\n"
                + "  - name: env\n"
                + "    registrationTokenHash: 1a7674eb4ee78df7e1ac439a93c3fa8e3c945784d4dec9fd8e3011738b2f1d62\n"
                + "    daemonConfig:\n"
                + "      studioUrl: https://studio.example.com\n"
                + "      bashExecutable: null\n"
                + "      lsp: null\n"
                + "      proxyUrl: null\n"
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

  /** Agent 条目不再识别旧 model/variant 字段：它们与其它未知字段同规则，整条 skip 并报告字段名。 */
  @Test
  void legacyAgentModelAndVariantAreUnknownFields() {
    ConfigSyncParser.ParsedDocument parsed =
        parser.parse(
            "agents:\n"
                + "  - name: a\n"
                + "    model: legacy-model\n"
                + "    variant: legacy-variant\n"
                + "    execution: {driver: builtin, settings: {modelName: m, variant: null}}\n"
                + "    config:\n"
                + "      tools: []\n"
                + "      skills: []\n"
                + "      subagents: []\n");
    assertTrue(parsed.agents().isEmpty());
    assertEquals(1, parsed.skipped().size());
    assertEquals(ConfigSyncKind.AGENTS, parsed.skipped().get(0).getKind());
    assertTrue(parsed.skipped().get(0).getReason().contains("unsupported field"));
  }

  @Test
  void currentCommitInSkillPackageIsSkippedAsUnknownField() {
    ConfigSyncParser.ParsedDocument skill =
        parser.parse(
            "skillPackages:\n"
                + "  - packageName: pkg\n"
                + "    repositoryUrl: https://example.com/pkg.git\n"
                + "    branch: main\n"
                + "    currentCommit: "
                + "a".repeat(40)
                + "\n");
    assertTrue(skill.skillPackages().isEmpty());
    assertEquals(1, skill.skipped().size());
    assertEquals(ConfigSyncKind.SKILL_PACKAGES, skill.skipped().get(0).getKind());
    assertTrue(skill.skipped().get(0).getReason().contains("currentCommit"));
  }

  /** 未知嵌套 config 字段不能掩盖当前必填字段缺失：只有 obsolete、缺 limit/abilities/pricing/variants 必须硬拒绝。 */
  @Test
  void unknownNestedConfigWithMissingMandatoryFieldsIsRejected() {
    String yaml =
        "providers:\n"
            + provider("p", "openai")
            + "models:\n"
            + "  - kind: MODEL\n    providerName: p\n"
            + "    name: m\n"
            + "    modelId: gpt\n"
            + "    config:\n"
            + "      obsolete: 1\n";
    assertThrows(DomainException.class, () -> parser.parse(yaml));
  }

  /** 未知条目字段不能掩盖 known 非法值/缺必填：环境空 token、MCP 非法 url、model 空 modelId 都必须硬拒绝。 */
  @Test
  void unknownEntryFieldWithKnownInvalidValueIsRejected() {
    assertThrows(
        DomainException.class,
        () ->
            parser.parse(
                "environments:\n"
                    + "  - name: env\n"
                    + "    registrationTokenHash: ''\n"
                    + "    daemonConfig:\n"
                    + "      studioUrl: https://studio.example.com\n"
                    + "      bashExecutable: null\n"
                    + "      lsp: null\n"
                    + "      proxyUrl: null\n"
                    + "    bogus: 1\n"));
    assertThrows(
        DomainException.class,
        () -> parser.parse("mcpServers:\n  - name: mcp\n    url: not-a-url\n    bogus: 1\n"));
    assertThrows(
        DomainException.class,
        () ->
            parser.parse(
                "providers:\n"
                    + provider("p", "openai")
                    + "models:\n"
                    + "  - kind: MODEL\n    providerName: p\n"
                    + "    name: m\n"
                    + "    modelId: ''\n"
                    + "    config: {}\n"
                    + "    bogus: 1\n"));
  }

  /** 内置执行选择保留全局模型名称和 variant。 */
  @Test
  void agentModelRefUsesGlobalModelName() {
    ConfigSyncParser.ParsedDocument document =
        parser.parse(
            "agents:\n"
                + "  - name: a\n"
                + "    execution: {driver: builtin, settings: {modelName: bad, variant: fast}}\n"
                + "    config:\n"
                + "      tools: []\n"
                + "      skills: []\n"
                + "      subagents: []\n");

    assertEquals("bad", document.agents().get(0).builtinModel().modelName());
    assertEquals("fast", document.agents().get(0).builtinModel().variant());
  }

  @Test
  void agentConfigMustBeAnObject() {
    assertThrows(
        DomainException.class,
        () ->
            parser.parse(
                "agents:\n  - name: a\n"
                    + "    execution: {driver: builtin, settings: {modelName: m, variant: null}}\n"
                    + "    config: []\n"));
  }

  @Test
  void settingsMustBeAnObject() {
    assertThrows(DomainException.class, () -> parser.parse("settings:\n  - 1\n"));
  }

  /** 意图：optLong 只接受精确整数——小数、超 64 位大整数与字符串都必须拒绝，合法整数正常读取。 */
  @Test
  void optLongRejectsDecimalOversizedAndStringValues() {
    assertThrows(
        DomainException.class,
        () ->
            parser.parse(
                "providers:\n  - name: p\n    providerType: openai\n    modelCallTimeoutMillis: 1.5\n"));
    assertThrows(
        DomainException.class,
        () ->
            parser.parse(
                "providers:\n"
                    + "  - name: p\n"
                    + "    providerType: openai\n"
                    + "    modelCallTimeoutMillis: 99999999999999999999999999\n"));
    assertThrows(
        DomainException.class,
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
        DomainException.class,
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
        DomainException.class,
        () ->
            parser.parse(
                "mcpServers:\n  - name: mcp\n    url: https://mcp.example.com/mcp\n    headers: 5\n"));
    assertThrows(
        DomainException.class,
        () ->
            parser.parse(
                "mcpServers:\n"
                    + "  - name: mcp\n"
                    + "    url: https://mcp.example.com/mcp\n"
                    + "    headers:\n"
                    + "      Authorization: 5\n"));
  }

  /** 已安装插件的普通值按插件自身 schema 归一化；wire 只承载 pluginId 与 valuesJson。 */
  @Test
  void installedPluginEntryIsParsedAndNormalized() {
    var document =
        pluginParser().parse(pluginYaml("    valuesJson: '{\"promptAgentName\":\"h3\"}'\n"));

    assertEquals(1, document.plugins().size());
    assertEquals(PLUGIN_ID, document.plugins().get(0).pluginId());
    assertEquals("{\"promptAgentName\":\"h3\"}", document.plugins().get(0).valuesJson());
    assertTrue(document.skipped().isEmpty());
  }

  /** 未安装的 pluginId 在字段与未知字段都合法后按既有条目级 skip 模式上报，不加载也不合成插件。 */
  @Test
  void uninstalledPluginIsSkipped() {
    var document = parser.parse(pluginYaml("    valuesJson: '{}'\n"));

    assertTrue(document.plugins().isEmpty());
    assertEquals(1, document.skipped().size());
    assertEquals(ConfigSyncKind.PLUGINS, document.skipped().get(0).getKind());
    assertEquals(PLUGIN_ID, document.skipped().get(0).getName());
  }

  /** 条目上的未声明字段按条目级 skip 上报，与其它类别一致。 */
  @Test
  void unknownPluginEntryFieldIsSkipped() {
    var document = pluginParser().parse(pluginYaml("    revision: 5\n    valuesJson: '{}'\n"));

    assertTrue(document.plugins().isEmpty());
    assertEquals(1, document.skipped().size());
    assertEquals(ConfigSyncKind.PLUGINS, document.skipped().get(0).getKind());
  }

  /** 已声明的普通字段类型不符是硬错误，绝不被降级成 skip；附加未知字段也不能让它提前变成 skip。 */
  @Test
  void pluginEntryRejectsWrongFieldType() {
    assertThrows(
        DomainException.class,
        () -> pluginParser().parse(pluginYaml("    valuesJson: '{\"promptAgentName\":5}'\n")));
    assertThrows(
        DomainException.class,
        () ->
            pluginParser()
                .parse(pluginYaml("    revision: 5\n    valuesJson: '{\"promptAgentName\":5}'\n")));
  }

  /** 未声明（含秘密）字段不得借插件条目夹带：schema 拒绝额外字段，且错误文本不回显输入值。 */
  @Test
  void pluginEntryRejectsSecretLikeFieldWithoutEchoingValue() {
    String sentinel = "sk-sentinel-13579";
    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                pluginParser()
                    .parse(pluginYaml("    valuesJson: '{\"apiKey\":\"" + sentinel + "\"}'\n")));
    assertFalse(error.getMessage().contains(sentinel), "error must not echo the value");
  }

  /** valuesJson 结构非法或超 64 KiB 上限都是硬错误。 */
  @Test
  void pluginEntryRejectsMalformedOrOversizedValues() {
    assertThrows(
        DomainException.class, () -> pluginParser().parse(pluginYaml("    valuesJson: '[1,2]'\n")));
    assertThrows(
        DomainException.class,
        () -> pluginParser().parse(pluginYaml("    valuesJson: '{\"promptAgentName\":1}'\n")));
    String oversized = "x".repeat(66 * 1024);
    assertThrows(
        DomainException.class,
        () ->
            pluginParser()
                .parse(
                    pluginYaml("    valuesJson: '{\"promptAgentName\":\"" + oversized + "\"}'\n")));
  }

  /** 文件内重复 pluginId（含被 skip 的条目）是硬错误。 */
  @Test
  void duplicatePluginIdIsRejected() {
    assertThrows(
        DomainException.class,
        () ->
            pluginParser()
                .parse(
                    "plugins:\n"
                        + "  - pluginId: "
                        + PLUGIN_ID
                        + "\n    valuesJson: '{}'\n"
                        + "  - pluginId: "
                        + PLUGIN_ID
                        + "\n    valuesJson: '{}'\n"));
  }

  /** 缺 pluginId/valuesJson 或类型不符是硬错误。 */
  @Test
  void pluginEntryRequiresStrictFields() {
    assertThrows(
        DomainException.class, () -> pluginParser().parse("plugins:\n  - valuesJson: '{}'\n"));
    assertThrows(
        DomainException.class,
        () -> pluginParser().parse("plugins:\n  - pluginId: " + PLUGIN_ID + "\n"));
    assertThrows(
        DomainException.class, () -> pluginParser().parse(pluginYaml("    valuesJson: 5\n")));
  }
}
