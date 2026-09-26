package fun.fengwk.kkstudio.platform.project.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueAgentThreadDO;

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

  @Delete(
      "delete from project_issue_agent_thread"
          + " where issue_id = #{issueId} and agent_name = #{agentName}")
  int deleteByIssueIdAndAgentName(
      @Param("issueId") UUID issueId, @Param("agentName") String agentName);
}
