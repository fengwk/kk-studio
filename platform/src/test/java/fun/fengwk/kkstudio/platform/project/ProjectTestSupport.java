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
import org.springframework.context.annotation.Primary;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinOutcome;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinRequest;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetContributorStateCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.project.service.IssueRunService;
import fun.fengwk.kkstudio.project.service.IssueService;
import fun.fengwk.kkstudio.project.service.ProjectService;
import fun.fengwk.kkstudio.project.turn.ProjectRunScope;
import fun.fengwk.kkstudio.project.turn.ProjectRunScopeJsonCodec;

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

  /**
   * 用于把并发重放用例的第二个请求精确停在锁前 receipt 检查，构造确定性的「旧快照 → 并发提交 → receipt 命中」交错。
   *
   * <p>声明在共享基座而不是单个用例类：平台测试的 Spring context 连同其连接池按上下文缓存，只有让本包全部用例继续共用同一上下文，才不会额外占用共享 PostgreSQL
   * 容器的连接额度。
   */
  @MockitoSpyBean protected IssueActivityRepository issueActivityRepository;

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

  /**
   * 读取本 Thread 上最新一次冻结的 {@code project/run} contributor state JSON：SET_CONTRIBUTOR_STATE 命令的
   * durable payload 就是接受该 Run（或闭合 scope）时写下的业务身份，测试据此以真实来源驱动业务工具，而不是自己重算 scope。
   */
  protected String frozenRunScopeJson(UUID threadId) {
    Map<String, Object> row =
        jdbc.queryForMap(
            "select command_type, payload::text as payload from harness_thread_command"
                + " where thread_id = ? and command_type = 'SET_CONTRIBUTOR_STATE'"
                + " order by sequence desc limit 1",
            threadId);
    ThreadCommandPayload payload =
        new ThreadCommandPayloadJsonCodec()
            .decode(
                ThreadCommandType.valueOf((String) row.get("command_type")),
                (String) row.get("payload"));
    return ((SetContributorStateCommandPayload) payload).state().dataJson();
  }

  /** 该 Thread 上最新一次冻结的 Run scope：Run 进入终态后这里读到的是 {@code active=false} 的关闭副本。 */
  protected ProjectRunScope latestRunScope(UUID threadId) {
    return new ProjectRunScopeJsonCodec().decode(frozenRunScopeJson(threadId));
  }

  /** 读取本 Thread 上指定类型命令的 durable payload，用于断言显式 Run 配置与 scope 闭合写入的实际内容。 */
  protected String commandPayload(UUID threadId, String commandType) {
    return jdbc.queryForObject(
        "select payload::text from harness_thread_command where thread_id = ? and command_type = ?",
        String.class,
        threadId,
        commandType);
  }

  /** 本 Thread 上 Run 接受的 SET_ENVIRONMENT 取值；null 表示显式清空，用于断言环境只来自阶段配置。 */
  protected String runEnvironmentName(UUID threadId) {
    ThreadCommandPayload payload =
        new ThreadCommandPayloadJsonCodec()
            .decode(
                ThreadCommandType.SET_ENVIRONMENT,
                commandPayload(threadId, ThreadCommandType.SET_ENVIRONMENT.name()));
    return ((SetEnvironmentCommandPayload) payload).environmentName();
  }

  /** 本 Thread 上最新一次 scope 冻结命令的序号：用于断言关闭旧 scope 后新 Run 的 active=true 严格在其之后。 */
  protected long latestRunScopeSequence(UUID threadId) {
    Long value =
        jdbc.queryForObject(
            "select max(sequence) from harness_thread_command where thread_id = ? and command_type ="
                + " 'SET_CONTRIBUTOR_STATE'",
            Long.class,
            threadId);
    return value == null ? 0L : value;
  }

  /** 冻结本次 Run 的 root Join 结果：terminal 与 final 指向同一 Entry，模拟 Harness 执行终止后写回的固定 join 凭据。 */
  protected void matchRunJoin(UUID runId, UUID terminalEntryId) {
    matchRunJoin(runId, terminalEntryId, terminalEntryId);
  }

  /** 冻结本次 Run 的 root Join 结果，允许 final 回答入口与 terminal 不同（如执行失败时无最终回答）。 */
  protected void matchRunJoin(UUID runId, UUID terminalEntryId, UUID finalAnswerEntryId) {
    int updated =
        jdbc.update(
            "update harness_thread_join set terminal_entry_id = ?, final_answer_entry_id = ?,"
                + " updated_at = ? where invocation_id = ?",
            terminalEntryId,
            finalAnswerEntryId,
            Timestamp.from(Instant.now()),
            runId);
    if (updated != 1) {
      throw new IllegalStateException("no harness_thread_join row for run " + runId);
    }
  }

  /**
   * 向该 Thread 追加一个终态 {@code TURN_END} Entry 并推进 head，返回新 head Entry id。
   *
   * <p>Join 冻结的 terminal 必须是真实存在的终态边界；本方法用真实 Harness 行构造该事实，供收尾用例区分 COMPLETED/ERROR/CANCELLED。
   */
  protected UUID appendTurnEndEntry(UUID threadId, TurnEndOutcome outcome) {
    UUID sessionId =
        jdbc.queryForObject(
            "select session_id from harness_thread where id = ?", UUID.class, threadId);
    UUID parentEntryId = headEntry(threadId);
    UUID entryId = UUID.randomUUID();
    TurnEndReason reason =
        switch (outcome) {
          case COMPLETED -> null;
          case FAILED -> TurnEndReason.TURN_FAILED;
          case STOPPED -> TurnEndReason.USER_STOP;
          case CANCELLED -> TurnEndReason.CANCELLED;
        };
    UUID closeRequestId = outcome == TurnEndOutcome.STOPPED ? UUID.randomUUID() : null;
    TurnEndPayload payload =
        new TurnEndPayload(parentEntryId, outcome, false, reason, closeRequestId);
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
            + " created_at) values (?, ?, ?, 'TURN_END', ?::jsonb, ?)",
        entryId,
        sessionId,
        parentEntryId,
        HarnessRuntimeTestConfiguration.ENTRY_CODEC.encode(payload),
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

    private static final ThreadCommandPayloadJsonCodec COMMAND_CODEC =
        new ThreadCommandPayloadJsonCodec();

    private static final HistoryEntryPayloadJsonCodec ENTRY_CODEC =
        new HistoryEntryPayloadJsonCodec();

    /**
     * 受控 Harness 存储假件：只实现归属校验读取（Session / Thread 解析），事实直接来自真实 Harness 行。
     *
     * <p>写入由 {@link #harnessRuntime(JdbcTemplate)} 在同一事务内完成，因此本假件不承担持久化职责，只把 orchestrator
     * 的归属校验接到真表。
     */
    @Bean
    @Primary
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
      when(transaction.deleteJoinsForThreads(any()))
          .thenAnswer(
              invocation -> {
                List<UUID> threadIds = invocation.getArgument(0);
                int deleted = 0;
                for (UUID threadId : threadIds) {
                  deleted +=
                      jdbc.update(
                          "delete from harness_thread_join where child_thread_id = ?", threadId);
                }
                return deleted;
              });
      when(transaction.deleteThreads(any()))
          .thenAnswer(
              invocation -> {
                List<UUID> threadIds = invocation.getArgument(0);
                int deleted = 0;
                for (UUID threadId : threadIds) {
                  jdbc.update("delete from harness_thread_command where thread_id = ?", threadId);
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
      when(runtime.acceptCommandsAndJoin(
              any(AcceptCommandsCommand.class), any(ThreadJoinRequest.class), any()))
          .thenAnswer(
              invocation ->
                  acceptAndJoin(jdbc, invocation.getArgument(0), invocation.getArgument(1)));
      when(runtime.findJoin(any(UUID.class)))
          .thenAnswer(invocation -> findJoin(jdbc, invocation.getArgument(0)));
      when(runtime.findThreadCommand(any(UUID.class), any(UUID.class)))
          .thenAnswer(
              invocation ->
                  findThreadCommand(jdbc, invocation.getArgument(0), invocation.getArgument(1)));
      when(runtime.projectJoinReceipt(any(UUID.class)))
          .thenAnswer(invocation -> projectJoinReceipt(jdbc, invocation.getArgument(0)));
      when(runtime.getThreadSnapshot(any(UUID.class)))
          .thenAnswer(invocation -> snapshot(jdbc, invocation.getArgument(0)));
      when(runtime.stop(any(StopCommand.class)))
          .thenAnswer(invocation -> stop(jdbc, invocation.getArgument(0)));
      return runtime;
    }

    /** 原子接受并发起 join：先按普通接受写入 Session/Thread/Commands，再冻结 join 契约行（terminal 留空表示尚未匹配）。 */
    private static AcceptedCommands acceptAndJoin(
        JdbcTemplate jdbc, AcceptCommandsCommand command, ThreadJoinRequest join) {
      AcceptedCommands accepted = accept(jdbc, command);
      long sourceSequence =
          jdbc.queryForObject(
              "select max(sequence) from harness_thread_command where thread_id = ?",
              Long.class,
              accepted.thread().id());
      Timestamp now = Timestamp.from(Instant.now());
      jdbc.update(
          "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id,"
              + " child_thread_id, source_command_sequence, agent, max_turns, reminder_turn,"
              + " terminal_entry_id, final_answer_entry_id, delivery_command_sequence, created_at,"
              + " updated_at) values (?, ?, ?, ?, ?, ?, ?, 0, null, null, null, ?, ?)",
          join.invocationId(),
          join.requestHash(),
          join.parentThreadId(),
          accepted.thread().id(),
          sourceSequence,
          join.agent(),
          join.maxTurns(),
          now,
          now);
      return accepted;
    }

    /** 从真实 {@code harness_thread_join} 行读取固定 join 凭据；无行即未登记。 */
    private static Optional<ThreadJoin> findJoin(JdbcTemplate jdbc, UUID invocationId) {
      List<Map<String, Object>> rows =
          jdbc.queryForList(
              "select invocation_id, request_hash, parent_thread_id, child_thread_id,"
                  + " source_command_sequence, agent, max_turns, reminder_turn, terminal_entry_id,"
                  + " final_answer_entry_id, delivery_command_sequence, created_at, updated_at"
                  + " from harness_thread_join where invocation_id = ?",
              invocationId);
      if (rows.isEmpty()) {
        return Optional.empty();
      }
      Map<String, Object> row = rows.get(0);
      return Optional.of(
          new ThreadJoin(
              (UUID) row.get("invocation_id"),
              (String) row.get("request_hash"),
              (UUID) row.get("parent_thread_id"),
              (UUID) row.get("child_thread_id"),
              ((Number) row.get("source_command_sequence")).longValue(),
              (String) row.get("agent"),
              row.get("max_turns") == null ? null : ((Number) row.get("max_turns")).intValue(),
              ((Number) row.get("reminder_turn")).longValue(),
              (UUID) row.get("terminal_entry_id"),
              (UUID) row.get("final_answer_entry_id"),
              row.get("delivery_command_sequence") == null
                  ? null
                  : ((Number) row.get("delivery_command_sequence")).longValue(),
              ((Timestamp) row.get("created_at")).toInstant(),
              ((Timestamp) row.get("updated_at")).toInstant()));
    }

    /**
     * 从真实 Join 与终态 Entry 投影只读 receipt：终态 Entry 是 {@code TURN_END} 时按其 outcome 映射为
     * COMPLETED/ERROR/CANCELLED；测试用普通 MESSAGE 作为终态时按正常完成处理，便于专注业务收尾。
     */
    private static Optional<ThreadJoinReceipt> projectJoinReceipt(
        JdbcTemplate jdbc, UUID invocationId) {
      Optional<ThreadJoin> found = findJoin(jdbc, invocationId);
      if (found.isEmpty() || !found.get().matched()) {
        return Optional.empty();
      }
      ThreadJoin join = found.get();
      ThreadJoinOutcome outcome = ThreadJoinOutcome.COMPLETED;
      Map<String, Object> terminal =
          queryThread(
              jdbc,
              "select entry_type, payload::text as payload from harness_entry where id = ?",
              join.terminalEntryId());
      if (terminal != null && EntryType.TURN_END.name().equals(terminal.get("entry_type"))) {
        EntryPayload payload =
            ENTRY_CODEC.decode(EntryType.TURN_END, (String) terminal.get("payload"));
        outcome =
            switch (((TurnEndPayload) payload).outcome()) {
              case COMPLETED -> ThreadJoinOutcome.COMPLETED;
              case FAILED -> ThreadJoinOutcome.ERROR;
              case STOPPED, CANCELLED -> ThreadJoinOutcome.CANCELLED;
            };
      }
      return Optional.of(
          new ThreadJoinReceipt(
              join.invocationId(),
              join.childThreadId(),
              join.agent(),
              outcome,
              "",
              null,
              null,
              null));
    }

    /** 按 Thread + idempotencyKey 读取 durable command，用真实 payload codec 还原 typed aggregate。 */
    private static Optional<ThreadCommand> findThreadCommand(
        JdbcTemplate jdbc, UUID threadId, UUID idempotencyKey) {
      List<Map<String, Object>> rows =
          jdbc.queryForList(
              "select sequence, command_type, payload::text as payload, request_hash,"
                  + " applied_entry_id, stop_request_id, cancelled_at, created_at"
                  + " from harness_thread_command where thread_id = ? and idempotency_key = ?",
              threadId,
              idempotencyKey);
      if (rows.isEmpty()) {
        return Optional.empty();
      }
      Map<String, Object> row = rows.get(0);
      ThreadCommandPayload payload =
          COMMAND_CODEC.decode(
              ThreadCommandType.valueOf((String) row.get("command_type")),
              (String) row.get("payload"));
      return Optional.of(
          new ThreadCommand(
              threadId,
              ((Number) row.get("sequence")).longValue(),
              payload,
              idempotencyKey,
              (String) row.get("request_hash"),
              (UUID) row.get("applied_entry_id"),
              (UUID) row.get("stop_request_id"),
              row.get("cancelled_at") == null
                  ? null
                  : ((Timestamp) row.get("cancelled_at")).toInstant(),
              ((Timestamp) row.get("created_at")).toInstant()));
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
                + " name, yolo_enabled, execution_control, input_through_sequence,"
                + " next_command_sequence, version, created_at, updated_at)"
                + " values (?, ?, ?, ?, ?, ?, 'RUNNABLE', 0, ?, 0, ?, ?)",
            threadId,
            sessionId,
            rootEntryId,
            "0".repeat(64),
            "thread-" + threadId,
            newSession.yoloEnabled(),
            command.commands().size() + 1L,
            now,
            now);
        insertCommands(jdbc, threadId, 1, command.commands(), now);
        return accepted(threadId, rootEntryId);
      }
      AcceptCommandsTarget.Thread thread = (AcceptCommandsTarget.Thread) target;
      insertCommands(
          jdbc,
          thread.threadId(),
          thread.expectedNextCommandSequence(),
          command.commands(),
          Timestamp.from(Instant.now()));
      jdbc.update(
          "update harness_thread set next_command_sequence = next_command_sequence + ? where id = ?",
          command.commands().size(),
          thread.threadId());
      return accepted(thread.threadId(), thread.expectedHeadEntryId());
    }

    /** 记录本次接受的真实命令行，供并发幂等测试断言「只派发一次」。 */
    private static void insertCommands(
        JdbcTemplate jdbc,
        UUID threadId,
        long sequence,
        List<NewThreadCommand> commands,
        Timestamp now) {
      Long stored =
          jdbc.queryForObject(
              "select coalesce(max(sequence), 0) + 1 from harness_thread_command where thread_id = ?",
              Long.class,
              threadId);
      if (stored != null) {
        sequence = stored;
      }
      for (NewThreadCommand item : commands) {
        jdbc.update(
            "insert into harness_thread_command (thread_id, sequence, command_type, payload,"
                + " idempotency_key, request_hash, created_at) values (?, ?, ?, ?::jsonb, ?, ?, ?)",
            threadId,
            sequence,
            item.payload().type().name(),
            COMMAND_CODEC.encode(item.payload()),
            item.idempotencyKey(),
            item.requestHash(),
            now);
        sequence++;
      }
    }

    private static AcceptedCommands accepted(UUID threadId, UUID rootEntryId) {
      Entry rootEntry = mock(Entry.class);
      when(rootEntry.id()).thenReturn(rootEntryId);
      ThreadState thread = mock(ThreadState.class);
      when(thread.id()).thenReturn(threadId);
      AcceptedCommands accepted = mock(AcceptedCommands.class);
      when(accepted.rootEntry()).thenReturn(rootEntry);
      when(accepted.thread()).thenReturn(thread);
      return accepted;
    }

    private static ThreadSnapshot snapshot(JdbcTemplate jdbc, UUID threadId) {
      Map<String, Object> row =
          jdbc.queryForMap(
              "select session_id, head_entry_id, next_command_sequence, execution_control,"
                  + " input_through_sequence from harness_thread where id = ?",
              threadId);
      UUID headEntryId = (UUID) row.get("head_entry_id");
      ThreadState thread = mock(ThreadState.class);
      when(thread.sessionId()).thenReturn((UUID) row.get("session_id"));
      when(thread.headEntryId()).thenReturn(headEntryId);
      when(thread.nextCommandSequence())
          .thenReturn(((Number) row.get("next_command_sequence")).longValue());
      // 持久执行控制来自真实行：运行阶段判定读取的就是这一事实，测试不得伪造固定值。
      when(thread.executionControl())
          .thenReturn(ThreadExecutionControl.valueOf((String) row.get("execution_control")));
      when(thread.inputThroughSequence())
          .thenReturn(((Number) row.get("input_through_sequence")).longValue());
      EntryPath path = entryPath(jdbc, headEntryId);
      ThreadSnapshot snapshot = mock(ThreadSnapshot.class);
      when(snapshot.thread()).thenReturn(thread);
      when(snapshot.entryPath()).thenReturn(path);
      return snapshot;
    }

    /**
     * 模拟真实 Stop：在同一 Thread 上写入 STOPPED TURN_END 边界并推进 head，再以该新 head 投影权威 ThreadState。
     *
     * <p>调用方收尾必须引用返回 head（而非 Stop 之前的快照 head），否则会丢失本次停止已提交的区间末端。同一 stopRequestId 重放时复用既有边界。
     */
    private static StopResult stop(JdbcTemplate jdbc, StopCommand command) {
      UUID threadId = command.threadId();
      List<Map<String, Object>> existing =
          jdbc.queryForList(
              "select id from harness_entry where session_id = (select session_id from"
                  + " harness_thread where id = ?) and payload->>'closeRequestId' = ?",
              threadId,
              command.stopRequestId().toString());
      if (existing.isEmpty()) {
        UUID sessionId =
            jdbc.queryForObject(
                "select session_id from harness_thread where id = ?", UUID.class, threadId);
        UUID headEntryId =
            jdbc.queryForObject(
                "select head_entry_id from harness_thread where id = ?", UUID.class, threadId);
        UUID entryId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        TurnEndPayload payload =
            new TurnEndPayload(
                headEntryId,
                TurnEndOutcome.STOPPED,
                false,
                TurnEndReason.USER_STOP,
                command.stopRequestId());
        jdbc.update(
            "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
                + " created_at) values (?, ?, ?, 'TURN_END', ?::jsonb, ?)",
            entryId,
            sessionId,
            headEntryId,
            ENTRY_CODEC.encode(payload),
            now);
        jdbc.update(
            "update harness_thread set head_entry_id = ?, updated_at = ? where id = ?",
            entryId,
            now,
            threadId);
      }
      return new StopResult(false, snapshot(jdbc, threadId).thread(), List.of());
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
