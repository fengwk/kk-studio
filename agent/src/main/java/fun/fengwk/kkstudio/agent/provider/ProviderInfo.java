package fun.fengwk.kkstudio.agent.provider;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;

/**
 * ProviderInfo 表示一个 provider 的连接与超时配置。
 *
 * @author fengwk
 */
@Builder
@Data
public class ProviderInfo {

    /**
     * provider 类型。
     */
    private final ProviderType providerType;

    /**
     * provider 服务地址。
     */
    private final String baseUrl;

    /**
     * provider 访问凭据。
     */
    private final String apiKey;

    /**
     * provider 总体请求超时。
     */
    private final Duration timeout;

    /**
     * provider 流式间隔超时。
     */
    private final Duration streamIdleTimeout;

}
