package fun.fengwk.kkstudio.platform.catalog.model;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Objects;

/**
 * Builtin execution 中的模型引用。
 *
 * <p>改名在调用方已锁定模型行的同一事务内先按 name 锁定引用 Agent，再以一条 UPDATE 同步全部引用；存在性检查只读，不加行锁。
 */
public final class BuiltinAgentModelReferences {
  private final JdbcClient jdbcClient;

  public BuiltinAgentModelReferences(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  /** 按 name 升序锁定引用该模型的 Builtin Agent，直到外层事务结束。 */
  public List<String> lockAgents(String modelName) {
    return jdbcClient
        .sql(
            "select name from agent_definition where execution ->> 'driver' = 'builtin'"
                + " and execution -> 'settings' ->> 'modelName' = :name order by name for update")
        .param("name", modelName)
        .query(String.class)
        .list();
  }

  /** 一条 UPDATE 改写全部已锁定 Agent 的模型引用，并各自递增版本。 */
  public void renameLockedAgents(List<String> agents, String modelName) {
    if (agents.isEmpty()) {
      return;
    }
    jdbcClient
        .sql(
            "update agent_definition set execution = jsonb_set(execution,"
                + " '{settings,modelName}', to_jsonb(cast(:modelName as text))),"
                + " version = version + 1,"
                + " updated_at = greatest(updated_at, current_timestamp)"
                + " where name in (:agentNames)")
        .param("modelName", modelName)
        .param("agentNames", agents)
        .update();
  }

  public boolean hasAgents(String modelName) {
    return jdbcClient
        .sql(
            "select exists (select 1 from agent_definition"
                + " where execution ->> 'driver' = 'builtin'"
                + " and execution -> 'settings' ->> 'modelName' = :name)")
        .param("name", modelName)
        .query(Boolean.class)
        .single();
  }

  public boolean hasVariant(String modelName, String variant) {
    return jdbcClient
        .sql(
            "select exists (select 1 from agent_definition"
                + " where execution ->> 'driver' = 'builtin'"
                + " and execution -> 'settings' ->> 'modelName' = :name"
                + " and execution -> 'settings' ->> 'variant' = :variant)")
        .param("name", modelName)
        .param("variant", variant)
        .query(Boolean.class)
        .single();
  }
}
