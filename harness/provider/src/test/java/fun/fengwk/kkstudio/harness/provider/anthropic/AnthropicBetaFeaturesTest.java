package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 验证 {@code anthropic-beta} 能力配置与并集发送：配置列表严格解析（有序、唯一、安全头 token）， 运行时强制的 interleaved thinking 只在
 * BUDGET 思考实际启用时追加且不重复。
 */
class AnthropicBetaFeaturesTest {

  private static final String INTERLEAVED = "interleaved-thinking-2025-05-14";

  private final ProviderDescriptor descriptor =
      new ProviderDescriptor(
          "test-anthropic",
          ProviderType.ANTHROPIC,
          "https://api.anthropic.com/v1",
          new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)),
          new UUID(1L, 2L));

  /** 测试意图：缺省、null、空数组与空白 JSON 都不声明任何 beta 能力。 */
  @Test
  void treatsAbsentBetaFeaturesAsNone() {
    assertEquals(List.of(), AnthropicConfiguration.defaults().anthropicBetaFeatures());
    assertEquals(List.of(), AnthropicConfiguration.parse(null).anthropicBetaFeatures());
    assertEquals(List.of(), AnthropicConfiguration.parse("{}").anthropicBetaFeatures());
    assertEquals(
        List.of(),
        AnthropicConfiguration.parse("{\"anthropicBetaFeatures\":null}").anthropicBetaFeatures());
    assertEquals(
        List.of(),
        AnthropicConfiguration.parse("{\"anthropicBetaFeatures\":[]}").anthropicBetaFeatures());
  }

  /** 测试意图：配置的 beta 能力按声明顺序原样保留，未知字段与思考模式互不影响。 */
  @Test
  void parsesConfiguredBetaFeaturesInOrder() {
    AnthropicConfiguration config =
        AnthropicConfiguration.parse(
            """
            {
              "anthropicThinkingMode": "BUDGET",
              "anthropicBetaFeatures": [
                "prompt-caching-2024-07-31",
                "context-1m-2025-08-07",
                "token-efficient-tools-2025-02-19"
              ],
              "unknownField": "ignored"
            }
            """);

    assertEquals(AnthropicThinkingMode.BUDGET, config.anthropicThinkingMode());
    assertEquals(
        List.of(
            "prompt-caching-2024-07-31",
            "context-1m-2025-08-07",
            "token-efficient-tools-2025-02-19"),
        config.anthropicBetaFeatures());
  }

  /** 测试意图：beta 标识取值域是「非空安全头 token」。任何可注入头分隔符、越界长度、重复或超量的配置都必须确定性拒绝， 且错误消息绝不回显配置内容。 */
  @Test
  void rejectsUnsafeBetaFeatureConfigurationWithoutEchoingValues() {
    String sensitive = "sk-ant-TOP-SECRET-BETA";

    // 1. 非字符串元素
    assertRejected("{\"anthropicBetaFeatures\":[123]}", "entries must be strings");
    // 2. 非数组
    assertRejected("{\"anthropicBetaFeatures\":\"x\"}", "must be a JSON array");
    // 3. 空白标识
    assertRejected("{\"anthropicBetaFeatures\":[\"  \"]}", "non-blank safe header tokens");
    // 4. 逗号（可注入额外能力）、空格、冒号、控制字符
    assertRejected("{\"anthropicBetaFeatures\":[\"a,b\"]}", "non-blank safe header tokens");
    assertRejected("{\"anthropicBetaFeatures\":[\"a b\"]}", "non-blank safe header tokens");
    assertRejected("{\"anthropicBetaFeatures\":[\"a:b\"]}", "non-blank safe header tokens");
    assertRejected("{\"anthropicBetaFeatures\":[\"a\\nb\"]}", "non-blank safe header tokens");
    // 5. 重复标识
    assertRejected("{\"anthropicBetaFeatures\":[\"a-1\",\"a-1\"]}", "entries must be unique");
    // 6. 单个标识超长（65 个字符）
    assertRejected(
        "{\"anthropicBetaFeatures\":[\"" + "a".repeat(65) + "\"]}",
        "must not exceed 64 characters");
    // 7. 数量超限（9 个）
    assertRejected(
        "{\"anthropicBetaFeatures\":["
            + "\"a-1\",\"a-2\",\"a-3\",\"a-4\",\"a-5\",\"a-6\",\"a-7\",\"a-8\",\"a-9\"]}",
        "must contain at most 8 entries");
    // 8. 合并长度超限：8 个 64 字符标识 + 7 个逗号 = 519 > 512
    String longToken = "a".repeat(64);
    assertRejected(
        "{\"anthropicBetaFeatures\":[\""
            + longToken
            + "\",\""
            + longToken.replace('a', 'b')
            + "\",\""
            + longToken.replace('a', 'c')
            + "\",\""
            + longToken.replace('a', 'd')
            + "\",\""
            + longToken.replace('a', 'e')
            + "\",\""
            + longToken.replace('a', 'f')
            + "\",\""
            + longToken.replace('a', 'g')
            + "\",\""
            + longToken.replace('a', 'h')
            + "\"]}",
        "combined length must not exceed 512 characters");

    // 9. 错误消息绝不回显配置值（含安全前缀但混入非法分隔符）
    ProviderException leak =
        assertThrows(
            ProviderException.class,
            () ->
                AnthropicConfiguration.parse(
                    "{\"anthropicBetaFeatures\":[\"" + sensitive + ",extra\"]}"));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, leak.kind());
    assertFalse(leak.getMessage().contains(sensitive));
    assertNull(leak.getCause());

    // 10. 编程构造同样经过唯一校验入口
    ProviderException programmatic =
        assertThrows(
            ProviderException.class,
            () -> new AnthropicConfiguration(AnthropicThinkingMode.ADAPTIVE, List.of("a,b")));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, programmatic.kind());
  }

  /** 测试意图：未启用 BUDGET 思考（ADAPTIVE / 未启用推理）时，只发送配置能力，绝不附加 interleaved beta。 */
  @Test
  void sendsOnlyConfiguredBetaFeaturesWhenBudgetThinkingInactive() {
    AnthropicConfiguration config =
        new AnthropicConfiguration(
            AnthropicThinkingMode.ADAPTIVE, List.of("prompt-caching-2024-07-31"));
    AnthropicRequestEncoder encoder = new AnthropicRequestEncoder(config);

    // 1. 无推理能力：只发送配置能力
    AnthropicEncodedRequest plain = encoder.encode(request(reasoningModel(), null), descriptor);
    assertEquals(List.of("prompt-caching-2024-07-31"), plain.betaFeatures());

    // 2. ADAPTIVE 模式即使启用推理也不附加 interleaved beta
    AnthropicEncodedRequest adaptive =
        encoder.encode(request(reasoningModel(), new ModelVariant("v", "high")), descriptor);
    assertEquals(List.of("prompt-caching-2024-07-31"), adaptive.betaFeatures());

    // 3. BUDGET 模式但推理显式关闭（effort=off）同样不附加
    AnthropicRequestEncoder budgetEncoder =
        new AnthropicRequestEncoder(
            new AnthropicConfiguration(
                AnthropicThinkingMode.BUDGET, List.of("prompt-caching-2024-07-31")));
    AnthropicEncodedRequest off =
        budgetEncoder.encode(request(reasoningModel(), new ModelVariant("v", "off")), descriptor);
    assertEquals(List.of("prompt-caching-2024-07-31"), off.betaFeatures());

    // 4. 无配置能力且无强制能力：空并集（不发送 anthropic-beta 头）
    AnthropicEncodedRequest none =
        new AnthropicRequestEncoder(new AnthropicConfiguration(AnthropicThinkingMode.ADAPTIVE))
            .encode(request(model(), null), descriptor);
    assertEquals(List.of(), none.betaFeatures());
  }

  /** 测试意图：BUDGET 思考实际启用时把运行时强制能力追加在配置列表之后，且与已配置的同名能力去重。 */
  @Test
  void appendsImplicitInterleavedBetaOnceWhenBudgetThinkingEnabled() {
    AnthropicRequestEncoder encoder =
        new AnthropicRequestEncoder(
            new AnthropicConfiguration(
                AnthropicThinkingMode.BUDGET,
                List.of("prompt-caching-2024-07-31", "context-1m-2025-08-07")));

    AnthropicEncodedRequest encoded =
        encoder.encode(request(reasoningModel(), new ModelVariant("v", "low")), descriptor);
    assertEquals(
        List.of("prompt-caching-2024-07-31", "context-1m-2025-08-07", INTERLEAVED),
        encoded.betaFeatures());

    // 已配置相同能力时不重复：顺序与配置完全一致
    AnthropicRequestEncoder dedupEncoder =
        new AnthropicRequestEncoder(
            new AnthropicConfiguration(
                AnthropicThinkingMode.BUDGET, List.of(INTERLEAVED, "prompt-caching-2024-07-31")));
    AnthropicEncodedRequest deduped =
        dedupEncoder.encode(request(reasoningModel(), new ModelVariant("v", "low")), descriptor);
    assertEquals(List.of(INTERLEAVED, "prompt-caching-2024-07-31"), deduped.betaFeatures());

    // 未配置任何能力时，并集只含运行时强制能力
    AnthropicRequestEncoder implicitOnlyEncoder =
        new AnthropicRequestEncoder(new AnthropicConfiguration(AnthropicThinkingMode.BUDGET));
    AnthropicEncodedRequest implicitOnly =
        implicitOnlyEncoder.encode(
            request(reasoningModel(), new ModelVariant("v", "low")), descriptor);
    assertEquals(List.of(INTERLEAVED), implicitOnly.betaFeatures());
  }

  /** 测试意图：beta 能力并集不影响请求体内容与冻结前缀哈希语义。 */
  @Test
  void betaUnionDoesNotAlterRequestBodyOrPrefixHash() {
    byte[] withoutBeta =
        new AnthropicRequestEncoder(new AnthropicConfiguration(AnthropicThinkingMode.BUDGET))
            .encode(request(reasoningModel(), new ModelVariant("v", "low")), descriptor)
            .bodyUtf8Bytes();
    AnthropicEncodedRequest withBeta =
        new AnthropicRequestEncoder(
                new AnthropicConfiguration(
                    AnthropicThinkingMode.BUDGET, List.of("prompt-caching-2024-07-31")))
            .encode(request(reasoningModel(), new ModelVariant("v", "low")), descriptor);

    assertEquals(
        new String(withoutBeta, StandardCharsets.UTF_8),
        new String(withBeta.bodyUtf8Bytes(), StandardCharsets.UTF_8));
    assertTrue(withBeta.betaFeatures().contains("prompt-caching-2024-07-31"));
  }

  private static void assertRejected(String configJson, String expectedMessageFragment) {
    ProviderException error =
        assertThrows(ProviderException.class, () -> AnthropicConfiguration.parse(configJson));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
    assertTrue(
        error.getMessage().contains(expectedMessageFragment),
        "unexpected message: " + error.getMessage());
  }

  private ProviderRequest request(ModelDescriptor model, ModelVariant variant) {
    return new ProviderRequest(
        model,
        variant != null ? variant : new ModelVariant("default"),
        4096,
        "Test system instruction.",
        List.of(
            new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("hi")))),
        List.of(),
        ProviderCacheControl.none());
  }

  private static ModelDescriptor model() {
    return modelDescriptor(false);
  }

  private static ModelDescriptor reasoningModel() {
    return modelDescriptor(true);
  }

  private static ModelDescriptor modelDescriptor(boolean reasoning) {
    return new ModelDescriptor(
        "test-anthropic",
        "claude-3-7-sonnet",
        "claude-3-7-sonnet",
        Set.of(ModelInputModality.TEXT),
        true,
        reasoning,
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
            BigDecimal.ZERO));
  }
}
