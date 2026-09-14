package fun.fengwk.kkstudio.platform.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import fun.fengwk.kkstudio.harness.provider.anthropic.AnthropicConfiguration;
import fun.fengwk.kkstudio.harness.provider.anthropic.AnthropicProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.anthropic.AnthropicThinkingMode;
import fun.fengwk.kkstudio.harness.provider.gemini.GeminiProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.openai.chat.OpenAiChatProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.openai.responses.OpenAiResponsesProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.net.http.HttpClient;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 验证 {@link ModelExecutionConfiguration} 暴露的 4 个 named ProviderFactory bean 全部被收集到 {@link
 * ProviderFactories}，避免 Spring 按返回类型匹配时丢失 bean；同时验证生产 {@link PlatformModelGateway} 被组合进上下文。
 */
class ModelExecutionConfigurationTest extends PostgresSpringTestSupport {

  @Autowired
  @Qualifier("openaiProviderFactory")
  private ProviderFactory openaiProviderFactory;

  @Autowired
  @Qualifier("openaiResponsesProviderFactory")
  private ProviderFactory openaiResponsesProviderFactory;

  @Autowired
  @Qualifier("anthropicProviderFactory")
  private ProviderFactory anthropicProviderFactory;

  @Autowired
  @Qualifier("googleProviderFactory")
  private ProviderFactory googleProviderFactory;

  @Autowired private ProviderFactories providerFactories;

  @Autowired private PlatformModelGateway platformModelGateway;

  @Autowired
  @Qualifier("modelExecutionExecutor")
  private ExecutorService modelExecutionExecutor;

  @Autowired
  @Qualifier("modelExecutionHttpClient")
  private HttpClient modelExecutionHttpClient;

  @Autowired
  @Qualifier("modelExecutionWatchdogScheduler")
  private ScheduledExecutorService modelExecutionWatchdogScheduler;

  @Autowired
  @Qualifier("modelExecutionTransport")
  private JdkHttpSseTransport modelExecutionTransport;

  @Test
  void composesPlatformModelGatewayWithSharedExecutorAndConfig() {
    assertNotNull(platformModelGateway);
  }

  /** 意图：原生 Provider 复用 Spring 托管 worker，禁止重定向，并由独立 Watchdog 调度器驱动超时。 */
  @Test
  void configuresManagedNativeProviderTransport() {
    assertEquals(HttpClient.Redirect.NEVER, modelExecutionHttpClient.followRedirects());
    assertSame(modelExecutionExecutor, modelExecutionHttpClient.executor().orElseThrow());
    assertNotNull(modelExecutionTransport);
    assertFalse(modelExecutionWatchdogScheduler.isShutdown());
  }

  @Test
  void registersAllFourNamedProviderFactoryBeans() {
    assertEquals(ProviderType.OPENAI, openaiProviderFactory.providerType());
    assertEquals(ProviderType.OPENAI_RESPONSES, openaiResponsesProviderFactory.providerType());
    assertEquals(ProviderType.ANTHROPIC, anthropicProviderFactory.providerType());
    assertEquals(ProviderType.GOOGLE, googleProviderFactory.providerType());
    assertEquals(
        Set.of(PromptCacheRetention.SHORT, PromptCacheRetention.LONG),
        anthropicProviderFactory.promptCacheCapability().supportedRetentions());
    assertEquals(
        EnumSet.allOf(PromptCacheBreakpoint.class),
        anthropicProviderFactory.promptCacheCapability().supportedBreakpoints());
  }

  @Test
  void providerFactoriesLookupAllFourTypes() {
    assertNotNull(providerFactories.lookup(ProviderType.OPENAI).orElseThrow());
    assertNotNull(providerFactories.lookup(ProviderType.OPENAI_RESPONSES).orElseThrow());
    assertNotNull(providerFactories.lookup(ProviderType.ANTHROPIC).orElseThrow());
    assertNotNull(providerFactories.lookup(ProviderType.GOOGLE).orElseThrow());
    assertSame(openaiProviderFactory, providerFactories.lookup(ProviderType.OPENAI).orElseThrow());
    assertSame(
        openaiResponsesProviderFactory,
        providerFactories.lookup(ProviderType.OPENAI_RESPONSES).orElseThrow());
    assertSame(
        anthropicProviderFactory, providerFactories.lookup(ProviderType.ANTHROPIC).orElseThrow());
    assertSame(googleProviderFactory, providerFactories.lookup(ProviderType.GOOGLE).orElseThrow());
  }

  /** 意图：验证 Spring 装配的 ProviderFactory 返回原生 Harness Provider 适配器，并共享 transport。 */
  @Test
  void createsTheAdapterMatchingEachProviderType() {
    assertInstanceOf(
        OpenAiChatProviderAdapter.class, openaiProviderFactory.create("credential", "{}"));
    assertInstanceOf(
        OpenAiResponsesProviderAdapter.class,
        openaiResponsesProviderFactory.create("credential", "{}"));
    assertInstanceOf(
        AnthropicProviderAdapter.class, anthropicProviderFactory.create("credential", "{}"));
    assertInstanceOf(GeminiProviderAdapter.class, googleProviderFactory.create("credential", "{}"));
  }

  /** 意图：验证每个 Spring ProviderFactory 均能贯通原生 adapter 并创建对应 ModelProvider。 */
  @Test
  void createsNativeModelProviderThroughEveryFactory() {
    assertNativeModelProvider(openaiProviderFactory, ProviderType.OPENAI, "{}");
    assertNativeModelProvider(
        openaiResponsesProviderFactory,
        ProviderType.OPENAI_RESPONSES,
        "{\"openAiPromptCacheMode\":\"GPT_5_6_EXPLICIT\"}");
    assertNativeModelProvider(anthropicProviderFactory, ProviderType.ANTHROPIC, "{}");
    assertNativeModelProvider(googleProviderFactory, ProviderType.GOOGLE, "{}");
  }

  /** 意图：验证 OpenAI Chat 工厂基于 configJson 正确解析 AUTOMATIC、LEGACY 与 GPT_5_6_EXPLICIT 三种模式。 */
  @Test
  void openAiChatFactoryResolvesDynamicPromptCacheCapabilities() {
    // 默认空配置 -> AUTOMATIC
    assertEquals(PromptCacheMode.AUTOMATIC, openaiProviderFactory.promptCacheCapability().mode());
    assertEquals(
        PromptCacheMode.AUTOMATIC, openaiProviderFactory.promptCacheCapability(null).mode());
    assertEquals(
        PromptCacheMode.AUTOMATIC, openaiProviderFactory.promptCacheCapability("{}").mode());

    // LEGACY -> AFFINITY (SHORT, LONG)
    PromptCacheCapability legacy =
        openaiProviderFactory.promptCacheCapability("{\"openAiPromptCacheMode\":\"LEGACY\"}");
    assertEquals(PromptCacheMode.AFFINITY, legacy.mode());
    assertEquals(
        Set.of(PromptCacheRetention.SHORT, PromptCacheRetention.LONG),
        legacy.supportedRetentions());

    // GPT_5_6_EXPLICIT -> BREAKPOINTS (SHORT + SYSTEM, CONVERSATION)
    PromptCacheCapability gptExplicit =
        openaiProviderFactory.promptCacheCapability(
            "{\"openAiPromptCacheMode\":\"GPT_5_6_EXPLICIT\"}");
    assertEquals(PromptCacheMode.BREAKPOINTS, gptExplicit.mode());
    assertEquals(Set.of(PromptCacheRetention.SHORT), gptExplicit.supportedRetentions());
    assertEquals(
        Set.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.CONVERSATION),
        gptExplicit.supportedBreakpoints());
  }

  /** 意图：验证 OpenAI Responses 工厂基于 configJson 正确解析 AUTOMATIC、LEGACY 与 GPT_5_6_EXPLICIT 三种模式。 */
  @Test
  void openAiResponsesFactoryResolvesDynamicPromptCacheCapabilities() {
    // 默认空配置 -> AUTOMATIC（Provider 自治管理，不发送 cache hint）
    for (PromptCacheCapability automatic :
        List.of(
            openaiResponsesProviderFactory.promptCacheCapability(),
            openaiResponsesProviderFactory.promptCacheCapability(null),
            openaiResponsesProviderFactory.promptCacheCapability("{}"))) {
      assertEquals(PromptCacheMode.AUTOMATIC, automatic.mode());
      assertEquals(Set.of(), automatic.supportedRetentions());
      assertEquals(Set.of(), automatic.supportedBreakpoints());
    }

    // LEGACY -> AFFINITY (SHORT, LONG)
    PromptCacheCapability legacy =
        openaiResponsesProviderFactory.promptCacheCapability(
            "{\"openAiPromptCacheMode\":\"LEGACY\"}");
    assertEquals(PromptCacheMode.AFFINITY, legacy.mode());
    assertEquals(
        Set.of(PromptCacheRetention.SHORT, PromptCacheRetention.LONG),
        legacy.supportedRetentions());

    // GPT_5_6_EXPLICIT -> BREAKPOINTS (SHORT + SYSTEM, CONVERSATION)
    PromptCacheCapability gptExplicit =
        openaiResponsesProviderFactory.promptCacheCapability(
            "{\"openAiPromptCacheMode\":\"GPT_5_6_EXPLICIT\"}");
    assertEquals(PromptCacheMode.BREAKPOINTS, gptExplicit.mode());
    assertEquals(Set.of(PromptCacheRetention.SHORT), gptExplicit.supportedRetentions());
    assertEquals(
        Set.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.CONVERSATION),
        gptExplicit.supportedBreakpoints());
  }

  /** 意图：验证 Google 与 Anthropic 工厂暴露固定的标准提示缓存能力。 */
  @Test
  void fixedFactoriesExposeExpectedPromptCacheCapabilities() {
    assertEquals(PromptCacheMode.AUTOMATIC, googleProviderFactory.promptCacheCapability().mode());
    assertEquals(
        PromptCacheMode.AUTOMATIC,
        googleProviderFactory.promptCacheCapability("{\"ignored\":true}").mode());

    assertEquals(
        PromptCacheMode.BREAKPOINTS, anthropicProviderFactory.promptCacheCapability().mode());
    assertEquals(
        Set.of(PromptCacheRetention.SHORT, PromptCacheRetention.LONG),
        anthropicProviderFactory.promptCacheCapability().supportedRetentions());
    assertEquals(
        EnumSet.allOf(PromptCacheBreakpoint.class),
        anthropicProviderFactory.promptCacheCapability().supportedBreakpoints());
  }

  /** 意图：验证 Anthropic 工厂将配置中的 anthropicThinkingMode 传递给适配器。 */
  @Test
  void anthropicFactoryAppliesConfiguredThinkingMode() {
    AnthropicProviderAdapter adapter =
        (AnthropicProviderAdapter)
            anthropicProviderFactory.create("credential", "{\"anthropicThinkingMode\":\"BUDGET\"}");
    assertEquals(AnthropicThinkingMode.BUDGET, adapter.configuration().anthropicThinkingMode());
  }

  /** 意图：验证 Anthropic 工厂只解析 thinking mode，已被删除的 modelAliases 字段不再保留任何状态。 */
  @Test
  void anthropicFactoryIgnoresRemovedModelAliasConfiguration() {
    // 别名映射不再是配置能力：解析成功后配置对象与默认值完全相等，不保留任何别名状态。
    assertEquals(
        AnthropicConfiguration.defaults(),
        AnthropicConfiguration.parse("{\"modelAliases\":{\"MiniMax-M3\":\"wire-name\"}}"));
  }

  /** 意图：验证 Anthropic 工厂拒绝非法配置且不回显配置内容与敏感信息，因果链无暴露。 */
  @Test
  void anthropicFactoryRejectsMalformedConfigurationWithoutExposingInput() {
    String sensitiveConfig = "{\"anthropicThinkingMode\":\"SUPER_SECRET_VALUE\"}";
    ProviderException ex =
        assertThrows(
            ProviderException.class,
            () -> anthropicProviderFactory.create("credential", sensitiveConfig));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, ex.kind());
    assertFalse(ex.getMessage().contains("SUPER_SECRET_VALUE"));
    assertNull(ex.getCause());

    String malformedJson = "{\"anthropicThinkingMode\":";
    ProviderException exMalformed =
        assertThrows(
            ProviderException.class,
            () -> anthropicProviderFactory.create("credential", malformedJson));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exMalformed.kind());
    assertNull(exMalformed.getCause());

    // 未知 provider 配置字段（含已删除的 modelAliases）必须被忽略，且配置对象绝不回显原始 payload。
    AnthropicConfiguration aliasIgnored =
        AnthropicConfiguration.parse("{\"modelAliases\":{\"key\":\" SUPER_SECRET_PAYLOAD \"}}");
    assertEquals(AnthropicConfiguration.defaults(), aliasIgnored);
    assertFalse(aliasIgnored.toString().contains("SUPER_SECRET_PAYLOAD"));

    String sensitiveValue = "{\"anthropicThinkingMode\":\" SENSITIVE_VALUE \"}";
    ProviderException exValue =
        assertThrows(
            ProviderException.class,
            () -> anthropicProviderFactory.create("credential", sensitiveValue));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, exValue.kind());
    assertFalse(exValue.getMessage().contains("SENSITIVE_VALUE"));
    assertNull(exValue.getCause());
  }

  private static void assertNativeModelProvider(
      ProviderFactory factory, ProviderType type, String configJson) {
    ModelProvider modelProvider =
        factory
            .create("credential", configJson)
            .create(
                new ProviderDescriptor(
                    "provider-" + type.wireValue(),
                    type,
                    "https://provider.example/v1",
                    ModelCallTimeoutPolicy.DEFAULT,
                    UUID.randomUUID()));
    assertNotNull(modelProvider);
  }
}
