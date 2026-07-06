package fun.fengwk.kkstudio.agent.provider.support;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.provider.Provider;
import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderManager;
import fun.fengwk.kkstudio.agent.provider.ProviderManagerImpl;
import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;
import fun.fengwk.kkstudio.agent.tool.ToolInfo;
import fun.fengwk.kkstudio.agent.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolStringSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;

import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.provider.Provider;
import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderManager;
import fun.fengwk.kkstudio.agent.provider.ProviderManagerImpl;
import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;
import fun.fengwk.kkstudio.agent.tool.ToolInfo;
import fun.fengwk.kkstudio.agent.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolStringSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;

/**
 * Provider 测试公共基座。
 *
 * @author fengwk
 */
abstract class AbstractProviderTestSupport {

  private static final ProviderManager PROVIDER_MANAGER = new ProviderManagerImpl();

  /** 返回当前测试目标的 provider 类型。 */
  protected abstract ProviderType providerType();

  /**
   * 返回当前测试目标的 provider 名称。
   *
   * <p>约定： - 必须与 ProviderTestFixtures 中 contract fixture 的 providerName 对齐。
   */
  protected abstract String providerName();

  /** 返回 live test 需要读取的 BASE_URL 环境变量名。 */
  protected abstract String baseUrlEnvName();

  /** 返回 live test 需要读取的 API_KEY 环境变量名。 */
  protected abstract String apiKeyEnvName();

  /** 返回当前 provider live test 使用的模型名。 */
  protected abstract String modelName();

  /** 使用环境变量中的 ProviderInfo 构造一个真实 Provider。 */
  protected Provider provider() {
    return PROVIDER_MANAGER.getProvider(providerInfo());
  }

  /**
   * 从环境变量读取 live test 所需 ProviderInfo。
   *
   * <p>当变量缺失时，使用 assume 让对应 live test 自动跳过。
   */
  protected ProviderInfo providerInfo() {
    String baseUrl = System.getenv(baseUrlEnvName());
    String apiKey = System.getenv(apiKeyEnvName());
    Assumptions.assumeTrue(
        baseUrl != null && !baseUrl.isBlank(), () -> "missing env: " + baseUrlEnvName());
    Assumptions.assumeTrue(
        apiKey != null && !apiKey.isBlank(), () -> "missing env: " + apiKeyEnvName());
    return providerInfo(baseUrl, apiKey);
  }

  /**
   * 使用指定 baseUrl/apiKey 构造 ProviderInfo。
   *
   * <p>该方法主要供 HTTP contract test 使用，以便把 provider 指向本地探针服务。
   */
  protected ProviderInfo providerInfo(String baseUrl, String apiKey) {
    return ProviderInfo.builder()
        .providerType(providerType())
        .baseUrl(baseUrl)
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(90))
        .streamIdleTimeout(Duration.ofSeconds(90))
        .build();
  }

  /** 构造当前 provider test 使用的最小 ModelInfo。 */
  protected ModelInfo modelInfo() {
    return ModelInfo.builder().provider(providerName()).name(modelName()).build();
  }

  /**
   * 构造 provider test 使用的基础 Variant。
   *
   * <p>约定： - 使用固定 temperature，降低 live test 波动。 - 只保留最小必要参数，避免把测试与无关请求参数耦合。
   */
  protected Variant variant() {
    return Variant.builder().name("live").temperature(0.0D).maxOutputTokens(512).build();
  }

  /**
   * 构造一份最小 echo tool 描述。
   *
   * <p>这份工具描述同时用于： - live test 的真实 tool-call 场景 - contract test 的 request schema 校验
   */
  protected ToolInfo echoTool() {
    return ToolInfo.builder()
        .name("echo")
        .description("Echo the input text without modification")
        .inputSchema(
            ToolParamsSchema.builder()
                .description("echo parameters")
                .properties(
                    Map.of("text", ToolStringSchema.builder().description("text to echo").build()))
                .required(List.of("text"))
                .additionalProperties(false)
                .build())
        .build();
  }

  /** 校验 provider 返回的最基本 metadata。 */
  protected void assertCommonMetadata(AssistantMetadata metadata) {
    assertNotNull(metadata, () -> providerName() + " metadata must not be null");
    assertNotNull(
        metadata.getModelName(), () -> providerName() + " metadata.modelName must not be null");
  }
}
