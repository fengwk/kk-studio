package fun.fengwk.kkstudio.core.agent.runtime.engine;

import fun.fengwk.kkstudio.core.agent.runtime.provider.ModelRequestConfig;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ProviderConfig;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * @author fengwk
 */
@Builder
@Data
public class AgentRequest {

    private final String sessionId;
    /** 调用方提供的幂等键，避免 HTTP/RPC 超时重试造成重复 task。 */
    private final String idempotencyKey;
    private final ProviderConfig providerConfig;
    private final ModelRequestConfig modelRequestConfig;
    private final Map<String, Object> parameters;
    private final String systemPrompt; // 系统提示词
    private final String userMessage; // 用户提示词
    /** 显式请求在 session 处于 busy 时立即按最新配置检查是否 stale；未超时则仍只排队。 */
    private final boolean recoverIfBusy;
    /** stale 恢复成功时写入 ErrorEvent 的原因。 */
    private final String recoverReason;

}
