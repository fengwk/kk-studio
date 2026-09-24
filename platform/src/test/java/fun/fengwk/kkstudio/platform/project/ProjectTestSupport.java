package fun.fengwk.kkstudio.platform.project;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Project / Issue 领域集成测试共享基类。 基于真实 PostgreSQL Testcontainers 提供测试数据准备与清理辅助方法。 */
public abstract class ProjectTestSupport extends PostgresSpringTestSupport {

  private static final AtomicLong FIXTURE_COUNTER = new AtomicLong();

  /**
   * 合法的最小 AgentModel runtime config：按最新 catalog 物化分支 settings 时必须通过严格解析， 缺失任一必需字段都会让 Issue Agent
   * Session 引导失败。
   */
  private static final String VALID_MODEL_CONFIG =
      "{\"limit\":{\"context\":128000,\"output\":8192},"
          + "\"abilities\":{\"tools\":true,\"reasoning\":true,\"inputModalities\":[\"TEXT\"]},"
          + "\"variants\":[{\"id\":\"default\"}],"
          + "\"defaultVariant\":\"default\","
          + "\"pricing\":{\"currency\":\"USD\",\"pricingTier\":\"standard\","
          + "\"serviceTier\":\"standard\",\"serviceTierMultiplier\":1.0,"
          + "\"version\":\"2026-01-01\",\"inputPerMillionTokens\":0,"
          + "\"outputPerMillionTokens\":0,\"cacheReadPerMillionTokens\":0,"
          + "\"cacheWritePerMillionTokens\":0,\"cacheWriteLongPerMillionTokens\":0,"
          + "\"reasoningPerMillionTokens\":0}}";

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
            + "values (?, ?, ?, ?::jsonb)",
        providerName,
        modelName,
        "wire-" + id,
        VALID_MODEL_CONFIG);

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

  /** 插入一个合法的 harness_thread 最小行（需先插入 harness_entry ROOT）。 */
  protected UUID createHarnessThread(UUID sessionId) {
    UUID rootEntryId = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, null, 'ROOT', '{}'::jsonb, current_timestamp)",
        rootEntryId,
        sessionId);
    UUID threadId = UUID.randomUUID();
    String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    jdbcTemplate.update(
        "insert into harness_thread (id, session_id, head_entry_id, creation_request_hash, name,"
            + " yolo_enabled, next_command_sequence, version, created_at, updated_at) values (?, ?,"
            + " ?, ?, 'thread-1', true, 1, 0, current_timestamp, current_timestamp)",
        threadId,
        sessionId,
        rootEntryId,
        hash);
    return threadId;
  }

  /** 插入一个合法的 session_owner 绑定到 issue_agent_session_id。 */
  protected void createSessionOwnerForIssueAgentSession(UUID sessionId, UUID issueAgentSessionId) {
    jdbcTemplate.update(
        "insert into session_owner (session_id, chat_id, canvas_id, issue_agent_session_id,"
            + " created_at) values (?, null, null, ?, current_timestamp)",
        sessionId,
        issueAgentSessionId);
  }

  protected static UUID randomId() {
    return UUID.randomUUID();
  }
}
