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
          applied_entry_id, applied_at, gmt_create
      ) values (
          #{id}, #{threadId}, #{sequence}, #{inputType}, #{payloadJson}, #{clientMessageId},
          #{appliedEntryId}, #{appliedAt}, #{createTime}
      )
      """)
  int insert(HarnessThreadInputDO input);

  @Select(
      """
      select id, thread_id, sequence, input_type, payload_json, client_message_id,
             applied_entry_id, applied_at, gmt_create as create_time
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
        @Result(column = "applied_entry_id", property = "appliedEntryId"),
        @Result(column = "applied_at", property = "appliedAt"),
        @Result(column = "create_time", property = "createTime")
      })
  HarnessThreadInputDO find(@Param("inputId") long inputId);

  @Select(
      """
      select id, thread_id, sequence, input_type, payload_json, client_message_id,
             applied_entry_id, applied_at, gmt_create as create_time
      from harness_thread_input
      where thread_id = #{threadId} and client_message_id = #{clientMessageId}
      """)
  @ResultMap("harnessThreadInputResultMap")
  HarnessThreadInputDO findByClientMessageId(
      @Param("threadId") long threadId, @Param("clientMessageId") String clientMessageId);

  @Select(
      """
      select id, thread_id, sequence, input_type, payload_json, client_message_id,
             applied_entry_id, applied_at, gmt_create as create_time
      from harness_thread_input
      where thread_id = #{threadId}
      order by sequence asc
      """)
  @ResultMap("harnessThreadInputResultMap")
  List<HarnessThreadInputDO> listByThread(@Param("threadId") long threadId);

  @Select(
      """
      select id, thread_id, sequence, input_type, payload_json, client_message_id,
             applied_entry_id, applied_at, gmt_create as create_time
      from harness_thread_input
      where thread_id = #{threadId} and applied_entry_id is null
      order by sequence asc
      """)
  @ResultMap("harnessThreadInputResultMap")
  List<HarnessThreadInputDO> listPending(@Param("threadId") long threadId);

  @Select(
      """
      select id, thread_id, sequence, input_type, payload_json, client_message_id,
             applied_entry_id, applied_at, gmt_create as create_time
      from harness_thread_input
      where thread_id = #{threadId} and applied_entry_id is null
      order by sequence asc
      limit 1
      """)
  @ResultMap("harnessThreadInputResultMap")
  HarnessThreadInputDO findNextPending(@Param("threadId") long threadId);

  @Select(
      """
      select id, thread_id, sequence, input_type, payload_json, client_message_id,
             applied_entry_id, applied_at, gmt_create as create_time
      from harness_thread_input
      where id = #{inputId}
      for update
      """)
  @ResultMap("harnessThreadInputResultMap")
  HarnessThreadInputDO findForUpdate(@Param("inputId") long inputId);

  @Update(
      """
      update harness_thread_input
      set applied_entry_id = #{appliedEntryId}, applied_at = #{appliedAt}
      where id = #{inputId} and applied_entry_id is null
      """)
  int markApplied(
      @Param("inputId") long inputId,
      @Param("appliedEntryId") long appliedEntryId,
      @Param("appliedAt") LocalDateTime appliedAt);
}
