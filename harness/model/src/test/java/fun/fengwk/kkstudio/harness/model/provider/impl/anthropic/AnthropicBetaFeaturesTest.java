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
import java.util.UUID;

class AnthropicBetaFeaturesTest {
  private static final ProviderDescriptor DESCRIPTOR =
      new ProviderDescriptor(
          "anthropic",
          ProviderType.ANTHROPIC,
          "https://example.invalid/v1",
          new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)),
          new UUID(1, 2));
  private static final ModelDescriptor MODEL =
      new ModelDescriptor(
          "anthropic", "model", "model", Set.of(ModelInputModality.TEXT), true, true);

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
    return new ProviderRequest(
        MODEL,
        variant,
        4096,
        "system",
        List.of(
            new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("hi")))),
        List.of(),
        ProviderCacheControl.none());
  }
}
