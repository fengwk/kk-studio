package fun.fengwk.kkstudio.platform.project.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueAgentSessionDO;

import java.util.UUID;

@Mapper
public interface IssueAgentSessionMapper extends BaseMapper {

  String COLUMNS = "id, issue_id, agent_name, session_id, thread_id, created_at, updated_at";

  @Select(
      """
      insert into project_issue_agent_session (
          id, issue_id, agent_name, session_id, thread_id, created_at, updated_at
      ) values (
          #{id}, #{issueId}, #{agentName}, #{sessionId}, #{threadId},
          clock_timestamp(), clock_timestamp()
      )
      returning id, issue_id, agent_name, session_id, thread_id, created_at, updated_at
      """)
  @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
  @Results(
      id = "issueAgentSessionResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "issue_id", property = "issueId"),
        @Result(column = "agent_name", property = "agentName"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "thread_id", property = "threadId"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
      })
  IssueAgentSessionDO insert(IssueAgentSessionDO session);

  @Select("select " + COLUMNS + " from project_issue_agent_session where id = #{id}")
  @ResultMap("issueAgentSessionResultMap")
  IssueAgentSessionDO getById(@Param("id") UUID id);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue_agent_session where issue_id = #{issueId} and agent_name = #{agentName}")
  @ResultMap("issueAgentSessionResultMap")
  IssueAgentSessionDO findByIssueIdAndAgentName(
      @Param("issueId") UUID issueId, @Param("agentName") String agentName);

  @Select("select " + COLUMNS + " from project_issue_agent_session where session_id = #{sessionId}")
  @ResultMap("issueAgentSessionResultMap")
  IssueAgentSessionDO findBySessionId(@Param("sessionId") UUID sessionId);

  @Select("select " + COLUMNS + " from project_issue_agent_session where thread_id = #{threadId}")
  @ResultMap("issueAgentSessionResultMap")
  IssueAgentSessionDO findByThreadId(@Param("threadId") UUID threadId);

  @Delete("delete from project_issue_agent_session where id = #{id}")
  int deleteById(@Param("id") UUID id);

  @Delete("delete from project_issue_agent_session where issue_id = #{issueId}")
  int deleteByIssueId(@Param("issueId") UUID issueId);
}
