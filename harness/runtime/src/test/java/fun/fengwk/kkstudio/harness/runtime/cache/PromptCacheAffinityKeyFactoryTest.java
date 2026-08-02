package fun.fengwk.kkstudio.harness.runtime.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderVideoBlock;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 关注：固定 pc1- 前缀、43 字符 Base64URL 无 padding 输出；动态 USER/ASSISTANT/TOOL history 不影响 key；资源标识或 stable
 * prefix 一旦变化即切换 key；任意 typed content 块都能进入 digest 且字段边界不碰撞。
 */
class PromptCacheAffinityKeyFactoryTest {

  private static final long SESSION_ID = 42L;

  private final PromptCacheAffinityKeyFactory factory = new PromptCacheAffinityKeyFactory();

  @Test
  void rejectsInvalidArguments() {
    ProviderRequest request = baseRequest();
    assertThrows(IllegalArgumentException.class, () -> factory.create(0L, request));
    assertThrows(IllegalArgumentException.class, () -> factory.create(-1L, request));
    assertThrows(NullPointerException.class, () -> factory.create(SESSION_ID, null));
  }

  @Test
  void keyFormatIsFixedPrefixPlusBase64UrlNoPadding() {
    String key = factory.create(SESSION_ID, baseRequest());
    assertTrue(key.startsWith("pc1-"), key);
    String encoded = key.substring("pc1-".length());
    assertEquals(43, encoded.length(), encoded);
    assertTrue(encoded.matches("^[A-Za-z0-9_-]+$"), "Base64URL chars only: " + encoded);
    assertTrue(encoded.chars().noneMatch(ch -> ch == '='), "no padding allowed");
  }

  @Test
  void sameInputsProduceIdenticalKey() {
    ProviderRequest a = baseRequest();
    ProviderRequest b = baseRequest();
    assertEquals(factory.create(SESSION_ID, a), factory.create(SESSION_ID, b));
  }

  @Test
  void dynamicUserAssistantToolHistoryDoesNotAffectKey() {
    ProviderRequest template = baseRequest();
    String baseKey = factory.create(SESSION_ID, template);

    ProviderRequest dynamicText =
        withMessages(template, appendDynamic(template.messages(), userText("q1")));
    ProviderRequest dynamicTool =
        withMessages(template, appendDynamic(template.messages(), assistantToolCall()));
    ProviderRequest dynamicToolResult =
        withMessages(template, appendDynamic(template.messages(), toolResultMessage()));
    ProviderRequest dynamicThinking =
        withMessages(template, appendDynamic(template.messages(), assistantThinking("thinking")));

    assertEquals(baseKey, factory.create(SESSION_ID, dynamicText));
    assertEquals(baseKey, factory.create(SESSION_ID, dynamicTool));
    assertEquals(baseKey, factory.create(SESSION_ID, dynamicToolResult));
    assertEquals(baseKey, factory.create(SESSION_ID, dynamicThinking));
  }

  /**
   * 即便让 image block 出现在 leading SYSTEM 也必须有 coverage；对应 sealed 分支。 这里覆盖 sealed ProviderContentBlock
   * 全部分支，让 key 工厂对各类 leading SYSTEM 内容都能稳定派生。
   */
  @Test
  void leadingSystemSealedBlocksAllBranchesRender() {
    ProviderRequest template = baseRequest();
    String base = factory.create(SESSION_ID, template);

    ProviderRequest withText =
        withMessages(
            template,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM, List.of(new ProviderTextBlock("S1")))));
    ProviderRequest withThinking =
        withMessages(
            template,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM,
                    List.of(new ProviderThinkingBlock("S1-thinking")))));
    ProviderRequest withJson =
        withMessages(
            template,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM, List.of(new ProviderJsonBlock("{\"s\":1}")))));
    ProviderRequest withMixed =
        withMessages(
            template,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM,
                    List.of(
                        new ProviderTextBlock("S1"), new ProviderImageBlock("image/png", "u:a")))));
    String baseText = factory.create(SESSION_ID, withText);
    String baseThinking = factory.create(SESSION_ID, withThinking);
    String baseJson = factory.create(SESSION_ID, withJson);
    String baseMixed = factory.create(SESSION_ID, withMixed);

    assertTrue(baseText.startsWith("pc1-"));
    assertTrue(baseThinking.startsWith("pc1-"));
    assertTrue(baseJson.startsWith("pc1-"));
    assertTrue(baseMixed.startsWith("pc1-"));
    // 任何分支之间必须互不相同。
    assertNotEquals(baseText, baseThinking);
    assertNotEquals(baseText, baseJson);
    assertNotEquals(baseText, baseMixed);
    assertNotEquals(baseThinking, baseJson);

    // 文本以外其它分支必须改变 key。
    assertNotEquals(base, baseText);
    assertNotEquals(base, baseThinking);
    assertNotEquals(base, baseJson);
    assertNotEquals(base, baseMixed);
  }

  @Test
  void addingAnotherLeadingSystemMessageChangesKey() {
    ProviderRequest original = withMessages(baseRequest(), List.of(systemText("S1")));
    String originalKey = factory.create(SESSION_ID, original);

    ProviderRequest extended =
        withMessages(baseRequest(), List.of(systemText("S1"), systemText("S2")));
    assertNotEquals(originalKey, factory.create(SESSION_ID, extended));
  }

  @Test
  void systemTextChangeChangesKey() {
    ProviderRequest a = withMessages(baseRequest(), List.of(systemText("S1")));
    ProviderRequest b = withMessages(baseRequest(), List.of(systemText("S2")));
    assertNotEquals(factory.create(SESSION_ID, a), factory.create(SESSION_ID, b));
  }

  @Test
  void systemImageBlockChangesKey() {
    ProviderRequest a =
        withMessages(
            baseRequest(),
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM,
                    List.of(new ProviderImageBlock("image/png", "data:image/png;base64,AAA")))));
    ProviderRequest b =
        withMessages(
            baseRequest(),
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM,
                    List.of(new ProviderImageBlock("image/png", "data:image/png;base64,BBB")))));
    assertNotEquals(factory.create(SESSION_ID, a), factory.create(SESSION_ID, b));
  }

  @Test
  void systemAudioBlockChangesKey() {
    ProviderRequest a =
        withMessages(
            baseRequest(),
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM,
                    List.of(new ProviderAudioBlock("audio/mpeg", "uri:a")))));
    ProviderRequest b =
        withMessages(
            baseRequest(),
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM,
                    List.of(new ProviderAudioBlock("audio/mpeg", "uri:b")))));
    assertNotEquals(factory.create(SESSION_ID, a), factory.create(SESSION_ID, b));
  }

  @Test
  void systemVideoBlockChangesKey() {
    ProviderRequest a =
        withMessages(
            baseRequest(),
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM,
                    List.of(new ProviderVideoBlock("video/mp4", "uri:a")))));
    ProviderRequest b =
        withMessages(
            baseRequest(),
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM,
                    List.of(new ProviderVideoBlock("video/mp4", "uri:b")))));
    assertNotEquals(factory.create(SESSION_ID, a), factory.create(SESSION_ID, b));
  }

  @Test
  void systemJsonBlockChangesKey() {
    ProviderRequest a =
        withMessages(
            baseRequest(),
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM, List.of(new ProviderJsonBlock("{\"a\":1}")))));
    ProviderRequest b =
        withMessages(
            baseRequest(),
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.SYSTEM, List.of(new ProviderJsonBlock("{\"a\":2}")))));
    assertNotEquals(factory.create(SESSION_ID, a), factory.create(SESSION_ID, b));
  }

  @Test
  void toolNameChangeChangesKey() {
    ProviderRequest a = baseRequest(tool("alpha", "a", "{}"));
    ProviderRequest b = baseRequest(tool("beta", "a", "{}"));
    assertNotEquals(factory.create(SESSION_ID, a), factory.create(SESSION_ID, b));
  }

  @Test
  void toolDescriptionChangeChangesKey() {
    ProviderRequest a = baseRequest(tool("alpha", "desc-a", "{}"));
    ProviderRequest b = baseRequest(tool("alpha", "desc-b", "{}"));
    assertNotEquals(factory.create(SESSION_ID, a), factory.create(SESSION_ID, b));
  }

  @Test
  void toolSchemaChangeChangesKey() {
    ProviderRequest a =
        baseRequest(
            tool(
                "alpha",
                "desc",
                "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}"));
    ProviderRequest b =
        baseRequest(
            tool(
                "alpha",
                "desc",
                "{\"type\":\"object\",\"properties\":{\"y\":{\"type\":\"string\"}}}"));
    assertNotEquals(factory.create(SESSION_ID, a), factory.create(SESSION_ID, b));
  }

  @Test
  void toolOrderChangeChangesKey() {
    ProviderRequest a = baseRequest(tool("alpha", "alpha", "{}"), tool("beta", "beta", "{}"));
    ProviderRequest b = baseRequest(tool("beta", "beta", "{}"), tool("alpha", "alpha", "{}"));
    assertNotEquals(factory.create(SESSION_ID, a), factory.create(SESSION_ID, b));
  }

  @Test
  void sessionIdChangeChangesKey() {
    assertNotEquals(factory.create(1L, baseRequest()), factory.create(2L, baseRequest()));
  }

  @Test
  void providerNameChangeChangesKey() {
    assertNotEquals(
        factory.create(
            SESSION_ID, baseRequestWithModel(model("provider-a", "m1", ProviderType.OPENAI))),
        factory.create(
            SESSION_ID, baseRequestWithModel(model("provider-b", "m1", ProviderType.OPENAI))));
  }

  @Test
  void modelNameChangeChangesKey() {
    assertNotEquals(
        factory.create(
            SESSION_ID, baseRequestWithModel(model("provider", "m1", ProviderType.OPENAI))),
        factory.create(
            SESSION_ID, baseRequestWithModel(model("provider", "m2", ProviderType.OPENAI))));
  }

  @Test
  void providerTypeChangeChangesKey() {
    assertNotEquals(
        factory.create(
            SESSION_ID, baseRequestWithModel(model("provider", "m1", ProviderType.OPENAI))),
        factory.create(
            SESSION_ID, baseRequestWithModel(model("provider", "m1", ProviderType.ANTHROPIC))));
  }

  @Test
  void modelNameChangeAlsoChangesKeyWhenProviderStaysTheSame() {
    assertNotEquals(
        factory.create(
            SESSION_ID, baseRequestWithModel(model("provider", "model-a", ProviderType.OPENAI))),
        factory.create(
            SESSION_ID, baseRequestWithModel(model("provider", "model-b", ProviderType.OPENAI))));
  }

  @Test
  void fieldBoundariesResistConcatenationCollision() {
    // 验证长度前缀编码能区分字符串拼接碰撞：name 和 value 字段使用长度前缀，
    // 不可能用字符串拼接伪造同 digest。
    ProviderRequest base = baseRequest();
    String baseKey = factory.create(SESSION_ID, base);
    // 修改 sessionId 让该差异出现在 digest 而不是 cacheControl。
    assertNotEquals(baseKey, factory.create(SESSION_ID + 1, base));
    // 修改 modelName 后缀（"m1" -> "m1x"）必须切 key。
    assertNotEquals(
        baseKey,
        factory.create(
            SESSION_ID, baseRequestWithModel(model("provider", "m1x", ProviderType.OPENAI))));
    // providerName 跨长度边界也必须切 key。
    assertNotEquals(
        factory.create(
            SESSION_ID, baseRequestWithModel(model("provider", "m1", ProviderType.OPENAI))),
        factory.create(
            SESSION_ID, baseRequestWithModel(model("provider-long", "m1", ProviderType.OPENAI))));
    // providerType 变化：m1 同名但 OPENAI vs ANTHROPIC 必须切 key。
    assertNotEquals(
        baseKey,
        factory.create(
            SESSION_ID, baseRequestWithModel(model("provider", "m1", ProviderType.ANTHROPIC))));
  }

  /**
   * NUL 字段边界回归：image 的 mediaType 与 source 必须以独立 length-prefixed frame 写入，不能用任何形式的 NUL
   * 拼接。两个对象的拼接后字节序列完全相同，但 key 不得相同。
   */
  @Test
  void nulBoundaryCollisionImageMediaTypeAndSource() {
    ProviderRequest a = requestWithLeadingImage("a\0b", "c");
    ProviderRequest b = requestWithLeadingImage("a", "b\0c");
    assertNotEquals(factory.create(SESSION_ID, a), factory.create(SESSION_ID, b));
  }

  /** 同等的 audio / video 复合块也必须避免 NUL 拼接碰撞。 */
  @Test
  void nulBoundaryCollisionAudioVideo() {
    ProviderRequest audioA = requestWithLeadingAudio("a\0b", "c");
    ProviderRequest audioB = requestWithLeadingAudio("a", "b\0c");
    assertNotEquals(factory.create(SESSION_ID, audioA), factory.create(SESSION_ID, audioB));

    ProviderRequest videoA = requestWithLeadingVideo("video/mp4", "a\0b");
    ProviderRequest videoB = requestWithLeadingVideo("video/mp4", "a\0b\0");
    // 仅在尾部追加 NUL 看似不影响媒体，但 length-prefixed 框架下仍必须切 key。
    assertNotEquals(factory.create(SESSION_ID, videoA), factory.create(SESSION_ID, videoB));
  }

  /** tool definition 各字段必须独立成帧：name / description / inputSchemaJson 的 NUL 重排或追加都必须切 key。 */
  @Test
  void toolDefinitionFieldCollision() {
    ProviderRequest a = baseRequest(tool("a\0b", "c", "{}"));
    ProviderRequest b = baseRequest(tool("a", "b\0c", "{}"));
    assertNotEquals(factory.create(SESSION_ID, a), factory.create(SESSION_ID, b));

    ProviderRequest c = baseRequest(tool("alpha", "desc\0{\"a\":1}", "{}"));
    ProviderRequest d = baseRequest(tool("alpha", "desc", "{\"a\":1}\0{}"));
    assertNotEquals(factory.create(SESSION_ID, c), factory.create(SESSION_ID, d));
  }

  /** 合法 SYSTEM nested 类型（Text/Image/Audio/Video/Thinking/Json）通过真实 {@code create} 路径覆盖。 */
  @Test
  void legalSystemNestedTypesAreAllCoveredViaCreate() {
    String textKey =
        factory.create(SESSION_ID, baseRequestWithSystemMessage(new ProviderTextBlock("S-text")));
    String imageKey =
        factory.create(
            SESSION_ID, baseRequestWithSystemMessage(new ProviderImageBlock("image/png", "u:img")));
    String audioKey =
        factory.create(
            SESSION_ID, baseRequestWithSystemMessage(new ProviderAudioBlock("audio/mp3", "u:aud")));
    String videoKey =
        factory.create(
            SESSION_ID, baseRequestWithSystemMessage(new ProviderVideoBlock("video/mp4", "u:vid")));
    String thinkingKey =
        factory.create(
            SESSION_ID, baseRequestWithSystemMessage(new ProviderThinkingBlock("think")));
    String jsonKey =
        factory.create(
            SESSION_ID, baseRequestWithSystemMessage(new ProviderJsonBlock("{\"k\":1}")));

    assertTrue(textKey.startsWith("pc1-"));
    assertTrue(imageKey.startsWith("pc1-"));
    assertTrue(audioKey.startsWith("pc1-"));
    assertTrue(videoKey.startsWith("pc1-"));
    assertTrue(thinkingKey.startsWith("pc1-"));
    assertTrue(jsonKey.startsWith("pc1-"));
    // 不同类型之间互不相同，证明每条分支都进入 digest。
    assertNotEquals(textKey, imageKey);
    assertNotEquals(textKey, audioKey);
    assertNotEquals(textKey, videoKey);
    assertNotEquals(textKey, thinkingKey);
    assertNotEquals(textKey, jsonKey);
    assertNotEquals(imageKey, audioKey);
    assertNotEquals(imageKey, videoKey);
    assertNotEquals(thinkingKey, jsonKey);
  }

  private static ProviderRequest baseRequest() {
    return baseRequestWithModel(model("provider", "m1", ProviderType.OPENAI));
  }

  private static ProviderRequest baseRequest(ProviderToolDefinition... tools) {
    ModelDescriptor m = model("provider", "m1", ProviderType.OPENAI);
    ModelVariant variant =
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null);
    return new ProviderRequest(m, variant, List.of(), List.of(tools), ProviderCacheControl.none());
  }

  private static ProviderRequest baseRequestWithModel(ModelDescriptor descriptor) {
    ModelVariant variant =
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null);
    return new ProviderRequest(
        descriptor, variant, List.of(), List.of(), ProviderCacheControl.none());
  }

  private static ProviderRequest withMessages(
      ProviderRequest template, List<ProviderMessage> messages) {
    return new ProviderRequest(
        template.model(), template.variant(), messages, template.tools(), template.cacheControl());
  }

  private static ProviderMessage systemText(String text) {
    return new ProviderMessage(ProviderMessageRole.SYSTEM, List.of(new ProviderTextBlock(text)));
  }

  private static ProviderMessage userText(String text) {
    return new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock(text)));
  }

  private static ProviderMessage assistantToolCall() {
    return new ProviderMessage(
        ProviderMessageRole.ASSISTANT,
        List.of(new ProviderToolCallBlock(new ProviderToolCall("call-1", "alpha", "{}"))));
  }

  private static ProviderMessage assistantThinking(String text) {
    return new ProviderMessage(
        ProviderMessageRole.ASSISTANT, List.of(new ProviderThinkingBlock(text)));
  }

  private static ProviderMessage toolResultMessage() {
    ProviderToolResultBlock block =
        new ProviderToolResultBlock(
            "call-1", "alpha", List.of(new ProviderTextBlock("ok")), false, "{}");
    return new ProviderMessage(ProviderMessageRole.TOOL, List.of(block));
  }

  private static ProviderToolDefinition tool(String name, String description, String schema) {
    return new ProviderToolDefinition(name, description, schema);
  }

  private static ProviderRequest baseRequestWithSystemMessage(ProviderContentBlock block) {
    return baseRequestWithSystemMessages(List.of(block));
  }

  private static ProviderRequest baseRequestWithSystemMessages(
      List<ProviderContentBlock> leadingSystemContents) {
    ProviderRequest template = baseRequest();
    return new ProviderRequest(
        template.model(),
        template.variant(),
        List.of(new ProviderMessage(ProviderMessageRole.SYSTEM, leadingSystemContents)),
        template.tools(),
        template.cacheControl());
  }

  private static ProviderRequest requestWithLeadingImage(String mediaType, String source) {
    return baseRequestWithSystemMessage(new ProviderImageBlock(mediaType, source));
  }

  private static ProviderRequest requestWithLeadingAudio(String mediaType, String source) {
    return baseRequestWithSystemMessage(new ProviderAudioBlock(mediaType, source));
  }

  private static ProviderRequest requestWithLeadingVideo(String mediaType, String source) {
    return baseRequestWithSystemMessage(new ProviderVideoBlock(mediaType, source));
  }

  private static List<ProviderMessage> appendDynamic(
      List<ProviderMessage> prefix, ProviderMessage dynamic) {
    List<ProviderMessage> messages = new ArrayList<>(prefix);
    messages.add(dynamic);
    return List.copyOf(messages);
  }

  private static ModelDescriptor model(String providerName, String modelName, ProviderType type) {
    return new ModelDescriptor(
        providerName,
        0L,
        modelName,
        type,
        true,
        false,
        new ModelPricing(
            "USD",
            "tier-1",
            "default",
            BigDecimal.ONE,
            "v1",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO),
        PromptCachePolicy.disabled());
  }
}
