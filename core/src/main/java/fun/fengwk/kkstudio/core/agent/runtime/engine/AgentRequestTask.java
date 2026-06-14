package fun.fengwk.kkstudio.core.agent.runtime.engine;

import fun.fengwk.kkstudio.core.agent.runtime.provider.ModelRequestConfig;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ProviderConfig;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * @author fengwk
 */
@Data
public class AgentRequestTask {

    /** submit 生成的任务 ID，调用方可用它追踪提交是否被消费。 */
    private String taskId;

    /** 任务所属 session。 */
    private String sessionId;

    /** 消费该任务的首个 turnId。 */
    private String turnId;

    /** 幂等键，预留给 HTTP/RPC 重试去重。 */
    private String idempotencyKey;

    /** 创建时间，锁定未消费 task 时可用于定义快照边界。 */
    private LocalDateTime createTime;

    private ProviderConfig providerConfig;
    private ModelRequestConfig modelRequestConfig;
    private Map<String, Object> parameters;
    private String systemPrompt;
    private String userMessage;

    /** 是否已经被某个 turn 消费。 */
    private boolean consume;

}
