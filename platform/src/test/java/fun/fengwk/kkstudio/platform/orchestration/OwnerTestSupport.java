package fun.fengwk.kkstudio.platform.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.platform.chat.repo.impl.mapper.ChatMapper;
import fun.fengwk.kkstudio.platform.chat.repo.impl.model.ChatDO;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chat / Issue+Agent owner 归属集成测试的共享装配与 fixture 构建。
 *
 * <p>owner 行、product 层级（project → issue → agent_definition）与 harness_session / harness_thread /
 * harness_entry 事实直接经 mapper 或 JDBC 构造（跳过业务服务校验分支），使测试聚焦于归属关系自身的 FK/PK/UK 契约。每个测试前由 {@link
 * PostgresSpringTestSupport} 重置并重新迁移 schema。
 */
public abstract class OwnerTestSupport extends PostgresSpringTestSupport {

  /** harness_thread.creation_request_hash 的严格形状：64 位小写 SHA-256 十六进制。 */
  private static final String CREATION_REQUEST_HASH = "a".repeat(64);

  private static final AtomicLong ISSUE_NUMBER = new AtomicLong();

  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected ChatMapper chatMapper;

  /** 建一个 Chat owner 行并返回 id。 */
  protected UUID chatOwner() {
    UUID id = uuid();
    ChatDO chat = new ChatDO();
    chat.setId(id);
    chat.setTitle("owner-" + id);
    chat.setAgentName("schema-agent");
    chat.setVersion(0L);
    assertEquals(1, chatMapper.insert(chat));
    return id;
  }

  /** 建一个最小合法 AgentDefinition（含 provider/model）并返回 Agent 自然名称。 */
  protected String agentDefinition() {
    String suffix = uuid().toString().substring(0, 8);
    String providerName = "prov-" + suffix;
    String modelName = "model-" + suffix;
    String agentName = "agent-" + suffix;
    jdbc.update(
        "insert into agent_provider (name, provider_type, config, connection_generation_id)"
            + " values (?, 'openai', '{}'::jsonb, ?::uuid)",
        providerName,
        uuid());
    jdbc.update(
        "insert into agent_model (provider_name, name, model_id, config)"
            + " values (?, ?, ?, '{}'::jsonb)",
        providerName,
        modelName,
        "wire-" + suffix);
    jdbc.update(
        "insert into agent_definition (name, model_provider_name, model_name, config)"
            + " values (?, ?, ?, '{}'::jsonb)",
        agentName,
        providerName,
        modelName);
    return agentName;
  }

  /** 建一个最小合法 Project 行并返回 id。 */
  protected UUID projectRow() {
    UUID id = uuid();
    jdbc.update(
        "insert into project (id, title, description, workflow)"
            + " values (?, ?, '', '{\"states\":[]}'::jsonb)",
        id,
        "project-" + id);
    return id;
  }

  /** 在指定 Project 下建一个最小合法 Issue 行（state=INIT）并返回 id。 */
  protected UUID issueRow(UUID projectId) {
    UUID id = uuid();
    jdbc.update(
        "insert into project_issue (id, project_id, number, title, state)"
            + " values (?, ?, ?, ?, 'INIT')",
        id,
        projectId,
        ISSUE_NUMBER.incrementAndGet(),
        "issue-" + id);
    return id;
  }

  /** 插入一个 harness_session 行（最小合法形态）。 */
  protected void sessionRow(UUID sessionId) {
    jdbc.update(
        "insert into harness_session (id, name, created_at) values (?, ?, current_timestamp)",
        sessionId,
        "test-session");
  }

  /** 插入一个带 ROOT Entry 的 harness_thread 行并返回 threadId。 */
  protected UUID threadRow(UUID sessionId) {
    UUID rootEntryId = uuid();
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
            + " created_at) values (?, ?, null, 'ROOT', '{}'::jsonb, current_timestamp)",
        rootEntryId,
        sessionId);
    UUID threadId = uuid();
    jdbc.update(
        "insert into harness_thread (id, session_id, head_entry_id, creation_request_hash, name,"
            + " yolo_enabled, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, ?, ?, 'thread', true, 1, 0, current_timestamp, current_timestamp)",
        threadId,
        sessionId,
        rootEntryId,
        CREATION_REQUEST_HASH);
    return threadId;
  }

  /** 插入稳定绑定 {@code (issueId, agentName) -> threadId}。 */
  protected void bindIssueAgentThread(UUID issueId, String agentName, UUID threadId) {
    assertEquals(
        1,
        jdbc.update(
            "insert into project_issue_agent_thread (issue_id, agent_name, thread_id)"
                + " values (?, ?, ?)",
            issueId,
            agentName,
            threadId));
  }

  protected long count(String sql, Object... args) {
    Long value = jdbc.queryForObject(sql, Long.class, args);
    return value == null ? 0L : value;
  }

  protected static UUID uuid() {
    return UUID.randomUUID();
  }
}
