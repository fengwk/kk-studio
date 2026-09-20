package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.ENVIRONMENT_ID;
import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.binding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.codec.AgentToolDefinitionJsonCodec;

/** 严格的 ToolBinding wire JSON 编解码测试。 */
class ToolBindingJsonCodecTest {

  private final ToolBindingJsonCodec codec = new ToolBindingJsonCodec();
  private final AgentToolDefinitionJsonCodec definitionCodec = new AgentToolDefinitionJsonCodec();

  /** 精确 JSON 证明字段顺序 deterministic，并在 round-trip 时保证 NONE/OPTIONAL/REQUIRED 状态无损。 */
  @Test
  void roundTripsEveryBindingShapeWithCanonicalJson() {
    ToolBinding environment = binding(EnvironmentSupport.REQUIRED);
    String environmentJson =
        "{\"definition\":"
            + definitionCodec.encode(environment.definition())
            + ",\"contributor\":"
            + "{\"contributorId\":\"core\",\"localName\":\"bash\",\"stateAccesses\":[]}"
            + ",\"environmentSupport\":\"REQUIRED\""
            + ",\"environmentId\":"
            + environmentJson(ENVIRONMENT_ID)
            + ",\"environmentName\":\"dev\"}";
    assertEquals(environmentJson, codec.encode(environment));
    assertEquals(environment, codec.decode(environmentJson));
    assertEquals(environment, codec.decodeNode(codec.encodeNode(environment)));

    ToolBinding host = binding(EnvironmentSupport.NONE);
    String hostJson =
        "{\"definition\":"
            + definitionCodec.encode(host.definition())
            + ",\"contributor\":"
            + "{\"contributorId\":\"core\",\"localName\":\"bash\",\"stateAccesses\":[]}"
            + ",\"environmentSupport\":\"NONE\""
            + ",\"environmentId\":null"
            + ",\"environmentName\":null}";
    assertEquals(hostJson, codec.encode(host));
    assertEquals(host, codec.decode(hostJson));
    assertEquals(EnvironmentSupport.NONE, codec.decode(hostJson).environmentSupport());
    assertNull(codec.decode(hostJson).environmentId());
    assertNull(codec.decode(hostJson).environmentName());

    ToolBinding optionalWithoutEnv = binding(EnvironmentSupport.OPTIONAL);
    String optionalWithoutEnvJson =
        "{\"definition\":"
            + definitionCodec.encode(optionalWithoutEnv.definition())
            + ",\"contributor\":"
            + "{\"contributorId\":\"core\",\"localName\":\"bash\",\"stateAccesses\":[]}"
            + ",\"environmentSupport\":\"OPTIONAL\""
            + ",\"environmentId\":null"
            + ",\"environmentName\":null}";
    assertEquals(optionalWithoutEnvJson, codec.encode(optionalWithoutEnv));
    assertEquals(optionalWithoutEnv, codec.decode(optionalWithoutEnvJson));
    assertEquals(
        EnvironmentSupport.OPTIONAL, codec.decode(optionalWithoutEnvJson).environmentSupport());
    assertNull(codec.decode(optionalWithoutEnvJson).environmentId());
    assertNull(codec.decode(optionalWithoutEnvJson).environmentName());

    ToolBinding optionalWithEnv =
        new ToolBinding(
            environment.definition(),
            environment.contributor(),
            EnvironmentSupport.OPTIONAL,
            ENVIRONMENT_ID,
            "dev");
    String optionalWithEnvJson =
        "{\"definition\":"
            + definitionCodec.encode(optionalWithEnv.definition())
            + ",\"contributor\":"
            + "{\"contributorId\":\"core\",\"localName\":\"bash\",\"stateAccesses\":[]}"
            + ",\"environmentSupport\":\"OPTIONAL\""
            + ",\"environmentId\":"
            + environmentJson(ENVIRONMENT_ID)
            + ",\"environmentName\":\"dev\"}";
    assertEquals(optionalWithEnvJson, codec.encode(optionalWithEnv));
    assertEquals(optionalWithEnv, codec.decode(optionalWithEnvJson));
    assertEquals(
        EnvironmentSupport.OPTIONAL, codec.decode(optionalWithEnvJson).environmentSupport());
    assertEquals(ENVIRONMENT_ID, codec.decode(optionalWithEnvJson).environmentId());
    assertEquals("dev", codec.decode(optionalWithEnvJson).environmentName());

    ToolBinding declarative = binding(EnvironmentSupport.NONE, true);
    String declarativeJson =
        "{\"definition\":"
            + definitionCodec.encode(declarative.definition())
            + ",\"contributor\":"
            + "{\"contributorId\":\"goal\",\"localName\":\"create\","
            + "\"stateAccesses\":[{\"customType\":\"state\",\"mode\":\"WRITE\"}]}"
            + ",\"environmentSupport\":\"NONE\""
            + ",\"environmentId\":null"
            + ",\"environmentName\":null}";
    assertEquals(declarativeJson, codec.encode(declarative));
    assertEquals(declarative, codec.decode(declarativeJson));

    ToolBinding stateWithEnv = binding(EnvironmentSupport.REQUIRED, true);
    String stateWithEnvJson =
        "{\"definition\":"
            + definitionCodec.encode(stateWithEnv.definition())
            + ",\"contributor\":"
            + "{\"contributorId\":\"goal\",\"localName\":\"create\","
            + "\"stateAccesses\":[{\"customType\":\"state\",\"mode\":\"WRITE\"}]}"
            + ",\"environmentSupport\":\"REQUIRED\""
            + ",\"environmentId\":"
            + environmentJson(ENVIRONMENT_ID)
            + ",\"environmentName\":\"dev\"}";
    assertEquals(stateWithEnvJson, codec.encode(stateWithEnv));
    assertEquals(stateWithEnv, codec.decode(stateWithEnvJson));
    assertEquals(EnvironmentSupport.REQUIRED, codec.decode(stateWithEnvJson).environmentSupport());
    assertEquals(ENVIRONMENT_ID, codec.decode(stateWithEnvJson).environmentId());
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
            + ",\"contributor\":{\"contributorId\":\"core\",\"localName\":\"bash\",\"stateAccesses\":[]}"
            + ",\"environmentSupport\":\"REQUIRED\""
            + ",\"environmentId\":"
            + environmentJson(ENVIRONMENT_ID)
            + ",\"environmentName\":\"dev\"}";

    // 未知字段
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.substring(0, valid.length() - 1) + ",\"extra\":true}"));

    // 旧字段 environmentRequired 被严格拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                valid.substring(0, valid.length() - 1) + ",\"environmentRequired\":true}"));

    // 缺少必要字段
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"definition\":" + definition + ",", "")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                valid.replace(
                    ",\"contributor\":{\"contributorId\":\"core\",\"localName\":\"bash\",\"stateAccesses\":[]}",
                    "")));
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

    // 字段类型错误
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"definition\":" + definition, "\"definition\":1")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                valid.replace(
                    "{\"contributorId\":\"core\",\"localName\":\"bash\",\"stateAccesses\":[]}",
                    "1")));
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
                valid.replace(
                    "{\"contributorId\":\"core\",\"localName\":\"bash\",\"stateAccesses\":[]}",
                    "null")));
  }

  /** Contributor 对象内部字段校验：必须是 contributorId/localName/stateAccesses。 */
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
                    + ",\"environmentName\":null}"));

    // 缺少 stateAccesses
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definition
                    + ",\"contributor\":{\"contributorId\":\"goal\",\"localName\":\"create\"}"
                    + ",\"environmentSupport\":\"NONE\""
                    + ",\"environmentId\":null"
                    + ",\"environmentName\":null}"));

    // stateAccesses 元素格式非法
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definition
                    + ",\"contributor\":{\"contributorId\":\"goal\",\"localName\":\"create\",\"stateAccesses\":[{\"customType\":\"state\",\"mode\":\"INVALID\"}]}"
                    + ",\"environmentSupport\":\"NONE\""
                    + ",\"environmentId\":null"
                    + ",\"environmentName\":null}"));
  }

  /** 解码边界必须重新校验 environmentSupport 与 environmentId/environmentName 的合法组合。 */
  @Test
  void rejectsMismatchedEnvironmentRequiredAndEnvironmentCombinations() {
    ToolBinding host = binding(EnvironmentSupport.NONE);
    String definition = definitionCodec.encode(host.definition());
    String emptyContributorJson =
        "{\"contributorId\":\"core\",\"localName\":\"bash\",\"stateAccesses\":[]}";

    // REQUIRED 缺 environmentId 或缺 environmentName 在解码构造边界必须被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definition
                    + ",\"contributor\":"
                    + emptyContributorJson
                    + ",\"environmentSupport\":\"REQUIRED\""
                    + ",\"environmentId\":null"
                    + ",\"environmentName\":null}"));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definition
                    + ",\"contributor\":"
                    + emptyContributorJson
                    + ",\"environmentSupport\":\"REQUIRED\""
                    + ",\"environmentId\":"
                    + environmentJson(ENVIRONMENT_ID)
                    + ",\"environmentName\":null}"));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definition
                    + ",\"contributor\":"
                    + emptyContributorJson
                    + ",\"environmentSupport\":\"REQUIRED\""
                    + ",\"environmentId\":null"
                    + ",\"environmentName\":\"dev\"}"));

    // NONE 携带 environmentId 被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definition
                    + ",\"contributor\":"
                    + emptyContributorJson
                    + ",\"environmentSupport\":\"NONE\""
                    + ",\"environmentId\":"
                    + environmentJson(ENVIRONMENT_ID)
                    + ",\"environmentName\":null}"));

    // NONE 携带 environmentName 被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definition
                    + ",\"contributor\":"
                    + emptyContributorJson
                    + ",\"environmentSupport\":\"NONE\""
                    + ",\"environmentId\":null"
                    + ",\"environmentName\":\"dev\"}"));

    // OPTIONAL 只提供 id 不提供 name 被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definition
                    + ",\"contributor\":"
                    + emptyContributorJson
                    + ",\"environmentSupport\":\"OPTIONAL\""
                    + ",\"environmentId\":"
                    + environmentJson(ENVIRONMENT_ID)
                    + ",\"environmentName\":null}"));

    // OPTIONAL 只提供 name 不提供 id 被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definition
                    + ",\"contributor\":"
                    + emptyContributorJson
                    + ",\"environmentSupport\":\"OPTIONAL\""
                    + ",\"environmentId\":null"
                    + ",\"environmentName\":\"dev\"}"));

    // environmentName 为空白字符串被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definition
                    + ",\"contributor\":"
                    + emptyContributorJson
                    + ",\"environmentSupport\":\"REQUIRED\""
                    + ",\"environmentId\":"
                    + environmentJson(ENVIRONMENT_ID)
                    + ",\"environmentName\":\"   \"}"));
  }

  private static String environmentJson(EnvironmentId environmentId) {
    return "\"" + environmentId + "\"";
  }
}
