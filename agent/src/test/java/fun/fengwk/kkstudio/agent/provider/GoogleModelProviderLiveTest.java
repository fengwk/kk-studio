package fun.fengwk.kkstudio.agent.provider;

import fun.fengwk.kkstudio.agent.provider.support.AbstractProviderLiveTestSupport;

/**
 * @author fengwk
 */
public class GoogleModelProviderLiveTest extends AbstractProviderLiveTestSupport {

    /**
     * 指定当前 live test 目标为 Google Gemini provider。
     */
    @Override
    protected ProviderType providerType() {
        return ProviderType.google;
    }

    /**
     * 返回当前 provider fixture 主键。
     */
    @Override
    protected String providerName() {
        return "google";
    }

    /**
     * 返回 Google Gemini 测试使用的 BASE_URL 环境变量名。
     */
    @Override
    protected String baseUrlEnvName() {
        return "TEST_GOOGLE_GEMINI_BASE_URL";
    }

    /**
     * 返回 Google Gemini 测试使用的 API_KEY 环境变量名。
     */
    @Override
    protected String apiKeyEnvName() {
        return "TEST_GOOGLE_GEMINI_API_KEY";
    }

    /**
     * 返回当前 live test 使用的模型名。
     */
    @Override
    protected String modelName() {
        return "gemini-3.5-flash";
    }

}
