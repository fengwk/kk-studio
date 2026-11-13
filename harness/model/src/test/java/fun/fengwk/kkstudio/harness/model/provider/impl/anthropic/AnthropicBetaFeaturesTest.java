package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.ProviderProtocolOptions;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.time.Duration;
import java.util.List;
import java.util.Set;

class AnthropicBetaFeaturesTest {
  private static final ProviderDescriptor DESCRIPTOR =
      new ProviderDescriptor(
          "anthropic",
          ProviderType.ANTHROPIC,
          "https://example.invalid/v1",
          new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)));
  private static final ModelDescriptor MODEL =
      new ModelDescriptor(
          "anthropic", "model", "model", Set.of(ModelInputModality.TEXT), true, true);
  private static final ModelDescriptor MESSAGE_LEVEL_MODEL =
      new ModelDescriptor(
          "anthropic", "model", "model", Set.of(ModelInputModality.TEXT), true, true, false, true);
  private static final ModelDescriptor SYSTEM_MESSAGE_MODEL =
      new ModelDescriptor(
          "anthropic", "model", "model", Set.of(ModelInputModality.TEXT), true, true, true, false);

  // 显式 adaptive effort 必须继续附加消息级控制所需 beta。
  @Test
  void includesEffortBetasOnlyWhenEffortEnabled() {
    AnthropicRequestEncoder encoder = new AnthropicRequestEncoder();
    assertEquals(
        List.of("mid-conversation-output-config-2026-07-01"),
        encoder
            .encode(
                request(new ModelVariant("v", "high", PromptCacheRetention.NONE, null)), DESCRIPTOR)
            .betaFeatures());
    assertEquals(
        List.of(),
        encoder
            .encode(
                request(new ModelVariant("v", "off", PromptCacheRetention.NONE, null)), DESCRIPTOR)
            .betaFeatures());
  }

  @Test
  void alwaysIncludesMessageLevelBetasWhenThinkingIsNotDisabled() {
    AnthropicRequestEncoder encoder = new AnthropicRequestEncoder();
    List<String> expected =
        List.of(
            "mid-conversation-output-config-2026-07-01", "thinking-binding-controls-2026-08-01");
    ModelVariant noEffort = new ModelVariant("v", null, PromptCacheRetention.NONE, null);
    List<ProviderMessage> userOnly = List.of(user("hi"));
    List<ProviderMessage> withSystemChange = List.of(user("hi"), system("updated"));

    assertEquals(
        expected,
        encoder
            .encode(request(MESSAGE_LEVEL_MODEL, noEffort, userOnly), DESCRIPTOR)
            .betaFeatures());
    assertEquals(
        expected,
        encoder
            .encode(request(MESSAGE_LEVEL_MODEL, noEffort, withSystemChange), DESCRIPTOR)
            .betaFeatures());
    assertEquals(
        List.of(),
        encoder
            .encode(
                request(
                    MESSAGE_LEVEL_MODEL,
                    new ModelVariant("v", "off", PromptCacheRetention.NONE, null),
                    withSystemChange),
                DESCRIPTOR)
            .betaFeatures());
  }

  @Test
  void doesNotEnableOutputConfigBetaForSystemMessagesAlone() {
    ProviderRequest request =
        request(
            SYSTEM_MESSAGE_MODEL,
            new ModelVariant("v", null, PromptCacheRetention.NONE, null),
            List.of(user("hi"), system("updated")));
    assertEquals(
        List.of(), new AnthropicRequestEncoder().encode(request, DESCRIPTOR).betaFeatures());
  }

  // 原生预算思考按原样下发，不自动开启 interleaved beta。
  @Test
  void passesNativeBudgetWithoutAddingInterleavedBeta() {
    ModelVariant variant =
        new ModelVariant(
            "budget",
            null,
            PromptCacheRetention.NONE,
            new ProviderProtocolOptions(
                "{\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":2048}}"));
    AnthropicEncodedRequest encoded =
        new AnthropicRequestEncoder().encode(request(variant), DESCRIPTOR);
    assertEquals(List.of(), encoded.betaFeatures());
    var thinking = JsonMapper.builder().build().readTree(encoded.bodyUtf8Bytes()).path("thinking");
    assertEquals("enabled", thinking.path("type").asString());
    assertEquals(2048, thinking.path("budget_tokens").asInt());
  }

  private static ProviderRequest request(ModelVariant variant) {
    return request(MODEL, variant, List.of(user("hi")));
  }

  private static ProviderRequest request(
      ModelDescriptor model, ModelVariant variant, List<ProviderMessage> messages) {
    return new ProviderRequest(
        model, variant, 4096, "system", messages, List.of(), ProviderCacheControl.none());
  }

  private static ProviderMessage user(String text) {
    return new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock(text)));
  }

  private static ProviderMessage system(String text) {
    return new ProviderMessage(ProviderMessageRole.SYSTEM, List.of(new ProviderTextBlock(text)));
  }
}
