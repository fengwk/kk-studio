package fun.fengwk.kkstudio.core.harness.thread.store.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadEventDO;

import java.util.List;

@Mapper
public interface HarnessThreadEventMapper extends BaseMapper {
  @Insert(
      """
      insert into harness_thread_event (
          id, thread_id, subject_entry_id, event_type, payload_json, gmt_create
      ) values (
          #{id}, #{threadId}, #{subjectEntryId}, #{eventType}, #{payloadJson}, #{createTime}
      )
      """)
  int insert(HarnessThreadEventDO event);

  @Select(
      """
      select id, thread_id, subject_entry_id, event_type, payload_json, gmt_create as create_time
      from harness_thread_event
      where thread_id = #{threadId} and id > #{afterEventId}
      order by id asc
      limit #{limit}
      """)
  @Results(
      id = "harnessThreadEventResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "thread_id", property = "threadId"),
        @Result(column = "subject_entry_id", property = "subjectEntryId"),
        @Result(column = "event_type", property = "eventType"),
        @Result(column = "payload_json", property = "payloadJson"),
        @Result(column = "create_time", property = "createTime")
      })
  List<HarnessThreadEventDO> listAfter(
      @Param("threadId") long threadId,
      @Param("afterEventId") long afterEventId,
      @Param("limit") int limit);

  /** 最新 lifecycle 事件（thread_idle / thread_failed），按 id 降序取一条。 */
  @Select(
      """
      select id, thread_id, subject_entry_id, event_type, payload_json, gmt_create as create_time
      from harness_thread_event
      where thread_id = #{threadId}
        and event_type in ('thread_idle', 'thread_failed')
      order by id desc
      limit 1
      """)
  @ResultMap("harnessThreadEventResultMap")
  HarnessThreadEventDO findLatestLifecycle(@Param("threadId") long threadId);

  /** 最新失败事件（thread_failed / assistant_failed）。 */
  @Select(
      """
      select id, thread_id, subject_entry_id, event_type, payload_json, gmt_create as create_time
      from harness_thread_event
      where thread_id = #{threadId}
        and event_type in ('thread_failed', 'assistant_failed')
      order by id desc
      limit 1
      """)
  @ResultMap("harnessThreadEventResultMap")
  HarnessThreadEventDO findLatestFailure(@Param("threadId") long threadId);

  @Select(
      """
      select count(*)
      from harness_thread_event
      where thread_id = #{threadId} and event_type = #{eventType}
      """)
  int countByType(@Param("threadId") long threadId, @Param("eventType") String eventType);
}
