package fun.fengwk.kkstudio.core.agent.runtime.event;

/**
 * @author fengwk
 */
public enum EventSessionStatus {

    /**
     * 会话空闲，可以由 submit 触发新的模型 turn。
     */
    idle,

    /**
     * 会话正在由某个节点上的异步 callback 链推进。
     * <p>
     * 失败/中止等业务结果由 head event 表达，session status 只负责最小并发门禁。
     */
    busy,

    ;

}
