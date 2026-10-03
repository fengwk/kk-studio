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

/**
 * 测试意图：锁定 Parser 的条目级决策——不支持协议与未知字段 skip、嵌套 config 未知字段整体 skip、settings 未知叶剔除并保留受支持字段、 重复名（含被 skip
 * 条目）硬错、present-but-null 与 absent 区分。
 */
class ConfigSyncParserTest {

  private final ConfigSyncParser parser = new ConfigSyncParser(new ConfigSyncYaml());

  private static String provider(String name, String protocol) {
    return "  - name: " + name + "\n    providerType: " + protocol + "\n";
  }

  private static final String VALID_MODEL_CONFIG =
      "    config:\n"
          + "      limit: {context: 1000, output: 100}\n"
          + "      abilities: {tools: true, reasoning: false, inputModalities: [TEXT]}\n"
          + "      pricing:\n"
          + "        currency: USD\n"
          + "        pricingTier: t\n"
          + "        serviceTier: s\n"
          + "        serviceTierMultiplier: 1\n"
          + "        version: v\n"
          + "        inputPerMillionTokens: 0\n"
          + "        outputPerMillionTokens: 0\n"
          + "        cacheReadPerMillionTokens: 0\n"
          + "        cacheWritePerMillionTokens: 0\n"
          + "        cacheWriteLongPerMillionTokens: 0\n"
          + "        reasoningPerMillionTokens: 0\n"
          + "      defaultVariant: default\n"
          + "      variants:\n"
          + "        - id: default\n";

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
}
