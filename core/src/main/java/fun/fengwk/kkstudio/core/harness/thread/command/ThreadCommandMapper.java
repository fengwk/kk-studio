package fun.fengwk.kkstudio.core.harness.thread.command;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/** final schema command adapter 专用 mapper；不复用 legacy Thread mapper。 */
@Mapper
public interface ThreadCommandMapper extends BaseMapper {

  @Insert(
      "insert into harness_session (id, title, main_thread_id, created_at, updated_at) "
          + "values (#{id}, #{title}, #{mainThreadId}, #{now}, #{now})")
  int insertSession(
      @Param("id") long id,
      @Param("title") String title,
      @Param("mainThreadId") long mainThreadId,
      @Param("now") OffsetDateTime now);

  @Insert(
      "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at) "
          + "values (#{id}, #{sessionId}, #{parentEntryId}, #{entryType}, cast(#{payloadJson} as jsonb), #{now})")
  int insertEntry(
      @Param("id") long id,
      @Param("sessionId") long sessionId,
      @Param("parentEntryId") Long parentEntryId,
      @Param("entryType") String entryType,
      @Param("payloadJson") String payloadJson,
      @Param("now") OffsetDateTime now);

  @Insert(
      "insert into harness_thread (id, session_id, head_entry_id, input_sequence, runnable, execution_epoch, created_at, updated_at) "
          + "values (#{id}, #{sessionId}, #{headEntryId}, 0, false, 0, #{now}, #{now})")
  int insertThread(
      @Param("id") long id,
      @Param("sessionId") long sessionId,
      @Param("headEntryId") long headEntryId,
      @Param("now") OffsetDateTime now);

  @Select(
      "select id, title, main_thread_id, created_at, updated_at from harness_session where id = #{sessionId}")
  @Results(
      id = "sessionResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "title", property = "title"),
        @Result(column = "main_thread_id", property = "mainThreadId"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
      })
  ThreadCommandRow findSession(@Param("sessionId") long sessionId);

  @Select(
      "select id, session_id, parent_entry_id, entry_type, payload::text as payload_json, created_at "
          + "from harness_entry where session_id = #{sessionId} and id = #{entryId}")
  @Results(
      id = "entryResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "parent_entry_id", property = "parentEntryId"),
        @Result(column = "entry_type", property = "entryType"),
        @Result(column = "payload_json", property = "payloadJson"),
        @Result(column = "created_at", property = "createdAt")
      })
  ThreadCommandRow findEntry(@Param("sessionId") long sessionId, @Param("entryId") long entryId);

  @Select(
      "select id, session_id, head_entry_id, input_sequence, runnable, execution_epoch, processor_token, processor_until, created_at, updated_at "
          + "from harness_thread where id = #{threadId} for update")
  @Results(
      id = "threadResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "head_entry_id", property = "headEntryId"),
        @Result(column = "input_sequence", property = "inputSequence"),
        @Result(column = "runnable", property = "runnable"),
        @Result(column = "execution_epoch", property = "executionEpoch"),
        @Result(column = "processor_token", property = "processorToken"),
        @Result(column = "processor_until", property = "processorUntil"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
      })
  ThreadCommandRow findThreadForUpdate(@Param("threadId") long threadId);

  @Select(
      """
      with recursive path (id, parent_entry_id, depth) as (
        select e.id, e.parent_entry_id, 0 from harness_entry e
        where e.session_id = #{sessionId} and e.id = #{headEntryId}
        union all
        select e.id, e.parent_entry_id, p.depth + 1 from harness_entry e
        join path p on p.parent_entry_id = e.id where e.session_id = #{sessionId}
      ), queued_config as (
        select i.payload from harness_thread_input i
        where i.thread_id = #{threadId}
          and i.status = 'QUEUED'
          and i.input_type in ('SET_AGENT', 'SET_MODEL', 'SET_YOLO')
        order by i.sequence desc
        limit 1
      ), path_config as (
        select e.payload
        from path p join harness_entry e on e.id = p.id and e.session_id = #{sessionId}
        where e.entry_type = 'RUNTIME_CONFIG'
        order by p.depth
        limit 1
      )
      select q.payload::text as payload_json from queued_config q
      union all
      select p.payload::text as payload_json from path_config p
      where not exists (select 1 from queued_config)
      """)
  @Results(
      id = "runtimeConfigEntryResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "parent_entry_id", property = "parentEntryId"),
        @Result(column = "entry_type", property = "entryType"),
        @Result(column = "payload_json", property = "payloadJson"),
        @Result(column = "created_at", property = "createdAt")
      })
  ThreadCommandRow findEffectiveRuntimeConfig(
      @Param("sessionId") long sessionId,
      @Param("headEntryId") long headEntryId,
      @Param("threadId") long threadId);

  @Select(
      "select id, thread_id, sequence, input_type, payload::text as payload_json, idempotency_key, status, created_at, applied_at "
          + "from harness_thread_input where thread_id = #{threadId} and idempotency_key = #{key} for update")
  @Results(
      id = "inputResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "thread_id", property = "threadId"),
        @Result(column = "sequence", property = "sequence"),
        @Result(column = "input_type", property = "inputType"),
        @Result(column = "payload_json", property = "payloadJson"),
        @Result(column = "idempotency_key", property = "idempotencyKey"),
        @Result(column = "status", property = "status"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "applied_at", property = "appliedAt")
      })
  ThreadCommandRow findInputByKeyForUpdate(
      @Param("threadId") long threadId, @Param("key") String key);

  @Select(
      "select id, thread_id, sequence, input_type, payload::text as payload_json, idempotency_key, status, created_at, applied_at "
          + "from harness_thread_input where thread_id = #{threadId} and status = 'QUEUED' order by sequence for update")
  @Results(
      id = "queuedInputResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "thread_id", property = "threadId"),
        @Result(column = "sequence", property = "sequence"),
        @Result(column = "input_type", property = "inputType"),
        @Result(column = "payload_json", property = "payloadJson"),
        @Result(column = "idempotency_key", property = "idempotencyKey"),
        @Result(column = "status", property = "status"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "applied_at", property = "appliedAt")
      })
  List<ThreadCommandRow> listQueuedInputsForUpdate(@Param("threadId") long threadId);

  @Update(
      "update harness_thread set input_sequence = #{sequence}, runnable = true, updated_at = greatest(updated_at, #{now}) "
          + "where id = #{threadId}")
  int advanceInputSequenceAndMarkRunnable(
      @Param("threadId") long threadId,
      @Param("sequence") long sequence,
      @Param("now") OffsetDateTime now);

  @Insert(
      "insert into harness_thread_input (id, thread_id, sequence, input_type, payload, idempotency_key, status, created_at) "
          + "values (#{id}, #{threadId}, #{sequence}, #{inputType}, cast(#{payloadJson} as jsonb), #{key}, 'QUEUED', #{now})")
  int insertInput(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("sequence") long sequence,
      @Param("inputType") String inputType,
      @Param("payloadJson") String payloadJson,
      @Param("key") String key,
      @Param("now") OffsetDateTime now);

  @Update(
      "update harness_thread set execution_epoch = #{epoch}, processor_token = null, processor_until = null, runnable = false, "
          + "updated_at = greatest(updated_at, #{now}) where id = #{threadId}")
  int fenceAndStopThread(
      @Param("threadId") long threadId,
      @Param("epoch") long epoch,
      @Param("now") OffsetDateTime now);

  @Update(
      "update harness_thread_input set status = 'CANCELLED' where thread_id = #{threadId} and status = 'QUEUED'")
  int cancelQueuedInputs(@Param("threadId") long threadId);

  @Select(
      "select id, thread_id, sequence, input_type, payload::text as payload_json, idempotency_key, status, created_at, applied_at "
          + "from harness_thread_input where thread_id = #{threadId} and status = 'CANCELLED' order by sequence")
  @Results(
      id = "cancelledInputResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "thread_id", property = "threadId"),
        @Result(column = "sequence", property = "sequence"),
        @Result(column = "input_type", property = "inputType"),
        @Result(column = "payload_json", property = "payloadJson"),
        @Result(column = "idempotency_key", property = "idempotencyKey"),
        @Result(column = "status", property = "status"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "applied_at", property = "appliedAt")
      })
  List<ThreadCommandRow> listCancelledInputs(@Param("threadId") long threadId);

  @Update(
      "update harness_model_invocation set status = 'CANCELLED', next_attempt_at = null, worker_token = null, worker_until = null, "
          + "finished_at = #{now} where thread_id = #{threadId} and status in ('QUEUED', 'RETRY_WAIT')")
  int cancelSafeModelInvocations(
      @Param("threadId") long threadId, @Param("now") OffsetDateTime now);

  @Update(
      "update harness_tool_invocation set status = 'CANCELLED', next_attempt_at = null, worker_token = null, worker_until = null, "
          + "finished_at = #{now} where thread_id = #{threadId} and status in ('QUEUED', 'RETRY_WAIT')")
  int cancelSafeToolInvocations(@Param("threadId") long threadId, @Param("now") OffsetDateTime now);
}
