package fun.fengwk.kkstudio.platform.catalog.definition;

/** 产品对 Agent 的引用检查。删除前在持有 Agent 行锁的事务内调用。 */
public interface AgentReferenceCheck {

  boolean isReferenced(String agentName);
}
