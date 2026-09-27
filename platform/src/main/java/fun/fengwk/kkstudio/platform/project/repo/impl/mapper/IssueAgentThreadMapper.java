package fun.fengwk.kkstudio.platform.project.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueAgentThreadDO;

import java.util.List;
import java.util.UUID;

/** {@code project_issue_agent_thread} 稳定绑定的 SQL 入口。 */
@Mapper
public interface IssueAgentThreadMapper extends BaseMapper {

  String COLUMNS = "issue_id, agent_name, thread_id";

  @Insert(
      """
      insert into project_issue_agent_thread (issue_id, agent_name, thread_id)
      values (#{issueId}, #{agentName}, #{threadId})
      """)
  int insert(
      @Param("issueId") UUID issueId,
      @Param("agentName") String agentName,
      @Param("threadId") UUID threadId);

  @Results(
      id = "issueAgentThreadMap",
      value = {
        @Result(column = "issue_id", property = "issueId"),
        @Result(column = "agent_name", property = "agentName"),
        @Result(column = "thread_id", property = "threadId")
      })
  @Select(
      "select "
          + COLUMNS
          + " from project_issue_agent_thread"
          + " where issue_id = #{issueId} and agent_name = #{agentName}")
  IssueAgentThreadDO findByIssueIdAndAgentName(
      @Param("issueId") UUID issueId, @Param("agentName") String agentName);

  @Select("select " + COLUMNS + " from project_issue_agent_thread where thread_id = #{threadId}")
  @ResultMap("issueAgentThreadMap")
  IssueAgentThreadDO findByThreadId(@Param("threadId") UUID threadId);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue_agent_thread where issue_id = #{issueId}"
          + " order by agent_name asc")
  @ResultMap("issueAgentThreadMap")
  List<IssueAgentThreadDO> listByIssueId(@Param("issueId") UUID issueId);

  @Delete(
      "delete from project_issue_agent_thread"
          + " where issue_id = #{issueId} and agent_name = #{agentName}")
  int deleteByIssueIdAndAgentName(
      @Param("issueId") UUID issueId, @Param("agentName") String agentName);

  /**
   * 该 Thread 自身或其 Harness Session 内的任一 Thread 是否已被 Issue+Agent 绑定。
   *
   * <p>Session 归属是只读事实：兄弟分支没有自己的绑定行，只能由同一 Session 的已绑定 Thread 判定。
   */
  @Select(
      """
      select exists(
          select 1
          from project_issue_agent_thread binding
          join harness_thread thread on thread.id = binding.thread_id
          where binding.thread_id = #{threadId}
             or thread.session_id = (select session_id from harness_thread where id = #{threadId})
      )
      """)
  boolean isIssueAgentBranch(@Param("threadId") UUID threadId);
}
