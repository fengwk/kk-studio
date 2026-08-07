package fun.fengwk.kkstudio.harness.runtime.invocation.codec;

import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.ENVIRONMENT_ID;
import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.binding;
import static fun.fengwk.kkstudio.harness.runtime.invocation.codec.InvocationCodecTestFixtures.descriptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;

/** 同时覆盖 platform 与 Environment route 的严格 ToolBinding wire。 */
class ToolBindingJsonCodecTest {

  private final ToolBindingJsonCodec codec = new ToolBindingJsonCodec();
  private final ToolDescriptorJsonCodec descriptorCodec = new ToolDescriptorJsonCodec();

  /** 精确 JSON 既证明字段顺序 deterministic，又在 round-trip 时重新执行领域 route 校验。 */
  @Test
  void roundTripsBothBindingKindsWithCanonicalJson() {
    ToolBinding environment = binding(ToolType.ENVIRONMENT);
    String environmentJson =
        "{\"descriptor\":"
            + descriptorCodec.encode(environment.descriptor())
            + ",\"type\":\"ENVIRONMENT\",\"environmentId\":\""
            + ENVIRONMENT_ID
            + "\"}";
    assertEquals(environmentJson, codec.encode(environment));
    assertEquals(environment, codec.decode(environmentJson));
    assertEquals(environment, codec.decodeNode(codec.encodeNode(environment)));

    ToolBinding platform = binding(ToolType.PLATFORM);
    String platformJson =
        "{\"descriptor\":"
            + descriptorCodec.encode(platform.descriptor())
            + ",\"type\":\"PLATFORM\",\"environmentId\":null}";
    assertEquals(platformJson, codec.encode(platform));
    assertEquals(platform, codec.decode(platformJson));
    assertNull(codec.decode(platformJson).environmentId());
  }

  /** 字符串边界在任何领域值构造之前拒绝畸形文档。 */
  @Test
  void rejectsNullDuplicateTrailingAndNonObjectDocuments() {
    String json = codec.encode(binding(ToolType.PLATFORM));
    String duplicate =
        json.replace("\"type\":\"PLATFORM\"", "\"type\":\"PLATFORM\",\"type\":\"ENVIRONMENT\"");

    assertThrows(NullPointerException.class, () -> codec.encode(null));
    assertThrows(NullPointerException.class, () -> codec.decode(null));
    assertThrows(NullPointerException.class, () -> codec.decodeNode(null));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("{"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(json + " {}"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicate));
  }

  /** 精确字段/类型检查与 ToolBinding 构造函数共同拒绝被破坏的持久化行。 */
  @Test
  void rejectsUnknownMissingWrongTypeAndInconsistentBindingFacts() {
    String descriptor = descriptorCodec.encode(descriptor(ToolType.ENVIRONMENT));
    String valid =
        "{\"descriptor\":"
            + descriptor
            + ",\"type\":\"ENVIRONMENT\",\"environmentId\":\""
            + ENVIRONMENT_ID
            + "\"}";

    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.substring(0, valid.length() - 1) + ",\"extra\":true}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(valid.replace(",\"environmentId\":\"" + ENVIRONMENT_ID + "\"", "")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                valid.replace(
                    ",\"type\":\"ENVIRONMENT\",\"environmentId\":",
                    ",\"type\":1,\"environmentId\":")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                valid.replace(
                    ",\"type\":\"ENVIRONMENT\",\"environmentId\":",
                    ",\"type\":\"OTHER\",\"environmentId\":")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                valid.replace(
                    "\"environmentId\":\"" + ENVIRONMENT_ID + "\"", "\"environmentId\":1")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                valid.replace(ENVIRONMENT_ID.value(), ENVIRONMENT_ID.value().toUpperCase())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"descriptor\":"
                    + descriptorCodec.encode(descriptor(ToolType.PLATFORM))
                    + ",\"type\":\"ENVIRONMENT\",\"environmentId\":\""
                    + ENVIRONMENT_ID
                    + "\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                "{\"descriptor\":"
                    + descriptor
                    + ",\"type\":\"ENVIRONMENT\",\"environmentId\":null}"));
  }
}
