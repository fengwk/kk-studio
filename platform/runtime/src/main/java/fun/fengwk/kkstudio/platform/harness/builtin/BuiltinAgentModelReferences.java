package fun.fengwk.kkstudio.platform.harness.builtin;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Objects;

/** Builtin execution 中的模型引用；调用方须先在事务中锁定模型行。 */
public final class BuiltinAgentModelReferences {
  private final JdbcClient jdbcClient;

  public BuiltinAgentModelReferences(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  public List<String> lockAgents(String modelName) {
    return jdbcClient
        .sql(
            "select name from agent_definition where execution ->> 'driver' = 'builtin'"
                + " and execution -> 'settings' ->> 'modelName' = :name order by name for update")
        .param("name", modelName)
        .query(String.class)
        .list();
  }

  public void renameLockedAgents(List<String> agents, String modelName) {
    for (String agent : agents) {
      jdbcClient
          .sql(
              "update agent_definition set execution = jsonb_set(execution,"
                  + " '{settings,modelName}', to_jsonb(cast(:modelName as text))),"
                  + " version = version + 1,"
                  + " updated_at = greatest(updated_at, current_timestamp) where name = :name")
          .param("modelName", modelName)
          .param("name", agent)
          .update();
    }
  }

  public boolean hasAgents(String modelName) {
    return !lockAgents(modelName).isEmpty();
  }

  public boolean hasVariant(String modelName, String variant) {
    return !jdbcClient
        .sql(
            "select name from agent_definition where execution ->> 'driver' = 'builtin'"
                + " and execution -> 'settings' ->> 'modelName' = :name"
                + " and execution -> 'settings' ->> 'variant' = :variant"
                + " order by name for update")
        .param("name", modelName)
        .param("variant", variant)
        .query(String.class)
        .list()
        .isEmpty();
  }
}
