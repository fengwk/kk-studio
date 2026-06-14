package fun.fengwk.kkstudio.core.agent.runtime.repo;

import fun.fengwk.kkstudio.core.agent.runtime.engine.AgentRequestTask;

import java.util.List;

/**
 * @author fengwk
 */
public interface AgentRequestTaskRepository {

    String generateTaskId();

    /**
     * 新增 task。
     * <p>
     * 如果 task 带有 idempotencyKey，实现必须在同一 session 内按该键去重，并返回已存在或新写入的 task。
     */
    AgentRequestTask add(AgentRequestTask agentRequestTask);

    /**
     * 在当前事务中锁定并返回未消费 task。
     * <p>
     * 多节点实现应使用行锁或等价机制保证同一批 task 不会被并发 turn 同时消费。该方法不修改 task 状态；
     * 后续事件写入成功后再由 {@link #consumeAll(List, String)} 标记消费。如果事务回滚，锁和消费状态会一起回滚。
     * 返回结果必须按提交顺序稳定排序，建议使用 create_time ASC, task_id ASC；limit 表示本次最多锁定多少条。
     */
    List<AgentRequestTask> listPendingForUpdate(String sessionId, int limit);

    /** 将已写入 user message event 的 task 标记为已消费，并记录消费它们的首个 turnId。 */
    boolean consumeAll(List<String> taskIdList, String turnId);

    /** 是否仍有未消费用户提交。用于 final answer 后的轻量接力。 */
    boolean hasPending(String sessionId);

}
