package fun.fengwk.kkstudio.core.harness.thread.store.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadInputDO;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface HarnessThreadInputMapper extends BaseMapper {
  @Insert(
      """
      insert into harness_thread_input (
          id, thread_id, sequence, input_type, payload_json, client_message_id,
          status, applied_entry_id, resolved_at, cancelled_by_stop_id, gmt_create
      ) values (
          #{id}, #{threadId}, #{sequence}, #{inputType}, #{payloadJson}, #{clientMessageId},
          #{status}, #{appliedEntryId}, #{resolvedAt}, #{cancelledByStopId}, #{createTime}
      )
      """)
  int insert(HarnessThreadInputDO input);

  @Select(
      """
      select id, thread_id, sequence, input_type, payload_json, client_message_id,
             status, applied_entry_id, resolved_at, cancelled_by_stop_id,
             gmt_create as create_time
      from harness_thread_input
      where id = #{inputId}
      """)
  @Results(
      id = "harnessThreadInputResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "thread_id", property = "threadId"),
        @Result(column = "sequence", property = "sequence"),
        @Result(column = "input_type", property = "inputType"),
        @Result(column = "payload_json", property = "payloadJson"),
        @Result(column = "client_message_id", property = "clientMessageId"),
        @Result(column = "status", property = "status"),
        @Result(column = "applied_entry_id", property = "appliedEntryId"),
        @Result(column = "resolved_at", property = "resolvedAt"),
        @Result(column = "cancelled_by_stop_id", property = "cancelledByStopId"),
        @Result(column = "create_time", property = "createTime")
      })
  HarnessThreadInputDO find(@Param("inputId") long inputId);

  @Select(
      """
      select id, thread_id, sequence, input_type, payload_json, client_message_id,
             status, applied_entry_id, resolved_at, cancelled_by_stop_id,
             gmt_create as create_time
      from harness_thread_input
      where thread_id = #{threadId} and client_message_id = #{clientMessageId}
      """)
  @ResultMap("harnessThreadInputResultMap")
  HarnessThreadInputDO findByClientMessageId(
      @Param("threadId") long threadId, @Param("clientMessageId") String clientMessageId);

  @Select(
      """
      select id, thread_id, sequence, input_type, payload_json, client_message_id,
             status, applied_entry_id, resolved_at, cancelled_by_stop_id,
             gmt_create as create_time
      from harness_thread_input
      where thread_id = #{threadId}
      order by sequence asc
      """)
  @ResultMap("harnessThreadInputResultMap")
  List<HarnessThreadInputDO> listByThread(@Param("threadId") long threadId);

  @Select(
      """
      select id, thread_id, sequence, input_type, payload_json, client_message_id,
             status, applied_entry_id, resolved_at, cancelled_by_stop_id,
             gmt_create as create_time
      from harness_thread_input
      where thread_id = #{threadId} and status = 'queued'
      order by sequence asc
      """)
  @ResultMap("harnessThreadInputResultMap")
  List<HarnessThreadInputDO> listQueued(@Param("threadId") long threadId);

  @Select(
      """
      select id, thread_id, sequence, input_type, payload_json, client_message_id,
             status, applied_entry_id, resolved_at, cancelled_by_stop_id,
             gmt_create as create_time
      from harness_thread_input
      where thread_id = #{threadId}
        and status = 'queued'
        and sequence <= #{cutoffSequence}
      order by sequence asc
      """)
  @ResultMap("harnessThreadInputResultMap")
  List<HarnessThreadInputDO> listQueuedUpTo(
      @Param("threadId") long threadId, @Param("cutoffSequence") long cutoffSequence);

  @Select(
      """
      select id, thread_id, sequence, input_type, payload_json, client_message_id,
             status, applied_entry_id, resolved_at, cancelled_by_stop_id,
             gmt_create as create_time
      from harness_thread_input
      where thread_id = #{threadId}
        and status = 'cancelled'
        and cancelled_by_stop_id = #{stopId}
      order by sequence asc
      """)
  @ResultMap("harnessThreadInputResultMap")
  List<HarnessThreadInputDO> listCancelledByStop(
      @Param("threadId") long threadId, @Param("stopId") long stopId);

  @Select(
      """
      select id, thread_id, sequence, input_type, payload_json, client_message_id,
             status, applied_entry_id, resolved_at, cancelled_by_stop_id,
             gmt_create as create_time
      from harness_thread_input
      where id = #{inputId}
      for update
      """)
  @ResultMap("harnessThreadInputResultMap")
  HarnessThreadInputDO findForUpdate(@Param("inputId") long inputId);

  @Update(
      """
      update harness_thread_input
      set status = 'applied',
          applied_entry_id = #{appliedEntryId},
          resolved_at = #{resolvedAt}
      where id = #{inputId} and status = 'queued'
      """)
  int markApplied(
      @Param("inputId") long inputId,
      @Param("appliedEntryId") long appliedEntryId,
      @Param("resolvedAt") LocalDateTime resolvedAt);

  @Update(
      """
      update harness_thread_input
      set status = 'cancelled',
          cancelled_by_stop_id = #{stopId},
          resolved_at = #{resolvedAt}
      where id = #{inputId} and status = 'queued'
      """)
  int markCancelled(
      @Param("inputId") long inputId,
      @Param("stopId") long stopId,
      @Param("resolvedAt") LocalDateTime resolvedAt);
}
