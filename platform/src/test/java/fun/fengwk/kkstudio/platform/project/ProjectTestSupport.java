package fun.fengwk.kkstudio.platform.project;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Project / Issue 领域集成测试共享基类。 基于真实 PostgreSQL Testcontainers 提供测试数据准备与清理辅助方法。 */
public abstract class ProjectTestSupport extends PostgresSpringTestSupport {

  private static final AtomicLong FIXTURE_COUNTER = new AtomicLong();

  @Autowired protected JdbcTemplate jdbcTemplate;

  /** 插入一个最小合法的 AgentDefinition 行及其关联的 provider 和 model。 */
  protected String createTestAgent() {
    long id = FIXTURE_COUNTER.incrementAndGet();
    String providerName = "prov-" + id;
    String modelName = "mod-" + id;
    String agentName = "agent-" + id;

    jdbcTemplate.update(
        "insert into agent_provider (name, provider_type, config, connection_generation_id) "
            + "values (?, 'openai', '{}'::jsonb, ?::uuid)",
        providerName,
        UUID.randomUUID());

    jdbcTemplate.update(
        "insert into agent_model (provider_name, name, model_id, config) "
            + "values (?, ?, ?, '{}'::jsonb)",
        providerName,
        modelName,
        "wire-" + id);

    jdbcTemplate.update(
        "insert into agent_definition (name, model_provider_name, model_name, config) "
            + "values (?, ?, ?, '{}'::jsonb)",
        agentName,
        providerName,
        modelName);

    return agentName;
  }

  /** 插入一个合法的 harness_session 最小行。 */
  protected UUID createHarnessSession() {
    UUID sessionId = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into harness_session (id, name, created_at) values (?, ?, current_timestamp)",
        sessionId,
        "test-session-" + sessionId);
    return sessionId;
  }

  protected static UUID randomId() {
    return UUID.randomUUID();
  }
}
