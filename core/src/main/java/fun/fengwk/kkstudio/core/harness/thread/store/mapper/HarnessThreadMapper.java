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

import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadViewDO;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface HarnessThreadMapper extends BaseMapper {
  @Insert(
      """
      insert into harness_thread (
          id, session_id, head_entry_id, status, input_sequence,
          active_agent_definition_id, active_agent_name, model_id, variant, yolo_enabled,
          processor_token, processor_until, gmt_create, gmt_modified, version
      ) values (
          #{id}, #{sessionId}, #{headEntryId}, #{status}, #{inputSequence},
          #{activeAgentDefinitionId}, #{activeAgentName}, #{modelId}, #{variant}, #{yoloEnabled},
          #{processorToken}, #{processorUntil}, #{createTime}, #{updateTime}, #{version}
      )
      """)
  int insert(HarnessThreadDO thread);

  String THREAD_COLUMNS =
      """
      id, session_id, head_entry_id, status, input_sequence,
      active_agent_definition_id, active_agent_name, model_id, variant, yolo_enabled,
      processor_token, processor_until, version,
      gmt_create as create_time, gmt_modified as update_time
      """;

  @Select(
      """
      select
      """
          + THREAD_COLUMNS
          + """
      from harness_thread
      where id = #{threadId}
      """)
  @Results(
      id = "harnessThreadResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "head_entry_id", property = "headEntryId"),
        @Result(column = "status", property = "status"),
        @Result(column = "input_sequence", property = "inputSequence"),
        @Result(column = "active_agent_definition_id", property = "activeAgentDefinitionId"),
        @Result(column = "active_agent_name", property = "activeAgentName"),
        @Result(column = "model_id", property = "modelId"),
        @Result(column = "variant", property = "variant"),
        @Result(column = "yolo_enabled", property = "yoloEnabled"),
        @Result(column = "processor_token", property = "processorToken"),
        @Result(column = "processor_until", property = "processorUntil"),
        @Result(column = "version", property = "version"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  HarnessThreadDO find(@Param("threadId") long threadId);

  @Select(
      """
      select
      """
          + THREAD_COLUMNS
          + """
      from harness_thread
      where id = #{threadId}
      for update
      """)
  @ResultMap("harnessThreadResultMap")
  HarnessThreadDO findForUpdate(@Param("threadId") long threadId);

  @Select(
      """
      select
      """
          + THREAD_COLUMNS
          + """
      from harness_thread
      where session_id = #{sessionId}
      order by id desc
      """)
  @ResultMap("harnessThreadResultMap")
  List<HarnessThreadDO> listBySession(@Param("sessionId") long sessionId);

  String VIEW_COLUMNS =
      """
      t.id, t.session_id, t.head_entry_id, t.status, t.input_sequence,
      t.active_agent_definition_id, t.active_agent_name, t.model_id, t.variant, t.yolo_enabled,
      t.processor_token, t.processor_until, t.version,
      t.gmt_create as create_time, t.gmt_modified as update_time,
      s.title as session_title
      """;

  @Select(
      """
      select
      """
          + VIEW_COLUMNS
          + """
      from harness_thread t
      join harness_session s on s.id = t.session_id
      order by t.gmt_modified desc, t.id desc
      """)
  @Results(
      id = "harnessThreadViewResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "head_entry_id", property = "headEntryId"),
        @Result(column = "status", property = "status"),
        @Result(column = "input_sequence", property = "inputSequence"),
        @Result(column = "active_agent_definition_id", property = "activeAgentDefinitionId"),
        @Result(column = "active_agent_name", property = "activeAgentName"),
        @Result(column = "model_id", property = "modelId"),
        @Result(column = "variant", property = "variant"),
        @Result(column = "yolo_enabled", property = "yoloEnabled"),
        @Result(column = "processor_token", property = "processorToken"),
        @Result(column = "processor_until", property = "processorUntil"),
        @Result(column = "version", property = "version"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime"),
        @Result(column = "session_title", property = "sessionTitle")
      })
  List<HarnessThreadViewDO> listAllNewestFirst();

  @Select(
      """
      select
      """
          + VIEW_COLUMNS
          + """
      from harness_thread t
      join harness_session s on s.id = t.session_id
      where t.id = #{threadId}
      """)
  @ResultMap("harnessThreadViewResultMap")
  HarnessThreadViewDO findView(@Param("threadId") long threadId);

  @Select(
      """
      select
      """
          + VIEW_COLUMNS
          + """
      from harness_thread t
      join harness_session s on s.id = t.session_id
      where t.session_id = #{sessionId}
      order by t.id desc
      """)
  @ResultMap("harnessThreadViewResultMap")
  List<HarnessThreadViewDO> listViewsBySession(@Param("sessionId") long sessionId);

  @Update(
      """
      update harness_thread
      set processor_token = #{processorToken},
          processor_until = #{processorUntil},
          gmt_modified = #{updateTime},
          version = version + 1
      where id = #{threadId}
        and status in ('RUNNING', 'WAITING', 'RETRYING')
        and (processor_token is null
             or processor_until is null
             or processor_until <= #{now})
      """)
  int tryAcquire(
      @Param("threadId") long threadId,
      @Param("processorToken") String processorToken,
      @Param("processorUntil") LocalDateTime processorUntil,
      @Param("now") LocalDateTime now,
      @Param("updateTime") LocalDateTime updateTime);

  @Update(
      """
      update harness_thread
      set processor_until = #{processorUntil},
          gmt_modified = #{updateTime},
          version = version + 1
      where id = #{threadId} and processor_token = #{processorToken}
      """)
  int renew(
      @Param("threadId") long threadId,
      @Param("processorToken") String processorToken,
      @Param("processorUntil") LocalDateTime processorUntil,
      @Param("updateTime") LocalDateTime updateTime);

  @Update(
      """
      update harness_thread
      set processor_token = null,
          processor_until = null,
          gmt_modified = #{updateTime},
          version = version + 1
      where id = #{threadId} and processor_token = #{processorToken}
      """)
  int release(
      @Param("threadId") long threadId,
      @Param("processorToken") String processorToken,
      @Param("updateTime") LocalDateTime updateTime);

  @Update(
      """
      update harness_thread
      set head_entry_id = #{newHeadEntryId},
          gmt_modified = #{updateTime},
          version = version + 1
      where id = #{threadId}
        and processor_token = #{processorToken}
        and head_entry_id = #{expectedHeadEntryId}
      """)
  int advanceHead(
      @Param("threadId") long threadId,
      @Param("processorToken") String processorToken,
      @Param("expectedHeadEntryId") long expectedHeadEntryId,
      @Param("newHeadEntryId") long newHeadEntryId,
      @Param("updateTime") LocalDateTime updateTime);

  @Update(
      """
      update harness_thread
      set active_agent_definition_id = #{activeAgentDefinitionId},
          active_agent_name = #{activeAgentName},
          model_id = #{modelId},
          variant = #{variant},
          gmt_modified = #{updateTime},
          version = version + 1
      where id = #{threadId}
        and processor_token = #{processorToken}
      """)
  int updateAgentSettings(
      @Param("threadId") long threadId,
      @Param("processorToken") String processorToken,
      @Param("activeAgentDefinitionId") Long activeAgentDefinitionId,
      @Param("activeAgentName") String activeAgentName,
      @Param("modelId") String modelId,
      @Param("variant") String variant,
      @Param("updateTime") LocalDateTime updateTime);

  @Update(
      """
      update harness_thread
      set model_id = #{modelId},
          variant = #{variant},
          gmt_modified = #{updateTime},
          version = version + 1
      where id = #{threadId}
        and processor_token = #{processorToken}
      """)
  int updateModelSettings(
      @Param("threadId") long threadId,
      @Param("processorToken") String processorToken,
      @Param("modelId") String modelId,
      @Param("variant") String variant,
      @Param("updateTime") LocalDateTime updateTime);

  @Update(
      """
      update harness_thread
      set yolo_enabled = #{yoloEnabled},
          gmt_modified = #{updateTime},
          version = version + 1
      where id = #{threadId}
        and processor_token = #{processorToken}
      """)
  int updateYoloSettings(
      @Param("threadId") long threadId,
      @Param("processorToken") String processorToken,
      @Param("yoloEnabled") boolean yoloEnabled,
      @Param("updateTime") LocalDateTime updateTime);

  @Update(
      """
      update harness_thread
      set status = #{status},
          gmt_modified = #{updateTime},
          version = version + 1
      where id = #{threadId} and processor_token = #{processorToken}
      """)
  int updateStatus(
      @Param("threadId") long threadId,
      @Param("processorToken") String processorToken,
      @Param("status") String status,
      @Param("updateTime") LocalDateTime updateTime);

  @Update(
      """
      update harness_thread
      set status = #{status},
          processor_token = null,
          processor_until = null,
          gmt_modified = #{updateTime},
          version = version + 1
      where id = #{threadId}
      """)
  int forceStatusAndClearProcessor(
      @Param("threadId") long threadId,
      @Param("status") String status,
      @Param("updateTime") LocalDateTime updateTime);

  @Update(
      """
      update harness_thread
      set status = #{status},
          gmt_modified = #{updateTime},
          version = version + 1
      where id = #{threadId}
      """)
  int updateStatusDirect(
      @Param("threadId") long threadId,
      @Param("status") String status,
      @Param("updateTime") LocalDateTime updateTime);

  /** 外部 durable 结果到达后仅唤醒等待中的 Thread，不能覆盖并发的 stop/failed 终态。 */
  @Update(
      "update harness_thread set status = 'RUNNING', gmt_modified = #{updateTime}, version = "
          + "version + 1 where id = #{threadId} and status = 'WAITING'")
  int promoteWaitingToRunning(
      @Param("threadId") long threadId, @Param("updateTime") LocalDateTime updateTime);

  @Update(
      """
      update harness_thread
      set input_sequence = input_sequence + 1,
          gmt_modified = #{updateTime},
          version = version + 1
      where id = #{threadId}
      """)
  int allocateInputSequence(
      @Param("threadId") long threadId, @Param("updateTime") LocalDateTime updateTime);

  /**
   * 低频恢复选择：
   *
   * <ul>
   *   <li>RUNNING 或未被当前 head Tool 阻塞的 RETRYING 且无有效 token；
   *   <li>token 已过期；
   *   <li>WAITING 下存在 due/cancelled/terminal Tool work；
   * </ul>
   *
   * <p>不选择 IDLE、FAILED、纯 WAITING_APPROVAL 且没有新 durable work。
   */
  @Select(
      """
      select distinct t.id
      from harness_thread t
      where t.status in ('RUNNING', 'WAITING', 'RETRYING')
        and (
          (
            t.processor_token is not null
            and (t.processor_until is null or t.processor_until <= #{now})
          )
          or (
            (t.processor_token is null
              or t.processor_until is null
              or t.processor_until <= #{now})
            and (
              t.status = 'RUNNING'
              or (
                t.status = 'RETRYING'
                and not exists (
                  select 1 from tool_invocation ti
                  where ti.thread_id = t.id
                    and ti.assistant_entry_id = t.head_entry_id
                    and ti.status not in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN')
                )
              )
              or exists (
                select 1 from harness_thread_input i
                where i.thread_id = t.id
                  and i.status = 'queued'
                  and t.status <> 'RETRYING'
              )
              or exists (
                select 1 from tool_invocation ti
                where ti.thread_id = t.id
                  and (
                    ti.status = 'QUEUED'
                    or (
                      ti.status = 'RUNNING'
                      and (ti.lease_until is null or ti.lease_until <= #{now})
                    )
                    or (
                      ti.status = 'CANCEL_REQUESTED'
                      and (ti.lease_owner is null or ti.lease_owner = ''
                           or ti.lease_until is null or ti.lease_until <= #{now})
                    )
                  )
              )
              or exists (
                select 1 from tool_invocation ti
                where ti.thread_id = t.id
                  and ti.assistant_entry_id = t.head_entry_id
                  and ti.status in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN')
              )
            )
          )
        )
      order by t.id
      limit #{limit}
      """)
  List<Long> listRecoverableThreadIds(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
