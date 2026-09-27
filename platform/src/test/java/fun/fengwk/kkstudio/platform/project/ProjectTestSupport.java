package fun.fengwk.kkstudio.platform.project;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.service.IssueRunService;
import fun.fengwk.kkstudio.project.service.IssueService;
import fun.fengwk.kkstudio.project.service.ProjectService;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 目标 Project/Issue/Run 持久化与用例的集成测试基座（真实 PostgreSQL Testcontainers）。
 *
 * <p>直接经 mapper/JDBC 构造 catalog fixture（agent
 * provider/model/definition），业务事实一律经目标服务写入，使断言聚焦目标表行与契约； 每个测试前由 {@link PostgresSpringTestSupport}
 * 重置并重新迁移 schema。
 *
 * <p>platform 不是 Harness 组合根：生产 {@link HarnessRuntime} 与 {@code HarnessStore} 由 web 组合根创建，platform
 * 测试上下文没有它们。 本基座因此装配一个 {@link HarnessRuntimeTestConfiguration 受控假件}，它用调用方给出的 Session/Thread
 * 身份在当前事务内真实写入 {@code harness_session / harness_entry / harness_thread} 行，从而让 {@code
 * project_issue_agent_thread}、 {@code project_issue_run}
 * 的即时外键与「失败整体回滚」真正被验证；它不模拟模型执行，回放给调用方的是当次接受的最小投影。
 */
@Import(ProjectTestSupport.HarnessRuntimeTestConfiguration.class)
public abstract class ProjectTestSupport extends PostgresSpringTestSupport {

  private static final AtomicLong FIXTURE_COUNTER = new AtomicLong();

  /** 合法的最小 AgentModel runtime config：物化分支 settings 时必须通过严格解析。 */
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

  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected ProjectService projectService;
  @Autowired protected IssueService issueService;
  @Autowired protected IssueRunService issueRunService;

  /** 插入一个最小合法的 AgentDefinition（含 provider/model）并返回 Agent 自然名称。 */
  protected String createAgent() {
    long id = FIXTURE_COUNTER.incrementAndGet();
    String providerName = "prov-" + id;
    String modelName = "mod-" + id;
    String agentName = "agent-" + id;
    jdbc.update(
        "insert into agent_provider (name, provider_type, config, connection_generation_id)"
            + " values (?, 'openai', '{}'::jsonb, ?::uuid)",
        providerName,
        UUID.randomUUID());
    jdbc.update(
        "insert into agent_model (provider_name, name, model_id, config)"
            + " values (?, ?, ?, ?::jsonb)",
        providerName,
        modelName,
        "wire-" + id,
        VALID_MODEL_CONFIG);
    jdbc.update(
        "insert into agent_definition (name, model_provider_name, model_name, config)"
            + " values (?, ?, ?, '{}'::jsonb)",
        agentName,
        providerName,
        modelName);
    return agentName;
  }

  /** 建一个默认工作流的项目并返回 id。 */
  protected UUID createProject() {
    Project project = projectService.createProject("项目-" + UUID.randomUUID(), "描述", true);
    return project.getId();
  }

  /**
   * 建一个带指定 Agent 工作阶段的严格 workflow 配置的项目：INIT → DESIGN → REVIEW → DONE。
   *
   * <p>DESIGN/REVIEW 都是启用且有 Agent 的工作阶段；DESIGN 的 maxRuns 由参数决定，REVIEW 固定为 1。
   */
  protected UUID createProjectWithStages(
      String projectTitle, String designAgent, String reviewAgent, int designMaxRuns) {
    Project project = projectService.createProject(projectTitle, "描述", true);
    projectService.updateWorkflow(
        project.getId(),
        project.getVersion(),
        workflowJson(designAgent, reviewAgent, designMaxRuns));
    return project.getId();
  }

  /** 目标严格 workflow JSON：INIT → DESIGN(agent) → REVIEW(agent) → DONE。 */
  protected String workflowJson(String designAgent, String reviewAgent, int designMaxRuns) {
    return "{"
        + "\"states\":["
        + "{\"state\":\"INIT\",\"name\":\"待开始\",\"next\":[\"DESIGN\"]},"
        + "{\"state\":\"DESIGN\",\"name\":\"设计\",\"agent\":\""
        + designAgent
        + "\",\"instructions\":\"完成可交付方案\",\"maxRuns\":"
        + designMaxRuns
        + ",\"next\":[\"REVIEW\"]},"
        + "{\"state\":\"REVIEW\",\"name\":\"检查\",\"agent\":\""
        + reviewAgent
        + "\",\"instructions\":\"检查\",\"maxRuns\":1,\"next\":[\"DONE\"]},"
        + "{\"state\":\"BLOCKED\",\"name\":\"业务阻塞\"},"
        + "{\"state\":\"DONE\",\"name\":\"完成\"}"
        + "]}";
  }

  protected Issue createIssue(UUID projectId) {
    return issueService.createIssue(projectId, "Issue " + UUID.randomUUID(), "需求描述");
  }

  /** 当前 Thread head Entry：终态收尾必须冻结一个真实属于本 Session 的 Entry。 */
  protected UUID headEntry(UUID threadId) {
    return jdbc.queryForObject(
        "select head_entry_id from harness_thread where id = ?", UUID.class, threadId);
  }

  /**
   * 模拟 Agent 产出：向该 Thread 的历史路径追加一个 MESSAGE Entry 并推进 head，返回新 head Entry id。
   *
   * <p>收尾冻结的区间是 {@code (start_entry_id, end_entry_id]}，因此正常收尾必须真的产出过历史；本方法用真实 Harness 表行构造该事实。
   */
  protected UUID appendHistoryEntry(UUID threadId) {
    UUID sessionId =
        jdbc.queryForObject(
            "select session_id from harness_thread where id = ?", UUID.class, threadId);
    UUID parentEntryId = headEntry(threadId);
    UUID entryId = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
            + " created_at) values (?, ?, ?, 'MESSAGE', '{}'::jsonb, ?)",
        entryId,
        sessionId,
        parentEntryId,
        now);
    jdbc.update(
        "update harness_thread set head_entry_id = ?, updated_at = ? where id = ?",
        entryId,
        now,
        threadId);
    return entryId;
  }

  protected long count(String sql, Object... args) {
    Long value = jdbc.queryForObject(sql, Long.class, args);
    return value == null ? 0L : value;
  }

  protected static String key(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  /**
   * 受控 Harness 运行时假件：真实写 Harness 行，投影用最小 mock 表达。
   *
   * <p>只覆盖本切片消费的契约（NEW_SESSION / THREAD 接受与 Thread 快照），不承担模型/工具执行语义。
   */
  @TestConfiguration(proxyBeanMethods = false)
  public static class HarnessRuntimeTestConfiguration {

    /**
     * 受控 Harness 存储假件：只实现归属校验读取（Session / Thread 解析），事实直接来自真实 Harness 行。
     *
     * <p>写入由 {@link #harnessRuntime(JdbcTemplate)} 在同一事务内完成，因此本假件不承担持久化职责，只把 orchestrator
     * 的归属校验接到真表。
     */
    @Bean
    public HarnessStore harnessStore(JdbcTemplate jdbc) {
      HarnessStore store = mock(HarnessStore.class);
      HarnessStore.Transaction transaction = mock(HarnessStore.Transaction.class);
      when(transaction.findSession(any(UUID.class)))
          .thenAnswer(
              invocation -> {
                UUID sessionId = invocation.getArgument(0);
                return count(jdbc, "select count(*) from harness_session where id = ?", sessionId)
                        > 0
                    ? Optional.of(mock(Session.class))
                    : Optional.empty();
              });
      when(transaction.lockSessionForUpdate(any(UUID.class)))
          .thenAnswer(
              invocation -> {
                UUID sessionId = invocation.getArgument(0);
                return count(jdbc, "select count(*) from harness_session where id = ?", sessionId)
                        > 0
                    ? Optional.of(mock(Session.class))
                    : Optional.empty();
              });
      when(transaction.findThread(any(UUID.class)))
          .thenAnswer(
              invocation -> {
                UUID threadId = invocation.getArgument(0);
                Map<String, Object> row =
                    queryThread(
                        jdbc, "select session_id from harness_thread where id = ?", threadId);
                if (row == null) {
                  return Optional.empty();
                }
                return Optional.of(threadState(threadId, (UUID) row.get("session_id")));
              });
      when(transaction.listThreadsBySession(any(UUID.class)))
          .thenAnswer(
              invocation -> {
                UUID sessionId = invocation.getArgument(0);
                List<Map<String, Object>> rows =
                    jdbc.queryForList(
                        "select id from harness_thread where session_id = ?", sessionId);
                return rows.stream().map(r -> threadState((UUID) r.get("id"), sessionId)).toList();
              });
      when(transaction.lockThread(any(UUID.class)))
          .thenAnswer(
              invocation -> {
                UUID threadId = invocation.getArgument(0);
                Map<String, Object> row =
                    queryThread(
                        jdbc, "select session_id from harness_thread where id = ?", threadId);
                if (row == null) {
                  return Optional.empty();
                }
                return Optional.of(threadState(threadId, (UUID) row.get("session_id")));
              });
      when(transaction.deleteThreads(any()))
          .thenAnswer(
              invocation -> {
                List<UUID> threadIds = invocation.getArgument(0);
                int deleted = 0;
                for (UUID threadId : threadIds) {
                  deleted += jdbc.update("delete from harness_thread where id = ?", threadId);
                }
                return deleted;
              });
      when(transaction.deleteEntries(any(UUID.class)))
          .thenAnswer(
              invocation -> {
                UUID sessionId = invocation.getArgument(0);
                jdbc.update(
                    "update harness_thread set head_entry_id = null where session_id = ?",
                    sessionId);
                return jdbc.update("delete from harness_entry where session_id = ?", sessionId);
              });
      when(transaction.deleteSession(any(UUID.class)))
          .thenAnswer(
              invocation -> {
                UUID sessionId = invocation.getArgument(0);
                return jdbc.update("delete from harness_session where id = ?", sessionId) > 0;
              });
      doAnswer(
              invocation -> {
                Function<HarnessStore.Transaction, Object> callback = invocation.getArgument(0);
                return callback.apply(transaction);
              })
          .when(store)
          .transaction(ArgumentMatchers.<Function<HarnessStore.Transaction, Object>>any());
      return store;
    }

    @Bean
    public HarnessRuntime harnessRuntime(JdbcTemplate jdbc) {
      HarnessRuntime runtime = mock(HarnessRuntime.class);
      when(runtime.acceptCommands(any(AcceptCommandsCommand.class), any()))
          .thenAnswer(invocation -> accept(jdbc, invocation.getArgument(0)));
      when(runtime.getThreadSnapshot(any(UUID.class)))
          .thenAnswer(invocation -> snapshot(jdbc, invocation.getArgument(0)));
      return runtime;
    }

    private static AcceptedCommands accept(JdbcTemplate jdbc, AcceptCommandsCommand command) {
      AcceptCommandsTarget target = command.target();
      if (target instanceof AcceptCommandsTarget.NewSession newSession) {
        UUID sessionId = newSession.sessionId();
        UUID threadId = newSession.threadId();
        UUID rootEntryId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
            "insert into harness_session (id, name, created_at) values (?, ?, ?)",
            sessionId,
            "session-" + sessionId,
            now);
        jdbc.update(
            "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
                + " created_at) values (?, ?, null, 'ROOT', '{}'::jsonb, ?)",
            rootEntryId,
            sessionId,
            now);
        jdbc.update(
            "insert into harness_thread (id, session_id, head_entry_id, creation_request_hash,"
                + " name, yolo_enabled, next_command_sequence, version, created_at, updated_at)"
                + " values (?, ?, ?, ?, ?, ?, 1, 0, ?, ?)",
            threadId,
            sessionId,
            rootEntryId,
            "0".repeat(64),
            "thread-" + threadId,
            newSession.yoloEnabled(),
            now,
            now);
        return accepted(rootEntryId);
      }
      AcceptCommandsTarget.Thread thread = (AcceptCommandsTarget.Thread) target;
      return accepted(thread.expectedHeadEntryId());
    }

    private static AcceptedCommands accepted(UUID rootEntryId) {
      Entry rootEntry = mock(Entry.class);
      when(rootEntry.id()).thenReturn(rootEntryId);
      AcceptedCommands accepted = mock(AcceptedCommands.class);
      when(accepted.rootEntry()).thenReturn(rootEntry);
      return accepted;
    }

    private static ThreadSnapshot snapshot(JdbcTemplate jdbc, UUID threadId) {
      Map<String, Object> row =
          jdbc.queryForMap(
              "select session_id, head_entry_id, next_command_sequence from harness_thread"
                  + " where id = ?",
              threadId);
      UUID headEntryId = (UUID) row.get("head_entry_id");
      ThreadState thread = mock(ThreadState.class);
      when(thread.sessionId()).thenReturn((UUID) row.get("session_id"));
      when(thread.headEntryId()).thenReturn(headEntryId);
      when(thread.nextCommandSequence())
          .thenReturn(((Number) row.get("next_command_sequence")).longValue());
      EntryPath path = entryPath(jdbc, headEntryId);
      ThreadSnapshot snapshot = mock(ThreadSnapshot.class);
      when(snapshot.thread()).thenReturn(thread);
      when(snapshot.entryPath()).thenReturn(path);
      return snapshot;
    }

    /** 按真实父链构造 root-to-head 路径：Entry id 全部来自 harness_entry 行，不伪造历史顺序。 */
    private static EntryPath entryPath(JdbcTemplate jdbc, UUID headEntryId) {
      List<UUID> ids = new ArrayList<>();
      UUID cursor = headEntryId;
      while (cursor != null) {
        ids.add(cursor);
        cursor =
            jdbc.queryForObject(
                "select parent_entry_id from harness_entry where id = ?", UUID.class, cursor);
      }
      Collections.reverse(ids);
      List<Entry> entries = new ArrayList<>(ids.size());
      for (UUID entryId : ids) {
        Entry entry = mock(Entry.class);
        when(entry.id()).thenReturn(entryId);
        entries.add(entry);
      }
      EntryPath path = mock(EntryPath.class);
      when(path.entries()).thenReturn(List.copyOf(entries));
      return path;
    }

    private static ThreadState threadState(UUID threadId, UUID sessionId) {
      ThreadState thread = mock(ThreadState.class);
      when(thread.id()).thenReturn(threadId);
      when(thread.sessionId()).thenReturn(sessionId);
      return thread;
    }

    private static Map<String, Object> queryThread(JdbcTemplate jdbc, String sql, UUID threadId) {
      try {
        return jdbc.queryForMap(sql, threadId);
      } catch (EmptyResultDataAccessException error) {
        return null;
      }
    }

    private static long count(JdbcTemplate jdbc, String sql, Object... args) {
      Long value = jdbc.queryForObject(sql, Long.class, args);
      return value == null ? 0L : value;
    }
  }
}
