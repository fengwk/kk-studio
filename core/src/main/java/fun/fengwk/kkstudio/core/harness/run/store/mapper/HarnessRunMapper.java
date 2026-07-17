package fun.fengwk.kkstudio.core.harness.run.store.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface HarnessRunMapper extends BaseMapper {
  @Insert(
      """
      insert into harness_run (
          id, session_id, trigger_entry_id, status, turn_index, attempt, event_sequence,
          lease_owner, lease_until, next_attempt_at, cancel_requested_at, gmt_create,
          started_at, finished_at, gmt_modified
      ) values (
          #{id}, #{sessionId}, #{triggerEntryId}, #{status}, #{turnIndex}, #{attempt},
          #{eventSequence}, #{leaseOwner}, #{leaseUntil}, #{nextAttemptAt}, #{cancelRequestedAt},
          #{createTime}, #{startedAt}, #{finishedAt}, #{updateTime}
      )
      """)
  int insert(HarnessRunDO run);

  @Select(
      """
      select id, session_id, trigger_entry_id, status, turn_index, attempt, event_sequence,
             lease_owner, lease_until, next_attempt_at, cancel_requested_at,
             gmt_create as create_time, started_at, finished_at, gmt_modified as update_time
      from harness_run
      where id = #{runId}
      """)
  @Results(
      id = "harnessRunResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "trigger_entry_id", property = "triggerEntryId"),
        @Result(column = "status", property = "status"),
        @Result(column = "turn_index", property = "turnIndex"),
        @Result(column = "attempt", property = "attempt"),
        @Result(column = "event_sequence", property = "eventSequence"),
        @Result(column = "lease_owner", property = "leaseOwner"),
        @Result(column = "lease_until", property = "leaseUntil"),
        @Result(column = "next_attempt_at", property = "nextAttemptAt"),
        @Result(column = "cancel_requested_at", property = "cancelRequestedAt"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "started_at", property = "startedAt"),
        @Result(column = "finished_at", property = "finishedAt"),
        @Result(column = "update_time", property = "updateTime")
      })
  HarnessRunDO find(@Param("runId") long runId);

  @Select(
      """
      select id, session_id, trigger_entry_id, status, turn_index, attempt, event_sequence,
             lease_owner, lease_until, next_attempt_at, cancel_requested_at,
             gmt_create as create_time, started_at, finished_at, gmt_modified as update_time
      from harness_run
      where id = #{runId}
      for update
      """)
  @ResultMap("harnessRunResultMap")
  HarnessRunDO findForUpdate(@Param("runId") long runId);

  @Select(
      """
      select id, session_id, trigger_entry_id, status, turn_index, attempt, event_sequence,
             lease_owner, lease_until, next_attempt_at, cancel_requested_at,
             gmt_create as create_time, started_at, finished_at, gmt_modified as update_time
      from harness_run
      where (status = 'QUEUED' and next_attempt_at <= #{now})
         or (status = 'RUNNING' and lease_until <= #{now})
      order by next_attempt_at asc, id asc
      limit 1
      """)
  @ResultMap("harnessRunResultMap")
  HarnessRunDO findClaimCandidate(@Param("now") LocalDateTime now);

  @Update(
      """
      update harness_run
      set status = 'RUNNING', lease_owner = #{owner}, lease_until = #{leaseUntil},
          attempt = attempt + 1, started_at = coalesce(started_at, #{now}),
          gmt_modified = #{now}
      where id = #{runId}
        and ((status = 'QUEUED' and next_attempt_at <= #{now})
          or (status = 'RUNNING' and lease_until <= #{now}))
      """)
  int claim(
      @Param("runId") long runId,
      @Param("owner") String owner,
      @Param("now") LocalDateTime now,
      @Param("leaseUntil") LocalDateTime leaseUntil);

  @Update(
      """
      update harness_run
      set lease_until = #{leaseUntil}, gmt_modified = #{now}
      where id = #{runId} and status = 'RUNNING'
        and lease_owner = #{owner} and attempt = #{attempt}
        and lease_until > #{now}
        and cancel_requested_at is null
      """)
  int heartbeat(
      @Param("runId") long runId,
      @Param("owner") String owner,
      @Param("attempt") int attempt,
      @Param("now") LocalDateTime now,
      @Param("leaseUntil") LocalDateTime leaseUntil);

  @Update(
      """
      update harness_run
      set cancel_requested_at = coalesce(cancel_requested_at, #{requestedAt}),
          gmt_modified = #{requestedAt}
      where id = #{runId} and status not in ('SUCCEEDED', 'FAILED', 'CANCELLED')
        and cancel_requested_at is null
      """)
  int requestCancel(@Param("runId") long runId, @Param("requestedAt") LocalDateTime requestedAt);

  @Update(
      """
      update harness_run
      set status = 'QUEUED', lease_owner = null, lease_until = null,
          next_attempt_at = #{nextAttemptAt}, gmt_modified = #{now}
      where id = #{runId} and status = 'RUNNING'
        and lease_owner = #{owner} and attempt = #{attempt}
      """)
  int requeueOwned(
      @Param("runId") long runId,
      @Param("owner") String owner,
      @Param("attempt") int attempt,
      @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
      @Param("now") LocalDateTime now);

  @Update(
      """
      update harness_run
      set status = 'QUEUED', turn_index = turn_index + 1,
          lease_owner = null, lease_until = null,
          next_attempt_at = #{now}, gmt_modified = #{now}
      where id = #{runId} and status = 'RUNNING'
        and lease_owner = #{owner} and attempt = #{attempt}
      """)
  int requeueAdvanceTurn(
      @Param("runId") long runId,
      @Param("owner") String owner,
      @Param("attempt") int attempt,
      @Param("now") LocalDateTime now);

  @Update(
      """
      update harness_run
      set status = #{status}, turn_index = turn_index + 1,
          lease_owner = null, lease_until = null,
          finished_at = case when #{status} = 'SUCCEEDED' then #{now} else finished_at end,
          gmt_modified = #{now}
      where id = #{runId} and status = 'RUNNING'
        and lease_owner = #{owner} and attempt = #{attempt}
      """)
  int completeTurnOwned(
      @Param("runId") long runId,
      @Param("owner") String owner,
      @Param("attempt") int attempt,
      @Param("status") String status,
      @Param("now") LocalDateTime now);

  @Update(
      """
      update harness_run
      set status = #{status}, lease_owner = null, lease_until = null,
          finished_at = #{now}, gmt_modified = #{now}
      where id = #{runId} and status = 'RUNNING'
        and lease_owner = #{owner} and attempt = #{attempt}
      """)
  int terminateOwned(
      @Param("runId") long runId,
      @Param("owner") String owner,
      @Param("attempt") int attempt,
      @Param("status") String status,
      @Param("now") LocalDateTime now);

  @Select(
      """
      select id, session_id, trigger_entry_id, status, turn_index, attempt, event_sequence,
             lease_owner, lease_until, next_attempt_at, cancel_requested_at,
             gmt_create as create_time, started_at, finished_at, gmt_modified as update_time
      from harness_run hr
      where hr.status = 'WAITING_TOOLS'
        and exists (
          select 1 from tool_invocation ti
          where ti.run_id = hr.id
        )
        and not exists (
          select 1 from tool_invocation ti
          where ti.run_id = hr.id
            and ti.status not in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN')
        )
      order by hr.id asc
      limit #{limit}
      """)
  @ResultMap("harnessRunResultMap")
  List<HarnessRunDO> listReadyWaitingTools(@Param("limit") int limit);

  @Update(
      """
      update harness_run
      set status = 'QUEUED', lease_owner = null, lease_until = null, next_attempt_at = #{now},
          gmt_modified = #{now}
      where id = #{runId} and status = 'WAITING_TOOLS'
      """)
  int requeueWaitingTools(@Param("runId") long runId, @Param("now") LocalDateTime now);

  @Update(
      """
      update harness_run
      set event_sequence = #{nextSequence}, gmt_modified = #{now}
      where id = #{runId} and event_sequence = #{expectedSequence}
      """)
  int updateEventSequence(
      @Param("runId") long runId,
      @Param("expectedSequence") long expectedSequence,
      @Param("nextSequence") long nextSequence,
      @Param("now") LocalDateTime now);

  @Select("select count(*) from tool_invocation where run_id = #{runId}")
  int countToolInvocations(@Param("runId") long runId);

  @Select(
      """
      select id, session_id, trigger_entry_id, status, turn_index, attempt, event_sequence,
             lease_owner, lease_until, next_attempt_at, cancel_requested_at,
             gmt_create as create_time, started_at, finished_at, gmt_modified as update_time
      from harness_run
      where session_id = #{sessionId}
      order by gmt_create asc, id asc
      """)
  @ResultMap("harnessRunResultMap")
  List<HarnessRunDO> listBySessionOrderByCreateTimeAsc(@Param("sessionId") long sessionId);
}
