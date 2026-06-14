package fun.fengwk.kkstudio.core.agent.runtime.event;

import fun.fengwk.kkstudio.core.agent.runtime.provider.ModelRequestConfig;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ProviderConfig;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * @author fengwk
 */
@Builder
@Data
public class EventSession {

    /** 会话 ID，是外部 submit/fork/subscribe 的稳定入口。 */
    private String sessionId;

    /** 当前分支的叶子事件。多节点通过 CAS 推进该字段来保证同一 session 单执行链。 */
    private String headEventId;
//    private String eventTreeId;

    /** 会话运行状态，仅用于触发门禁；失败/中止等结果由 head event 表达。 */
    private EventSessionStatus status;

    /** 当前 busy turn 的 ID，等于本轮 turn_start eventId。 */
    private String runningTurnId;

    /** 当前 busy turn 开始时间，用于上层识别 stale running，不由内核自动恢复。 */
    private LocalDateTime runningSince;

    /** 最近一次事件写入时间，用于调试与上层观测。 */
    private LocalDateTime lastEventAt;

    /** 当前会话默认 provider 配置，由最新 submit 的 task 更新。 */
    private ProviderConfig providerConfig;

    /** 当前会话默认模型请求配置，由最新 submit 的 task 更新。 */
    private ModelRequestConfig modelRequestConfig;

    /** 预留的扩展参数，后续用于工具、上下文或 UI 控制。 */
    private Map<String, Object> parameters;

    /** 当前会话 system prompt。为空时不向模型追加 SystemMessage。 */
    private String systemPrompt;

}
