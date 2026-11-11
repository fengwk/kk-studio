package fun.fengwk.kkstudio.harness.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.api.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.codec.AgentToolDefinitionJsonCodec;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** 严格的 ToolBinding wire JSON 编解码测试。 */
class ToolBindingJsonCodecTest {

  private static final EnvironmentId ENVIRONMENT_ID =
      EnvironmentId.parse("123e4567-e89b-12d3-a456-426614174000");

  private final ToolBindingJsonCodec codec = new ToolBindingJsonCodec();
  private final AgentToolDefinitionJsonCodec definitionCodec = new AgentToolDefinitionJsonCodec();

  /** 精确 JSON 证明字段顺序 deterministic，并在 round-trip 时保证 NONE/ROUTED/REQUIRED 状态无损。 */
  @Test
  void roundTripsEveryBindingShapeWithCanonicalJson() {
    ToolBinding environment = binding(EnvironmentSupport.REQUIRED);
    String environmentJson =
        "{\"definition\":"
            + definitionCodec.encode(environment.definition())
            + ",\"contributor\":"
            + "{\"contributorId\":\"core\",\"localName\":\"bash\"}"
            + ",\"environmentSupport\":\"REQUIRED\""
            + ",\"environmentId\":"
            + environmentJson(ENVIRONMENT_ID)
            + ",\"environmentName\":\"dev\""
            + "}";
    assertEquals(environmentJson, codec.encode(environment));
    assertEquals(environment, codec.decode(environmentJson));
    assertEquals(environment, codec.decodeNode(codec.encodeNode(environment)));

    ToolBinding host = binding(EnvironmentSupport.NONE);
    String hostJson =
        "{\"definition\":"
            + definitionCodec.encode(host.definition())
            + ",\"contributor\":"
            + "{\"contributorId\":\"core\",\"localName\":\"bash\"}"
            + ",\"environmentSupport\":\"NONE\""
            + ",\"environmentId\":null"
            + ",\"environmentName\":null"
            + "}";
    assertEquals(hostJson, codec.encode(host));
    assertEquals(host, codec.decode(hostJson));
    assertEquals(EnvironmentSupport.NONE, codec.decode(hostJson).environmentSupport());
    assertNull(codec.decode(hostJson).environmentId());
    assertNull(codec.decode(hostJson).environmentName());

    ToolBinding optionalWithoutEnv = binding(EnvironmentSupport.ROUTED);
    String optionalWithoutEnvJson =
        "{\"definition\":"
            + definitionCodec.encode(optionalWithoutEnv.definition())
            + ",\"contributor\":"
            + "{\"contributorId\":\"core\",\"localName\":\"bash\"}"
            + ",\"environmentSupport\":\"ROUTED\""
            + ",\"environmentId\":null"
            + ",\"environmentName\":null"
            + "}";
    assertEquals(optionalWithoutEnvJson, codec.encode(optionalWithoutEnv));
    assertEquals(optionalWithoutEnv, codec.decode(optionalWithoutEnvJson));
    assertEquals(
        EnvironmentSupport.ROUTED, codec.decode(optionalWithoutEnvJson).environmentSupport());
    assertNull(codec.decode(optionalWithoutEnvJson).environmentId());
    assertNull(codec.decode(optionalWithoutEnvJson).environmentName());

    ToolBinding optionalWithEnv =
        new ToolBinding(
            environment.definition(),
            environment.contributor(),
            EnvironmentSupport.ROUTED,
            ENVIRONMENT_ID,
            "dev");
    String optionalWithEnvJson =
        "{\"definition\":"
            + definitionCodec.encode(optionalWithEnv.definition())
            + ",\"contributor\":"
            + "{\"contributorId\":\"core\",\"localName\":\"bash\"}"
            + ",\"environmentSupport\":\"ROUTED\""
            + ",\"environmentId\":"
            + environmentJson(ENVIRONMENT_ID)
            + ",\"environmentName\":\"dev\""
            + "}";
    assertEquals(optionalWithEnvJson, codec.encode(optionalWithEnv));
    assertEquals(optionalWithEnv, codec.decode(optionalWithEnvJson));
    assertEquals(EnvironmentSupport.ROUTED, codec.decode(optionalWithEnvJson).environmentSupport());
    assertEquals(ENVIRONMENT_ID, codec.decode(optionalWithEnvJson).environmentId());
    assertEquals("dev", codec.decode(optionalWithEnvJson).environmentName());

    ToolBinding requiredWithGoalContributor =
        new ToolBinding(
            definition("fs"),
            new ContributorBinding("goal", "create"),
            EnvironmentSupport.REQUIRED,
            ENVIRONMENT_ID,
            "dev");
    String requiredJson =
        "{\"definition\":"
            + definitionCodec.encode(requiredWithGoalContributor.definition())
            + ",\"contributor\":"
            + "{\"contributorId\":\"goal\",\"localName\":\"create\"}"
            + ",\"environmentSupport\":\"REQUIRED\""
            + ",\"environmentId\":"
            + environmentJson(ENVIRONMENT_ID)
            + ",\"environmentName\":\"dev\""
            + "}";
    assertEquals(requiredJson, codec.encode(requiredWithGoalContributor));
    assertEquals(requiredWithGoalContributor, codec.decode(requiredJson));
    assertEquals(EnvironmentSupport.REQUIRED, codec.decode(requiredJson).environmentSupport());
    assertEquals(ENVIRONMENT_ID, codec.decode(requiredJson).environmentId());
  }

  /** 字符串边界在任何领域值构造之前拒绝畸形文档。 */
  @Test
  void rejectsNullDuplicateTrailingAndNonObjectDocuments() {
    String json = codec.encode(binding(EnvironmentSupport.NONE));
    String duplicate =
        json.replace(
            "\"environmentId\":null",
            "\"environmentId\":null,\"environmentId\":\"22222222-2222-2222-2222-222222222222\"");

    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(NullPointerException.class, () -> codec.decodeNode(null));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("null"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(json + " {}"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicate));
  }

  /** 顶层字段必须恰好是 definition/contributor/environmentSupport/environmentId/environmentName。 */
  @Test
  void rejectsUnknownMissingAndWrongTypeTopLevelFields() {
    ToolBinding environment = binding(EnvironmentSupport.REQUIRED);
    String definition = definitionCodec.encode(environment.definition());
    String valid =
        "{\"definition\":"
            + definition
            + ",\"contributor\":{\"contributorId\":\"core\",\"localName\":\"bash\"}"
            + ",\"environmentSupport\":\"REQUIRED\""
            + ",\"environmentId\":"
            + environmentJson(ENVIRONMENT_ID)
            + ",\"environmentName\":\"dev\""
            + "}";

    // 未知字段
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.substring(0, valid.length() - 1) + ",\"extra\":true}"));

    // 缺少必要字段
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"definition\":" + definition + ",", "")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                valid.replace(
                    ",\"contributor\":{\"contributorId\":\"core\",\"localName\":\"bash\"}", "")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace(",\"environmentSupport\":\"REQUIRED\"", "")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                valid.replace(",\"environmentId\":" + environmentJson(ENVIRONMENT_ID), "")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace(",\"environmentName\":\"dev\"", "")));

    // 字段类型与范围错误

    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"definition\":" + definition, "\"definition\":1")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                valid.replace("{\"contributorId\":\"core\",\"localName\":\"bash\"}", "1")));
    // environmentSupport 为布尔值或未知枚举值必须被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                valid.replace(
                    "\"environmentSupport\":\"REQUIRED\"", "\"environmentSupport\":true")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                valid.replace(
                    "\"environmentSupport\":\"REQUIRED\"", "\"environmentSupport\":\"UNKNOWN\"")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace(environmentJson(ENVIRONMENT_ID), "1")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"environmentName\":\"dev\"", "\"environmentName\":1")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"definition\":" + definition, "\"definition\":null")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                valid.replace("{\"contributorId\":\"core\",\"localName\":\"bash\"}", "null")));
  }

  /** Contributor 对象内部字段校验：必须是 contributorId/localName，且拒绝未知字段。 */
  @Test
  void rejectsInvalidContributorFields() {
    ToolBinding host = binding(EnvironmentSupport.NONE);
    String definition = definitionCodec.encode(host.definition());

    // 未知字段
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definition
                    + ",\"contributor\":{\"contributorId\":\"goal\",\"localName\":\"create\",\"stateAccesses\":[],\"unknown\":true}"
                    + ",\"environmentSupport\":\"NONE\""
                    + ",\"environmentId\":null"
                    + ",\"environmentName\":null"
                    + "}"));

    // 缺少必要字段
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definition
                    + ",\"contributor\":{\"contributorId\":\"goal\"}"
                    + ",\"environmentSupport\":\"NONE\""
                    + ",\"environmentId\":null"
                    + ",\"environmentName\":null"
                    + "}"));
  }

  /** 解码边界必须重新校验 environmentSupport 与 environmentId/environmentName 的合法组合。 */
  @Test
  void rejectsMismatchedEnvironmentRequiredAndEnvironmentCombinations() {
    ToolBinding host = binding(EnvironmentSupport.NONE);
    String definition = definitionCodec.encode(host.definition());
    String emptyContributorJson = "{\"contributorId\":\"core\",\"localName\":\"bash\"}";
    String prefix = "{\"definition\":" + definition + ",\"contributor\":" + emptyContributorJson;
    String suffix = "}";

    // REQUIRED 缺 environmentId 或缺 environmentName 在解码构造边界必须被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                prefix
                    + ",\"environmentSupport\":\"REQUIRED\""
                    + ",\"environmentId\":null"
                    + ",\"environmentName\":null"
                    + suffix));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                prefix
                    + ",\"environmentSupport\":\"REQUIRED\""
                    + ",\"environmentId\":"
                    + environmentJson(ENVIRONMENT_ID)
                    + ",\"environmentName\":null"
                    + suffix));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                prefix
                    + ",\"environmentSupport\":\"REQUIRED\""
                    + ",\"environmentId\":null"
                    + ",\"environmentName\":\"dev\""
                    + suffix));

    // NONE 携带 environmentId 被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                prefix
                    + ",\"environmentSupport\":\"NONE\""
                    + ",\"environmentId\":"
                    + environmentJson(ENVIRONMENT_ID)
                    + ",\"environmentName\":null"
                    + suffix));

    // NONE 携带 environmentName 被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                prefix
                    + ",\"environmentSupport\":\"NONE\""
                    + ",\"environmentId\":null"
                    + ",\"environmentName\":\"dev\""
                    + suffix));

    // ROUTED 只提供 id 不提供 name 被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                prefix
                    + ",\"environmentSupport\":\"ROUTED\""
                    + ",\"environmentId\":"
                    + environmentJson(ENVIRONMENT_ID)
                    + ",\"environmentName\":null"
                    + suffix));

    // ROUTED 只提供 name 不提供 id 被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                prefix
                    + ",\"environmentSupport\":\"ROUTED\""
                    + ",\"environmentId\":null"
                    + ",\"environmentName\":\"dev\""
                    + suffix));

    // environmentName 为空白字符串被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                prefix
                    + ",\"environmentSupport\":\"REQUIRED\""
                    + ",\"environmentId\":"
                    + environmentJson(ENVIRONMENT_ID)
                    + ",\"environmentName\":\"   \""
                    + suffix));
  }

  private static ToolBinding binding(EnvironmentSupport environmentSupport) {
    ContributorBinding contributor = new ContributorBinding("core", "bash");
    boolean hasEnv = environmentSupport == EnvironmentSupport.REQUIRED;
    return new ToolBinding(
        definition(hasEnv ? "fs" : "bash"),
        contributor,
        environmentSupport,
        hasEnv ? ENVIRONMENT_ID : null,
        hasEnv ? "dev" : null);
  }

  private static AgentToolDefinition definition(String name) {
    return new AgentToolDefinition(
        new ToolDescriptor(
            name,
            "Run " + name,
            name,
            new InputSchema("arguments", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(30)),
        ToolVisibility.SELECTABLE);
  }

  private static String environmentJson(EnvironmentId environmentId) {
    return "\"" + environmentId + "\"";
  }
}
