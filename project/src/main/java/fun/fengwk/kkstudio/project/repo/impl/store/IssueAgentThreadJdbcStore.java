package fun.fengwk.kkstudio.project.repo.impl.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.project.repo.impl.model.IssueAgentThreadDO;

import java.util.List;
import java.util.UUID;

/** {@code project_issue_agent_thread} 稳定绑定的 Spring JDBC 存取：显式 SQL 与 {@link RowMapper}。 */
@Component
public class IssueAgentThreadJdbcStore {

  private static final String COLUMNS = "issue_id, agent_name, thread_id";

  private static final RowMapper<IssueAgentThreadDO> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        IssueAgentThreadDO row = new IssueAgentThreadDO();
        row.setIssueId(ProjectRows.uuid(resultSet, "issue_id"));
        row.setAgentName(resultSet.getString("agent_name"));
        row.setThreadId(ProjectRows.uuid(resultSet, "thread_id"));
        return row;
      };

  private final JdbcClient jdbc;

  public IssueAgentThreadJdbcStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public int insert(UUID issueId, String agentName, UUID threadId) {
    return jdbc.sql(
            "insert into project_issue_agent_thread (issue_id, agent_name, thread_id)"
                + " values (:issueId, :agentName, :threadId)")
        .param("issueId", issueId)
        .param("agentName", agentName)
        .param("threadId", threadId)
        .update();
  }

  public IssueAgentThreadDO findByIssueIdAndAgentName(UUID issueId, String agentName) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from project_issue_agent_thread"
                + " where issue_id = :issueId and agent_name = :agentName")
        .param("issueId", issueId)
        .param("agentName", agentName)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public IssueAgentThreadDO findByThreadId(UUID threadId) {
    return jdbc.sql(
            "select " + COLUMNS + " from project_issue_agent_thread where thread_id = :threadId")
        .param("threadId", threadId)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public List<IssueAgentThreadDO> listByIssueId(UUID issueId) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from project_issue_agent_thread where issue_id = :issueId"
                + " order by agent_name asc")
        .param("issueId", issueId)
        .query(ROW_MAPPER)
        .list();
  }

  public int deleteByIssueIdAndAgentName(UUID issueId, String agentName) {
    return jdbc.sql(
            "delete from project_issue_agent_thread"
                + " where issue_id = :issueId and agent_name = :agentName")
        .param("issueId", issueId)
        .param("agentName", agentName)
        .update();
  }

  /**
   * 该 Thread 自身或其 Harness Session 内的任一 Thread 是否已被 Issue+Agent 绑定。
   *
   * <p>Session 归属是只读事实：兄弟分支没有自己的绑定行，只能由同一 Session 的已绑定 Thread 判定。
   */
  public boolean isIssueAgentBranch(UUID threadId) {
    return jdbc.sql(
            "select exists("
                + " select 1"
                + " from project_issue_agent_thread binding"
                + " join harness_thread thread on thread.id = binding.thread_id"
                + " where binding.thread_id = :threadId"
                + " or thread.session_id ="
                + " (select session_id from harness_thread where id = :threadId))")
        .param("threadId", threadId)
        .query((resultSet, rowNumber) -> resultSet.getBoolean(1))
        .single();
  }
}
