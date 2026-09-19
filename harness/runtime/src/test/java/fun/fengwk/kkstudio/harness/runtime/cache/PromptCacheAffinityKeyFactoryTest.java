package fun.fengwk.kkstudio.harness.runtime.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResourceBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 关注：固定 pc2- 前缀、43 字符 Base64URL 无 padding 输出；动态 USER/ASSISTANT/TOOL history 不影响 key；资源标识、 provider
 * 连接代际或 stable prefix 一旦变化即切换 key；systemInstruction 与 tools 字段独立成帧且边界不碰撞。
 */
class PromptCacheAffinityKeyFactoryTest {

  private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-00000000002a");
  private static final UUID PROVIDER_CONNECTION_GENERATION_ID =
      UUID.fromString("00000000-0000-0000-0000-00000000012a");

  private final TestKeyFactory factory = new TestKeyFactory();

  @Test
  void rejectsInvalidArguments() {
    ProviderRequest request = baseRequest();
    assertThrows(NullPointerException.class, () -> factory.create(null, request));
    assertThrows(NullPointerException.class, () -> factory.create(SESSION_ID, null));
    assertThrows(
        NullPointerException.class, () -> factory.delegate.create(SESSION_ID, null, request));
  }

  @Test
  void keyFormatIsFixedPrefixPlusBase64UrlNoPadding() {
    String key = factory.create(SESSION_ID, baseRequest());
    assertTrue(key.startsWith("pc2-"), key);
    String encoded = key.substring("pc2-".length());
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

  /** systemInstruction 内容改变必须派生不同的 affinity key。 */
  @Test
  void systemInstructionChangeChangesKey() {
    ProviderRequest a = baseRequestWithSystemInstruction("You are a helpful assistant.");
    ProviderRequest b = baseRequestWithSystemInstruction("You are a precise assistant.");
    assertNotEquals(factory.create(SESSION_ID, a), factory.create(SESSION_ID, b));
  }

  /** 任意对话历史消息内容变化（角色/文本/多轮）均不进入 digest，不影响 affinity key。 */
  @Test
  void arbitraryDynamicHistoryChangesDoNotAffectKey() {
    ProviderRequest template = baseRequest();
    String baseKey = factory.create(SESSION_ID, template);

    ProviderRequest msgA = withMessages(template, List.of(userText("hello")));
    ProviderRequest msgB = withMessages(template, List.of(userText("world")));
    ProviderRequest multiMsg =
        withMessages(
            template, List.of(userText("hello"), assistantThinking("hmm"), userText("bye")));

    assertEquals(baseKey, factory.create(SESSION_ID, msgA));
    assertEquals(baseKey, factory.create(SESSION_ID, msgB));
    assertEquals(baseKey, factory.create(SESSION_ID, multiMsg));
    assertEquals(factory.create(SESSION_ID, msgA), factory.create(SESSION_ID, msgB));
  }

  /** 动态历史中包含非文本块（thinking、json、resource 等）均不进入 digest，不影响 key。 */
  @Test
  void dynamicHistoryNonTextBlocksDoNotAffectKey() {
    ProviderRequest template = baseRequest();
    String baseKey = factory.create(SESSION_ID, template);

    ProviderMessage thinkingMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT, List.of(new ProviderThinkingBlock("internal thinking")));
    ProviderMessage jsonMsg =
        new ProviderMessage(
            ProviderMessageRole.USER, List.of(new ProviderJsonBlock("{\"key\":\"value\"}")));
    ProviderMessage resourceMsg =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(new ProviderResourceBlock(UUID.randomUUID(), "res.txt", 100L, 10L, "preview")));

    assertEquals(baseKey, factory.create(SESSION_ID, withMessages(template, List.of(thinkingMsg))));
    assertEquals(baseKey, factory.create(SESSION_ID, withMessages(template, List.of(jsonMsg))));
    assertEquals(baseKey, factory.create(SESSION_ID, withMessages(template, List.of(resourceMsg))));
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
    assertNotEquals(
        factory.create(UUID.fromString("00000000-0000-0000-0000-000000000001"), baseRequest()),
        factory.create(UUID.fromString("00000000-0000-0000-0000-000000000002"), baseRequest()));
  }

  @Test
  void providerConnectionGenerationIdChangeChangesKey() {
    ProviderRequest request = baseRequest();
    assertNotEquals(
        factory.createWithGeneration(
            SESSION_ID, UUID.fromString("00000000-0000-0000-0000-000000000001"), request),
        factory.createWithGeneration(
            SESSION_ID, UUID.fromString("00000000-0000-0000-0000-000000000002"), request));
  }

  @Test
  void providerNameChangeChangesKey() {
    assertNotEquals(
        factory.create(SESSION_ID, baseRequestWithModel(model("provider-a", "m1"))),
        factory.create(SESSION_ID, baseRequestWithModel(model("provider-b", "m1"))));
  }

  @Test
  void modelIdChangeChangesKey() {
    assertNotEquals(
        factory.create(SESSION_ID, baseRequestWithModel(model("provider", "m1"))),
        factory.create(SESSION_ID, baseRequestWithModel(model("provider", "m2"))));
  }

  /** 逻辑模型名及能力、价格不进入 digest；wire modelId 才是模型侧缓存身份。 */
  @Test
  void logicalModelNameAndDescriptorMetadataDoNotAffectKey() {
    ModelDescriptor identity = model("provider", "m1");
    ModelDescriptor sameIdentityDifferentFlags =
        new ModelDescriptor(
            "provider",
            "different-logical-name",
            "m1",
            Set.of(ModelInputModality.IMAGE),
            !identity.tools(),
            !identity.reasoning(),
            new ModelPricing(
                "CNY",
                "other-tier",
                "other",
                BigDecimal.TEN,
                "v2",
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE));
    assertEquals(
        factory.create(SESSION_ID, baseRequestWithModel(identity)),
        factory.create(SESSION_ID, baseRequestWithModel(sameIdentityDifferentFlags)));
  }

  @Test
  void modelIdChangeAlsoChangesKeyWhenProviderStaysTheSame() {
    assertNotEquals(
        factory.create(SESSION_ID, baseRequestWithModel(model("provider", "model-a"))),
        factory.create(SESSION_ID, baseRequestWithModel(model("provider", "model-b"))));
  }

  @Test
  void fieldBoundariesResistConcatenationCollision() {
    // 验证长度前缀编码能区分字符串拼接碰撞：name 和 value 字段使用长度前缀，
    // 不可能用字符串拼接伪造同 digest。
    ProviderRequest base = baseRequest();
    String baseKey = factory.create(SESSION_ID, base);
    // 修改 sessionId 让该差异出现在 digest 而不是 cacheControl。
    assertNotEquals(
        baseKey, factory.create(UUID.fromString("00000000-0000-0000-0000-00000000002b"), base));
    // 修改 modelId 后缀（"m1" -> "m1x"）必须切 key。
    assertNotEquals(
        baseKey, factory.create(SESSION_ID, baseRequestWithModel(model("provider", "m1x"))));
    // providerName 跨长度边界也必须切 key。
    assertNotEquals(
        factory.create(SESSION_ID, baseRequestWithModel(model("provider", "m1"))),
        factory.create(SESSION_ID, baseRequestWithModel(model("provider-long", "m1"))));
  }

  /** NUL 字段边界回归：systemInstruction 与 tool 各字段采用独立 length-prefixed frame 写入， 跨字段 NUL 伪造或重排不能产生碰撞。 */
  @Test
  void nulBoundaryCollisionSystemInstructionAndTool() {
    ProviderRequest a =
        baseRequestWithSystemInstructionAndTools("a\0b", List.of(tool("c", "desc", "{}")));
    ProviderRequest b =
        baseRequestWithSystemInstructionAndTools("a", List.of(tool("b\0c", "desc", "{}")));
    assertNotEquals(factory.create(SESSION_ID, a), factory.create(SESSION_ID, b));

    // systemInstruction 尾部追加 NUL 仍必须切 key。
    ProviderRequest c = baseRequestWithSystemInstruction("sys\0");
    ProviderRequest d = baseRequestWithSystemInstruction("sys");
    assertNotEquals(factory.create(SESSION_ID, c), factory.create(SESSION_ID, d));
  }

  /** Tool description 与 inputSchemaJson 跨字段 NUL 重排及尾部追加 NUL 仍必须切 key。 */
  @Test
  void nulBoundaryCollisionToolDescriptionAndSchema() {
    ProviderRequest a = baseRequest(tool("t1", "a\0b", "c"));
    ProviderRequest b = baseRequest(tool("t1", "a", "b\0c"));
    assertNotEquals(factory.create(SESSION_ID, a), factory.create(SESSION_ID, b));

    ProviderRequest c = baseRequest(tool("t1", "desc", "schema\0"));
    ProviderRequest d = baseRequest(tool("t1", "desc", "schema"));
    assertNotEquals(factory.create(SESSION_ID, c), factory.create(SESSION_ID, d));
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

  private static ProviderRequest baseRequest() {
    return baseRequestWithModel(model("provider", "m1"));
  }

  private static ProviderRequest baseRequest(ProviderToolDefinition... tools) {
    ModelDescriptor m = model("provider", "m1");
    ModelVariant variant = new ModelVariant("default");
    return new ProviderRequest(
        m,
        variant,
        1024,
        "Test system instruction.",
        List.of(),
        List.of(tools),
        ProviderCacheControl.none());
  }

  private static ProviderRequest baseRequestWithModel(ModelDescriptor descriptor) {
    ModelVariant variant = new ModelVariant("default");
    return new ProviderRequest(
        descriptor,
        variant,
        1024,
        "Test system instruction.",
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  private static ProviderRequest baseRequestWithSystemInstruction(String systemInstruction) {
    ModelDescriptor m = model("provider", "m1");
    ModelVariant variant = new ModelVariant("default");
    return new ProviderRequest(
        m, variant, 1024, systemInstruction, List.of(), List.of(), ProviderCacheControl.none());
  }

  private static ProviderRequest baseRequestWithSystemInstructionAndTools(
      String systemInstruction, List<ProviderToolDefinition> tools) {
    ModelDescriptor m = model("provider", "m1");
    ModelVariant variant = new ModelVariant("default");
    return new ProviderRequest(
        m, variant, 1024, systemInstruction, List.of(), tools, ProviderCacheControl.none());
  }

  private static ProviderRequest withMessages(
      ProviderRequest template, List<ProviderMessage> messages) {
    return new ProviderRequest(
        template.model(),
        template.variant(),
        1024,
        template.systemInstruction(),
        messages,
        template.tools(),
        template.cacheControl());
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

  private static List<ProviderMessage> appendDynamic(
      List<ProviderMessage> messages, ProviderMessage extra) {
    List<ProviderMessage> out = new ArrayList<>(messages);
    out.add(extra);
    return out;
  }

  private static ModelDescriptor model(String provider, String model) {
    return new ModelDescriptor(
        provider,
        model,
        model,
        Set.of(ModelInputModality.TEXT),
        true,
        false,
        new ModelPricing(
            "CNY",
            "tier-1",
            "default",
            BigDecimal.ONE,
            "v1",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO));
  }

  private static final class TestKeyFactory {

    private final PromptCacheAffinityKeyFactory delegate = new PromptCacheAffinityKeyFactory();

    private String create(UUID sessionId, ProviderRequest request) {
      return delegate.create(sessionId, PROVIDER_CONNECTION_GENERATION_ID, request);
    }

    private String createWithGeneration(
        UUID sessionId, UUID providerConnectionGenerationId, ProviderRequest request) {
      return delegate.create(sessionId, providerConnectionGenerationId, request);
    }
  }
}
