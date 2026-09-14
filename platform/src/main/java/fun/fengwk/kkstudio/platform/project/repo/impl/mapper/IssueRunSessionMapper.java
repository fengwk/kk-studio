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

import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueRunSessionDO;

import java.util.UUID;

@Mapper
public interface IssueRunSessionMapper extends BaseMapper {

  @Insert(
      """
      insert into session_owner (issue_run_id, session_id, created_at)
      values (#{runId}, #{sessionId}, clock_timestamp())
      """)
  int insert(@Param("runId") UUID runId, @Param("sessionId") UUID sessionId);

  @Select(
      "select issue_run_id as run_id, session_id, created_at"
          + " from session_owner where issue_run_id = #{runId}")
  @Results(
      id = "issueRunSessionResultMap",
      value = {
        @Result(column = "run_id", property = "runId"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "created_at", property = "createdAt")
      })
  IssueRunSessionDO findByRunId(@Param("runId") UUID runId);

  @Select(
      "select issue_run_id as run_id, session_id, created_at from session_owner"
          + " where session_id = #{sessionId} and issue_run_id is not null")
  @ResultMap("issueRunSessionResultMap")
  IssueRunSessionDO findBySessionId(@Param("sessionId") UUID sessionId);

  @Delete("delete from session_owner where issue_run_id = #{runId}")
  int deleteByRunId(@Param("runId") UUID runId);
}
