package fun.fengwk.kkstudio.core.harness.tool.worker;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/** Final-schema ToolInvocation mapper shared by worker transactions and read projections. */
@Mapper
public interface PostgresqlToolInvocationMapper extends BaseMapper {

  String FIELDS =
      """
      ti.id, ti.thread_id, ti.session_id, ti.assistant_entry_id, ti.ordinal,
      ti.tool_call_id, ti.descriptor::text as descriptor_json,
      ti.arguments::text as arguments_json, ti.location, ti.environment_name,
      ti.execution_epoch, ti.status, ti.attempt, ti.next_attempt_at,
      ti.worker_token, ti.worker_until, ti.deadline_at, ti.last_activity_at,
      ti.result::text as result_json, ti.error::text as error_json,
      ti.applied_at, ti.created_at, ti.started_at, ti.finished_at
      """;

  @Select(
      "select "
          + FIELDS
          + " from harness_tool_invocation ti where ti.id = #{id} and ti.thread_id = #{threadId} for update")
  @Results(
      id = "toolInvocationResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "thread_id", property = "threadId"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "assistant_entry_id", property = "assistantEntryId"),
        @Result(column = "ordinal", property = "ordinal"),
        @Result(column = "tool_call_id", property = "toolCallId"),
        @Result(column = "descriptor_json", property = "descriptorJson"),
        @Result(column = "arguments_json", property = "argumentsJson"),
        @Result(column = "location", property = "location"),
        @Result(column = "environment_name", property = "environmentName"),
        @Result(column = "execution_epoch", property = "executionEpoch"),
        @Result(column = "status", property = "status"),
        @Result(column = "attempt", property = "attempt"),
        @Result(column = "next_attempt_at", property = "nextAttemptAt"),
        @Result(column = "worker_token", property = "workerToken"),
        @Result(column = "worker_until", property = "workerUntil"),
        @Result(column = "deadline_at", property = "deadlineAt"),
        @Result(column = "last_activity_at", property = "lastActivityAt"),
        @Result(column = "result_json", property = "resultJson"),
        @Result(column = "error_json", property = "errorJson"),
        @Result(column = "applied_at", property = "appliedAt"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "started_at", property = "startedAt"),
        @Result(column = "finished_at", property = "finishedAt")
      })
  ToolInvocationDO findForUpdate(@Param("id") long id, @Param("threadId") long threadId);

  @Select("select " + FIELDS + " from harness_tool_invocation ti where ti.id = #{id}")
  @ResultMap("toolInvocationResultMap")
  ToolInvocationDO find(@Param("id") long id);

  @Select(
      "select "
          + FIELDS
          + " from harness_tool_invocation ti where ti.thread_id = #{threadId}"
          + " order by ti.created_at, ti.assistant_entry_id, ti.ordinal, ti.id")
  @ResultMap("toolInvocationResultMap")
  List<ToolInvocationDO> listByThread(@Param("threadId") long threadId);

  @Select(
      "select "
          + FIELDS
          + """
      from harness_tool_invocation ti
      join harness_thread t on t.id = ti.thread_id
      where ti.id = #{id}
        and t.execution_epoch = ti.execution_epoch
        and not exists (
          select 1 from harness_interaction i
          where i.owner_kind = 'TOOL_INVOCATION' and i.owner_id = ti.id and i.status = 'OPEN'
        )
        and (
          ti.status = 'QUEUED'
          or (ti.status = 'RETRY_WAIT' and ti.next_attempt_at <= #{now})
          or (ti.status = 'RUNNING' and ti.worker_until <= #{now})
        )
      """)
  @ResultMap("toolInvocationResultMap")
  ToolInvocationDO findClaimable(@Param("id") long id, @Param("now") OffsetDateTime now);

  @Update(
      """
      update harness_tool_invocation
      set status = 'RUNNING', worker_token = #{workerToken}, worker_until = #{workerUntil},
          started_at = #{startedAt}, deadline_at = #{deadlineAt}, last_activity_at = #{startedAt}
      where id = #{id} and thread_id = #{threadId} and execution_epoch = #{executionEpoch}
        and status = 'QUEUED' and attempt = #{attempt}
      """)
  int claimQueued(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("executionEpoch") long executionEpoch,
      @Param("attempt") int attempt,
      @Param("workerToken") String workerToken,
      @Param("workerUntil") OffsetDateTime workerUntil,
      @Param("startedAt") OffsetDateTime startedAt,
      @Param("deadlineAt") OffsetDateTime deadlineAt);

  @Update(
      """
      update harness_tool_invocation
      set status = 'RUNNING', attempt = attempt + 1, worker_token = #{workerToken},
          worker_until = #{workerUntil}, next_attempt_at = null,
          last_activity_at = greatest(last_activity_at, #{now})
      where id = #{id} and thread_id = #{threadId} and execution_epoch = #{executionEpoch}
        and status = 'RETRY_WAIT' and attempt = #{attempt} and next_attempt_at <= #{now}
      """)
  int claimRetry(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("executionEpoch") long executionEpoch,
      @Param("attempt") int attempt,
      @Param("workerToken") String workerToken,
      @Param("workerUntil") OffsetDateTime workerUntil,
      @Param("now") OffsetDateTime now);

  @Update(
      """
      update harness_tool_invocation
      set worker_token = #{workerToken}, worker_until = #{workerUntil}
      where id = #{id} and thread_id = #{threadId} and execution_epoch = #{executionEpoch}
        and status = 'RUNNING' and attempt = #{attempt}
        and worker_token = #{expectedToken} and worker_until <= #{now}
      """)
  int recoverExpired(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("executionEpoch") long executionEpoch,
      @Param("attempt") int attempt,
      @Param("expectedToken") String expectedToken,
      @Param("workerToken") String workerToken,
      @Param("workerUntil") OffsetDateTime workerUntil,
      @Param("now") OffsetDateTime now);

  @Update(
      """
      update harness_tool_invocation set worker_until = greatest(worker_until, #{workerUntil})
      where id = #{id} and thread_id = #{threadId} and execution_epoch = #{executionEpoch}
        and status = 'RUNNING' and attempt = #{attempt} and worker_token = #{token}
        and worker_until > #{now}
      """)
  int renew(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("executionEpoch") long executionEpoch,
      @Param("attempt") int attempt,
      @Param("token") String token,
      @Param("workerUntil") OffsetDateTime workerUntil,
      @Param("now") OffsetDateTime now);

  @Update(
      """
      update harness_tool_invocation
      set last_activity_at = greatest(last_activity_at, #{activityAt})
      where id = #{id} and thread_id = #{threadId} and execution_epoch = #{executionEpoch}
        and status = 'RUNNING' and attempt = #{attempt} and worker_token = #{token}
        and worker_until > #{now}
      """)
  int recordActivity(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("executionEpoch") long executionEpoch,
      @Param("attempt") int attempt,
      @Param("token") String token,
      @Param("activityAt") OffsetDateTime activityAt,
      @Param("now") OffsetDateTime now);

  @Update(
      """
      update harness_tool_invocation
      set status = 'QUEUED', worker_token = null, worker_until = null,
          started_at = null, deadline_at = null, last_activity_at = null
      where id = #{id} and thread_id = #{threadId} and execution_epoch = #{executionEpoch}
        and status = 'RUNNING' and attempt = #{attempt} and worker_token = #{token}
        and worker_until > #{now}
      """)
  int releaseUnstartedQueued(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("executionEpoch") long executionEpoch,
      @Param("attempt") int attempt,
      @Param("token") String token,
      @Param("now") OffsetDateTime now);

  @Update(
      """
      update harness_tool_invocation
      set status = 'RETRY_WAIT', attempt = #{previousAttempt}, next_attempt_at = #{nextAttemptAt},
          worker_token = null, worker_until = null
      where id = #{id} and thread_id = #{threadId} and execution_epoch = #{executionEpoch}
        and status = 'RUNNING' and attempt = #{attempt} and worker_token = #{token}
        and worker_until > #{now}
      """)
  int releaseUnstartedRetry(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("executionEpoch") long executionEpoch,
      @Param("attempt") int attempt,
      @Param("previousAttempt") int previousAttempt,
      @Param("token") String token,
      @Param("nextAttemptAt") OffsetDateTime nextAttemptAt,
      @Param("now") OffsetDateTime now);

  @Update(
      """
      update harness_tool_invocation
      set status = 'SUCCEEDED', result = cast(#{resultJson} as jsonb), error = null,
          next_attempt_at = null, worker_token = null, worker_until = null,
          last_activity_at = greatest(last_activity_at, #{lastObserved}),
          finished_at = greatest(#{finishedAt}, greatest(last_activity_at, #{lastObserved}))
      where id = #{id} and thread_id = #{threadId} and execution_epoch = #{executionEpoch}
        and status = 'RUNNING' and attempt = #{attempt} and worker_token = #{token}
        and worker_until > #{now}
      """)
  int completeSuccess(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("executionEpoch") long executionEpoch,
      @Param("attempt") int attempt,
      @Param("token") String token,
      @Param("resultJson") String resultJson,
      @Param("lastObserved") OffsetDateTime lastObserved,
      @Param("finishedAt") OffsetDateTime finishedAt,
      @Param("now") OffsetDateTime now);

  @Update(
      """
      update harness_tool_invocation
      set status = #{status}, result = null, error = cast(#{errorJson} as jsonb),
          next_attempt_at = null, worker_token = null, worker_until = null,
          last_activity_at = greatest(last_activity_at, #{lastObserved}),
          finished_at = greatest(#{finishedAt}, greatest(last_activity_at, #{lastObserved}))
      where id = #{id} and thread_id = #{threadId} and execution_epoch = #{executionEpoch}
        and status = 'RUNNING' and attempt = #{attempt} and worker_token = #{token}
        and worker_until > #{now}
      """)
  int completeError(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("executionEpoch") long executionEpoch,
      @Param("attempt") int attempt,
      @Param("token") String token,
      @Param("status") String status,
      @Param("errorJson") String errorJson,
      @Param("lastObserved") OffsetDateTime lastObserved,
      @Param("finishedAt") OffsetDateTime finishedAt,
      @Param("now") OffsetDateTime now);

  @Update(
      """
      update harness_tool_invocation
      set status = 'CANCELLED', result = null, error = null, next_attempt_at = null,
          worker_token = null, worker_until = null,
          last_activity_at = greatest(last_activity_at, #{lastObserved}),
          finished_at = greatest(#{finishedAt}, greatest(last_activity_at, #{lastObserved}))
      where id = #{id} and thread_id = #{threadId} and execution_epoch = #{executionEpoch}
        and status = 'RUNNING' and attempt = #{attempt} and worker_token = #{token}
        and worker_until > #{now}
      """)
  int completeCancelled(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("executionEpoch") long executionEpoch,
      @Param("attempt") int attempt,
      @Param("token") String token,
      @Param("lastObserved") OffsetDateTime lastObserved,
      @Param("finishedAt") OffsetDateTime finishedAt,
      @Param("now") OffsetDateTime now);

  @Update(
      """
      update harness_tool_invocation
      set status = 'RETRY_WAIT', worker_token = null, worker_until = null,
          last_activity_at = greatest(last_activity_at, #{lastObserved}),
          next_attempt_at = #{nextAttemptAt}
      where id = #{id} and thread_id = #{threadId} and execution_epoch = #{executionEpoch}
        and status = 'RUNNING' and attempt = #{attempt} and worker_token = #{token}
        and worker_until > #{now}
      """)
  int scheduleRetry(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("executionEpoch") long executionEpoch,
      @Param("attempt") int attempt,
      @Param("token") String token,
      @Param("lastObserved") OffsetDateTime lastObserved,
      @Param("nextAttemptAt") OffsetDateTime nextAttemptAt,
      @Param("now") OffsetDateTime now);
}
