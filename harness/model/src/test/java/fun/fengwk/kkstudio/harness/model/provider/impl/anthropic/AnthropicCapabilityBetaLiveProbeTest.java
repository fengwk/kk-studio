package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.model.provider.ProviderSystemToolAdditionBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.impl.transport.JdkHttpSseTransport;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 显式付费门禁的 Anthropic 能力 beta 实测；只访问 TEST_ANTHROPIC 凭据指向的线路，不重试。
 *
 * <p>默认只做免费 wire-shape 检查：用生产 {@link AnthropicRequestEncoder} 编码一条同时包含 baseline client tools、中途
 * SYSTEM tool addition 与 message-level effort marker 的请求，断言真实发送的三个 beta 标识。 真实请求仅在 {@code
 * KK_STUDIO_REAL_BETA_PROBE=true} 且 TEST_ANTHROPIC 凭据完整时执行一次。
 *
 * <p>{@code claude-fable-5-2} 由网关 models 列表确认存在；kk 的 Claude capability 规则（frontend {@code
 * claude-model-capabilities.ts}）同时把它标记为 {@code midConversationSystemMessages} 与 {@code
 * messageLevelEffort}。
 */
class AnthropicCapabilityBetaLiveProbeTest {
  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final String LIVE_MODEL = "claude-fable-5-2";
  private static final String EXPECTED_BETAS =
      "mid-conversation-output-config-2026-07-01,thinking-binding-controls-2026-08-01,"
          + "inline-tools-2026-09-15";
  private static final ModelCallTimeoutPolicy TIMEOUT =
      new ModelCallTimeoutPolicy(Duration.ofSeconds(120), Duration.ofSeconds(120));
  private static final ProviderToolDefinition BASELINE_TOOL =
      new ProviderToolDefinition(
          "read",
          "Read a text file.",
          "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},"
              + "\"required\":[\"path\"],\"additionalProperties\":false}");
  private static final ProviderToolDefinition ADDED_TOOL =
      new ProviderToolDefinition(
          "grep",
          "Search files for a pattern.",
          "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\"}},"
              + "\"required\":[\"pattern\"],\"additionalProperties\":false}");

  // 免费验证生产 encoder 同时输出三种能力 beta，并携带真实的 tool_addition 与 effort marker。
  @Test
  void offlineShapeEmitsCapabilityBetasAndWireFacts() throws Exception {
    AnthropicEncodedRequest encoded = new AnthropicRequestEncoder().encode(request(), descriptor());
    assertEquals(List.of(EXPECTED_BETAS.split(",")), encoded.betaFeatures());
    JsonNode root = JSON.readTree(encoded.bodyUtf8Bytes());
    assertEquals(LIVE_MODEL, root.path("model").asString(""));
    assertTrue(root.path("tools").size() >= 1, "baseline tools must be encoded");
    boolean toolAddition = false;
    boolean effortMarker = false;
    for (JsonNode message : root.path("messages")) {
      if (!"system".equals(message.path("role").asString())) {
        continue;
      }
      for (JsonNode block : message.path("content")) {
        toolAddition |= "tool_addition".equals(block.path("type").asString());
      }
      effortMarker |= message.path("output_config").has("effort");
    }
    assertTrue(toolAddition, "mid-conversation SYSTEM tool addition must be encoded");
    assertTrue(effortMarker, "message-level effort marker must be encoded");
    assertFalse(encoded.betaFeatures().isEmpty());
  }

  // 只在明确授权时发一次真实请求；缺任一凭据失败，不静默 skip、改线路或改模型。
  @Test
  @EnabledIfEnvironmentVariable(named = "KK_STUDIO_REAL_BETA_PROBE", matches = "true")
  void providerAcceptsCapabilityBetas() throws Exception {
    String base = System.getenv("TEST_ANTHROPIC_BASE_URL");
    String key = System.getenv("TEST_ANTHROPIC_API_KEY");
    assertTrue(
        base != null && !base.isBlank() && key != null && !key.isBlank(),
        "classification=MISSING_CREDENTIAL_PAIR");
    ProviderDescriptor descriptor =
        new ProviderDescriptor("anthropic", ProviderType.ANTHROPIC, base, TIMEOUT);
    AnthropicEncodedRequest encoded = new AnthropicRequestEncoder().encode(request(), descriptor);
    assertEquals(List.of(EXPECTED_BETAS.split(",")), encoded.betaFeatures());

    AtomicReference<ProviderCompletion> completion = new AtomicReference<>();
    AtomicReference<ProviderException> failure = new AtomicReference<>();
    AtomicInteger events = new AtomicInteger();
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      // 付费路径完全走生产 provider：同一份编码请求、同一套 header 与流式解析。
      ModelProvider provider =
          new AnthropicProviderAdapter(new JdkHttpSseTransport(client, scheduler), key)
              .create(descriptor);
      provider.stream(
          request(),
          new ProviderStreamHandler() {
            @Override
            public void onEvent(ProviderStreamEvent event) {
              events.incrementAndGet();
            }

            @Override
            public void onComplete(ProviderCompletion value) {
              completion.set(value);
            }

            @Override
            public void onError(ProviderException error) {
              failure.set(error);
            }
          });
    } catch (AssertionError error) {
      throw error;
    } catch (Exception error) {
      // 不把上游、URI、headers 或 cause 放进 Surefire 报告。
      throw new AssertionError("classification=LOCAL_OR_PROTOCOL_FAILURE");
    } finally {
      scheduler.shutdownNow();
      client.shutdownNow();
    }

    assertTrue(
        failure.get() == null,
        "classification=PROVIDER_ERROR kind="
            + (failure.get() == null ? "<none>" : failure.get().kind()));
    ProviderCompletion accepted = completion.get();
    assertNotNull(accepted, "classification=NO_TERMINAL_STATE events=" + events.get());
    assertTrue(events.get() > 0, "classification=NO_STREAM_EVENTS");
    System.out.println(
        "classification=ACCEPTED model="
            + LIVE_MODEL
            + " beta="
            + String.join(",", encoded.betaFeatures())
            + " events="
            + events.get()
            + " stopReason="
            + accepted.response().stopReason());
  }

  private static ProviderDescriptor descriptor() {
    return new ProviderDescriptor(
        "anthropic", ProviderType.ANTHROPIC, "https://example.invalid/v1", TIMEOUT);
  }

  /**
   * baseline client tool + 中途 SYSTEM tool addition + 历史 assistant effort（high）与当前 effort（medium）
   * 不同的 message-level marker。
   */
  private static ProviderRequest request() {
    ModelDescriptor model =
        new ModelDescriptor(
            "anthropic",
            LIVE_MODEL,
            LIVE_MODEL,
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            true,
            true);
    ModelVariant variant = new ModelVariant("medium", "medium", PromptCacheRetention.NONE, null);
    List<ProviderMessage> messages =
        List.of(
            new ProviderMessage(
                ProviderMessageRole.USER, List.of(new ProviderTextBlock("First turn."))),
            new ProviderMessage(
                ProviderMessageRole.ASSISTANT, List.of(new ProviderTextBlock("OK")), "high"),
            // 中途 SYSTEM tool addition 必须紧跟 USER，并前置于 ASSISTANT 回复。
            new ProviderMessage(
                ProviderMessageRole.USER, List.of(new ProviderTextBlock("Add the grep tool."))),
            new ProviderMessage(
                ProviderMessageRole.SYSTEM,
                List.of(new ProviderSystemToolAdditionBlock(ADDED_TOOL))),
            new ProviderMessage(
                ProviderMessageRole.ASSISTANT,
                List.of(new ProviderTextBlock("Tool added.")),
                "high"),
            new ProviderMessage(
                ProviderMessageRole.USER, List.of(new ProviderTextBlock("Reply with OK."))));
    return new ProviderRequest(
        model,
        variant,
        256,
        "Reply briefly.",
        messages,
        List.of(BASELINE_TOOL),
        ProviderCacheControl.none(),
        true);
  }
}
