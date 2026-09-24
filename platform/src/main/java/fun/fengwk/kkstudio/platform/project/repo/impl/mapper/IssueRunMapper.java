package fun.fengwk.kkstudio.platform.project.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueRunDO;

import java.util.List;
import java.util.UUID;

@Mapper
public interface IssueRunMapper extends BaseMapper {

  String COLUMNS =
      "id, issue_id, ordinal, role, agent_name, submission_run_id, "
          + "status, outcome, observed_activity_sequence, "
          + "continuation_count, max_continuations, deadline, waiting_reason, "
          + "result::text as result, terminal_action_id, version, created_at, updated_at, completed_at";

  @Insert(
      """
      insert into project_issue_run (
          id, issue_id, ordinal, role, agent_name, submission_run_id,
          status, outcome, observed_activity_sequence,
          continuation_count, max_continuations, deadline, waiting_reason,
          result, terminal_action_id, version, created_at, updated_at, completed_at
      ) values (
          #{id}, #{issueId}, #{ordinal}, #{role}, #{agentName}, #{submissionRunId},
          #{status}, #{outcome}, #{observedActivitySequence},
          #{continuationCount}, #{maxContinuations}, #{deadline}, #{waitingReason},
          cast(#{result, jdbcType=VARCHAR} as jsonb),
          #{terminalActionId}, 0, clock_timestamp(), clock_timestamp(), #{completedAt}
      )
      """)
  int insert(IssueRunDO run);

  @Select("select " + COLUMNS + " from project_issue_run where id = #{id}")
  @Results(
      id = "issueRunResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "issue_id", property = "issueId"),
        @Result(column = "ordinal", property = "ordinal"),
        @Result(column = "role", property = "role"),
        @Result(column = "agent_name", property = "agentName"),
        @Result(column = "submission_run_id", property = "submissionRunId"),
        @Result(column = "status", property = "status"),
        @Result(column = "outcome", property = "outcome"),
        @Result(column = "observed_activity_sequence", property = "observedActivitySequence"),
        @Result(column = "continuation_count", property = "continuationCount"),
        @Result(column = "max_continuations", property = "maxContinuations"),
        @Result(column = "deadline", property = "deadline"),
        @Result(column = "waiting_reason", property = "waitingReason"),
        @Result(column = "result", property = "result"),
        @Result(column = "terminal_action_id", property = "terminalActionId"),
        @Result(column = "version", property = "version"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt"),
        @Result(column = "completed_at", property = "completedAt")
      })
  IssueRunDO getById(@Param("id") UUID id);

  @Select("select " + COLUMNS + " from project_issue_run where id = #{id} for update")
  @ResultMap("issueRunResultMap")
  IssueRunDO lockById(@Param("id") UUID id);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue_run where issue_id = #{issueId} and status in ('RUNNING', 'WAITING_HUMAN')")
  @ResultMap("issueRunResultMap")
  IssueRunDO findActiveByIssueId(@Param("issueId") UUID issueId);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue_run where issue_id = #{issueId} and status in ('RUNNING', 'WAITING_HUMAN') for update")
  @ResultMap("issueRunResultMap")
  IssueRunDO lockActiveByIssueId(@Param("issueId") UUID issueId);

  @Select(
      """
      select exists(
          select 1
          from project_issue_run r
          join project_issue i on i.id = r.issue_id
          where i.project_id = #{projectId}
            and r.status in ('RUNNING', 'WAITING_HUMAN'))
      """)
  boolean hasActiveByProjectId(@Param("projectId") UUID projectId);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue_run where terminal_action_id = #{terminalActionId}")
  @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
  @ResultMap("issueRunResultMap")
  IssueRunDO findByTerminalActionId(@Param("terminalActionId") String terminalActionId);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue_run where issue_id = #{issueId} order by ordinal desc limit 1")
  @ResultMap("issueRunResultMap")
  IssueRunDO findLatestByIssueId(@Param("issueId") UUID issueId);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue_run where issue_id = #{issueId} order by ordinal asc")
  @ResultMap("issueRunResultMap")
  List<IssueRunDO> listByIssueId(@Param("issueId") UUID issueId);

  @Select("select coalesce(max(ordinal), 0) + 1 from project_issue_run where issue_id = #{issueId}")
  Long allocateNextOrdinal(@Param("issueId") UUID issueId);

  @Update(
      """
      update project_issue_run
      set status = #{run.status},
          outcome = #{run.outcome},
          observed_activity_sequence = #{run.observedActivitySequence},
          continuation_count = #{run.continuationCount},
          waiting_reason = #{run.waitingReason},
          result = cast(#{run.result, jdbcType=VARCHAR} as jsonb),
          terminal_action_id = #{run.terminalActionId},
          completed_at = #{run.completedAt},
          updated_at = clock_timestamp(),
          version = version + 1
      where id = #{run.id} and version = #{expectedVersion}
      """)
  int updateById(@Param("run") IssueRunDO run, @Param("expectedVersion") long expectedVersion);

  @Delete("delete from project_issue_run where id = #{id} and version = #{expectedVersion}")
  int deleteById(@Param("id") UUID id, @Param("expectedVersion") long expectedVersion);
}
