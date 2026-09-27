package fun.fengwk.kkstudio.project.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import fun.fengwk.kkstudio.project.repo.impl.model.IssueRunDO;

import java.util.List;
import java.util.UUID;

/** {@code project_issue_run} 表 SQL 入口。 */
@Mapper
public interface IssueRunMapper extends BaseMapper {

  String COLUMNS =
      "id, issue_id, ordinal, state, session_id, thread_id, status, start_entry_id, end_entry_id,"
          + " final_answer_entry_id, next_state, observed_activity_sequence,"
          + " remaining_execution_ms, active_since, error, version, started_at, ended_at";

  @Insert(
      """
      insert into project_issue_run (
          id, issue_id, ordinal, state, session_id, thread_id, status, start_entry_id,
          end_entry_id, final_answer_entry_id, next_state, observed_activity_sequence,
          remaining_execution_ms, active_since, error, version, started_at, ended_at
      ) values (
          #{id}, #{issueId}, #{ordinal}, #{state}, #{sessionId}, #{threadId}, #{status},
          #{startEntryId}, #{endEntryId}, #{finalAnswerEntryId}, #{nextState},
          #{observedActivitySequence}, #{remainingExecutionMs}, #{activeSince}, #{error},
          0, clock_timestamp(), #{endedAt}
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
        @Result(column = "state", property = "state"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "thread_id", property = "threadId"),
        @Result(column = "status", property = "status"),
        @Result(column = "start_entry_id", property = "startEntryId"),
        @Result(column = "end_entry_id", property = "endEntryId"),
        @Result(column = "final_answer_entry_id", property = "finalAnswerEntryId"),
        @Result(column = "next_state", property = "nextState"),
        @Result(column = "observed_activity_sequence", property = "observedActivitySequence"),
        @Result(column = "remaining_execution_ms", property = "remainingExecutionMs"),
        @Result(column = "active_since", property = "activeSince"),
        @Result(column = "error", property = "error"),
        @Result(column = "version", property = "version"),
        @Result(column = "started_at", property = "startedAt"),
        @Result(column = "ended_at", property = "endedAt")
      })
  IssueRunDO getById(@Param("id") UUID id);

  @Select("select " + COLUMNS + " from project_issue_run where id = #{id} for update")
  @ResultMap("issueRunResultMap")
  IssueRunDO lockById(@Param("id") UUID id);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue_run where issue_id = #{issueId}"
          + " and status in ('RUNNING', 'WAITING')")
  @ResultMap("issueRunResultMap")
  IssueRunDO getActiveByIssueId(@Param("issueId") UUID issueId);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue_run where issue_id = #{issueId}"
          + " and status in ('RUNNING', 'WAITING') for update")
  @ResultMap("issueRunResultMap")
  IssueRunDO lockActiveByIssueId(@Param("issueId") UUID issueId);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue_run where issue_id = #{issueId}"
          + " order by ordinal desc limit 1")
  @ResultMap("issueRunResultMap")
  IssueRunDO getLatestByIssueId(@Param("issueId") UUID issueId);

  @Select(
      "select "
          + COLUMNS
          + " from project_issue_run where issue_id = #{issueId}"
          + " order by ordinal asc")
  @ResultMap("issueRunResultMap")
  List<IssueRunDO> listByIssueId(@Param("issueId") UUID issueId);

  @Select(
      "select count(*) from project_issue_run where issue_id = #{issueId}"
          + " and state = #{state} and ordinal > #{afterOrdinal}")
  long countByIssueIdAndStateAfterOrdinal(
      @Param("issueId") UUID issueId,
      @Param("state") String state,
      @Param("afterOrdinal") long afterOrdinal);

  @Update(
      """
      update project_issue_run
      set status = #{run.status},
          end_entry_id = #{run.endEntryId},
          final_answer_entry_id = #{run.finalAnswerEntryId},
          next_state = #{run.nextState},
          observed_activity_sequence = #{run.observedActivitySequence},
          remaining_execution_ms = #{run.remainingExecutionMs},
          active_since = #{run.activeSince},
          error = #{run.error},
          ended_at = #{run.endedAt},
          version = version + 1
      where id = #{run.id} and version = #{expectedVersion}
      """)
  int updateById(@Param("run") IssueRunDO run, @Param("expectedVersion") long expectedVersion);

  @Delete("delete from project_issue_run where id = #{id} and version = #{expectedVersion}")
  int deleteById(@Param("id") UUID id, @Param("expectedVersion") long expectedVersion);

  @Delete("delete from project_issue_run where issue_id = #{issueId}")
  int deleteByIssueId(@Param("issueId") UUID issueId);
}
