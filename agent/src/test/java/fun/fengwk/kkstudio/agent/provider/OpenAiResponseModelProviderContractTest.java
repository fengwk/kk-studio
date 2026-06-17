package fun.fengwk.kkstudio.agent.provider;

import fun.fengwk.kkstudio.agent.provider.support.AbstractProviderContractTestSupport;

/**
 * @author fengwk
 */
public class OpenAiResponseModelProviderContractTest extends AbstractProviderContractTestSupport {

    /**
     * 指定当前 contract test 目标为 OpenAI Responses provider。
     */
    @Override
    protected ProviderType providerType() {
        return ProviderType.openai_response;
    }

    /**
     * 返回当前 provider fixture 主键。
     */
    @Override
    protected String providerName() {
        return "openai_response";
    }

    /**
     * 返回 OpenAI Responses 测试使用的 BASE_URL 环境变量名。
     */
    @Override
    protected String baseUrlEnvName() {
        return "TEST_OPENAI_RESPONSE_BASE_URL";
    }

    /**
     * 返回 OpenAI Responses 测试使用的 API_KEY 环境变量名。
     */
    @Override
    protected String apiKeyEnvName() {
        return "TEST_OPENAI_RESPONSE_API_KEY";
    }

    /**
     * 返回当前 contract test 使用的模型名。
     */
    @Override
    protected String modelName() {
        return "gpt-5.4";
    }

}
