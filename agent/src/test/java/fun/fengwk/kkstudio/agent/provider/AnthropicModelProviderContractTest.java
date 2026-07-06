package fun.fengwk.kkstudio.agent.provider;

import fun.fengwk.kkstudio.agent.provider.support.AbstractProviderContractTestSupport;

import fun.fengwk.kkstudio.agent.provider.support.AbstractProviderContractTestSupport;

/**
 * @author fengwk
 */
public class AnthropicModelProviderContractTest extends AbstractProviderContractTestSupport {

  /** 指定当前 contract test 目标为 Anthropic 兼容 provider。 */
  @Override
  protected ProviderType providerType() {
    return ProviderType.anthropic;
  }

  /** 返回当前 provider fixture 主键。 */
  @Override
  protected String providerName() {
    return "anthropic";
  }

  /** 返回 Anthropic 测试使用的 BASE_URL 环境变量名。 */
  @Override
  protected String baseUrlEnvName() {
    return "TEST_ANTHROPIC_BASE_URL";
  }

  /** 返回 Anthropic 测试使用的 API_KEY 环境变量名。 */
  @Override
  protected String apiKeyEnvName() {
    return "TEST_ANTHROPIC_API_KEY";
  }

  /** 返回当前 contract test 使用的模型名。 */
  @Override
  protected String modelName() {
    return "MiniMax-M2.7";
  }
}
