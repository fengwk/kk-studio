package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.ENVIRONMENT_ID;
import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.binding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.codec.AgentToolDefinitionJsonCodec;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;

/** 覆盖 HOST、PLUGIN 与 ENVIRONMENT_CAPABILITY backend 的严格 ToolBinding wire。 */
class ToolBindingJsonCodecTest {

  private final ToolBindingJsonCodec codec = new ToolBindingJsonCodec();
  private final AgentToolDefinitionJsonCodec definitionCodec = new AgentToolDefinitionJsonCodec();
  private final ToolDescriptorJsonCodec descriptorCodec = new ToolDescriptorJsonCodec();

  /** 精确 JSON 既证明字段顺序 deterministic，又在 round-trip 时重新执行 backend 不变式。 */
  @Test
  void roundTripsEveryBackendWithCanonicalJson() {
    ToolBinding environment = binding(AgentToolBackend.ENVIRONMENT_CAPABILITY);
    String environmentJson =
        "{\"definition\":"
            + definitionCodec.encode(environment.definition())
            + ",\"environment\":"
            + environmentJson(ENVIRONMENT_ID)
            + ",\"plugin\":null}";
    assertEquals(environmentJson, codec.encode(environment));
    assertEquals(environment, codec.decode(environmentJson));
    assertEquals(environment, codec.decodeNode(codec.encodeNode(environment)));

    ToolBinding host = binding(AgentToolBackend.HOST);
    String hostJson =
        "{\"definition\":"
            + definitionCodec.encode(host.definition())
            + ",\"environment\":null,\"plugin\":null}";
    assertEquals(hostJson, codec.encode(host));
    assertEquals(host, codec.decode(hostJson));
    assertNull(codec.decode(hostJson).environment());

    ToolBinding plugin = binding(AgentToolBackend.PLUGIN);
    String pluginJson =
        "{\"definition\":"
            + definitionCodec.encode(plugin.definition())
            + ",\"environment\":null,\"plugin\":"
            + "{\"pluginId\":\"goal\",\"contributionLocalName\":\"create\","
            + "\"stateAccesses\":[{\"customType\":\"state\",\"mode\":\"WRITE\"}]}}";
    assertEquals(pluginJson, codec.encode(plugin));
    assertEquals(plugin, codec.decode(pluginJson));
  }

  /** 字符串边界在任何领域值构造之前拒绝畸形文档。 */
  @Test
  void rejectsNullDuplicateTrailingAndNonObjectDocuments() {
    String json = codec.encode(binding(AgentToolBackend.HOST));
    String duplicate =
        json.replace(
            "\"environment\":null",
            "\"environment\":null,\"environment\":{\"name\":\"env-1\",\"workspacePath\":\".\"}");

    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(NullPointerException.class, () -> codec.decodeNode(null));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("null"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(json + " {}"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicate));
  }

  /** 顶层字段必须恰好是 definition/environment/plugin，旧 descriptor/type 形状必须拒绝。 */
  @Test
  void rejectsUnknownMissingWrongTypeAndLegacyShape() {
    ToolBinding environment = binding(AgentToolBackend.ENVIRONMENT_CAPABILITY);
    String definition = definitionCodec.encode(environment.definition());
    String descriptor = descriptorCodec.encode(environment.definition().descriptor());
    String valid =
        "{\"definition\":"
            + definition
            + ",\"environment\":"
            + environmentJson(ENVIRONMENT_ID)
            + ",\"plugin\":null}";

    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.substring(0, valid.length() - 1) + ",\"extra\":true}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"definition\":" + definition + ",", "")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(valid.replace(",\"environment\":" + environmentJson(ENVIRONMENT_ID), "")));
    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(valid.replace(",\"plugin\":null", "")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"definition\":" + definition, "\"definition\":1")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace(environmentJson(ENVIRONMENT_ID), "1")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace(",\"plugin\":null", ",\"plugin\":1")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace("\"definition\":" + definition, "\"definition\":null")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                valid.replace(
                    "\"definition\":" + definition,
                    "\"definition\":"
                        + definition.substring(0, definition.length() - 1)
                        + ",\"x\":1}")));

    // 旧 durable binding 的 descriptor/type 顶层形状不是新协议的合法输入，不能兼容恢复。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"descriptor\":"
                    + descriptor
                    + ",\"type\":\"ENVIRONMENT\",\"environment\":null,\"plugin\":null}"));
  }

  /** 构造器不变式必须在 codec 解码边界生效，而不是把非法 provenance 带入执行器。 */
  @Test
  void rejectsInvalidBackendPayloadCombinations() {
    ToolBinding host = binding(AgentToolBackend.HOST);
    ToolBinding plugin = binding(AgentToolBackend.PLUGIN);
    String pluginJson =
        "{\"pluginId\":\"goal\",\"contributionLocalName\":\"create\","
            + "\"stateAccesses\":[{\"customType\":\"state\",\"mode\":\"WRITE\"}]}";

    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definitionCodec.encode(host.definition())
                    + ",\"environment\":"
                    + environmentJson(ENVIRONMENT_ID)
                    + ",\"plugin\":null}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definitionCodec.encode(plugin.definition())
                    + ",\"environment\":"
                    + environmentJson(ENVIRONMENT_ID)
                    + ",\"plugin\":"
                    + pluginJson
                    + "}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definitionCodec.encode(plugin.definition())
                    + ",\"environment\":null,\"plugin\":null}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"definition\":"
                    + definitionCodec.encode(host.definition())
                    + ",\"environment\":null,\"plugin\":"
                    + pluginJson
                    + "}"));
  }

  private static String environmentJson(EnvironmentBinding binding) {
    return "{\"name\":\""
        + binding.environmentName().value()
        + "\",\"workspacePath\":\""
        + binding.workspacePath()
        + "\"}";
  }
}
