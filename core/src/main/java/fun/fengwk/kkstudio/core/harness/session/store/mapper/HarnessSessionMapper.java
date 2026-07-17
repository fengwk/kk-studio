package fun.fengwk.kkstudio.core.harness.session.store.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
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
public interface HarnessSessionMapper extends BaseMapper {
  @Insert(
      """
      insert into harness_session (
          id, agent_definition_id, title, leaf_entry_id, active_run_id,
          parent_session_id, root_session_id, parent_invocation_id, depth, yolo_enabled,
          gmt_create, gmt_modified, version
      ) values (
          #{id}, #{agentDefinitionId}, #{title}, #{leafEntryId}, #{activeRunId},
          #{parentSessionId}, #{rootSessionId}, #{parentInvocationId}, #{depth}, #{yoloEnabled},
          #{createTime}, #{updateTime}, #{version}
      )
      """)
  int insert(HarnessSessionDO session);

  @Select(
      """
      select id, agent_definition_id, title, leaf_entry_id, active_run_id,
             parent_session_id, root_session_id, parent_invocation_id, depth, yolo_enabled,
             version, gmt_create as create_time, gmt_modified as update_time
      from harness_session
      where id = #{sessionId}
      """)
  @Results(
      id = "harnessSessionResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "agent_definition_id", property = "agentDefinitionId"),
        @Result(column = "title", property = "title"),
        @Result(column = "leaf_entry_id", property = "leafEntryId"),
        @Result(column = "active_run_id", property = "activeRunId"),
        @Result(column = "parent_session_id", property = "parentSessionId"),
        @Result(column = "root_session_id", property = "rootSessionId"),
        @Result(column = "parent_invocation_id", property = "parentInvocationId"),
        @Result(column = "depth", property = "depth"),
        @Result(column = "yolo_enabled", property = "yoloEnabled"),
        @Result(column = "version", property = "version"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  HarnessSessionDO find(@Param("sessionId") long sessionId);

  @Select(
      """
      select id, agent_definition_id, title, leaf_entry_id, active_run_id,
             parent_session_id, root_session_id, parent_invocation_id, depth, yolo_enabled,
             version, gmt_create as create_time, gmt_modified as update_time
      from harness_session
      where id = #{sessionId}
      for update
      """)
  @ResultMap("harnessSessionResultMap")
  HarnessSessionDO findForUpdate(@Param("sessionId") long sessionId);

  @Update(
      """
      update harness_session
      set leaf_entry_id = #{newLeafEntryId}, active_run_id = #{runId},
          gmt_modified = #{updateTime}, version = version + 1
      where id = #{sessionId} and active_run_id is null
        and ((#{expectedLeafEntryId} is null and leaf_entry_id is null)
          or leaf_entry_id = #{expectedLeafEntryId})
      """)
  int attachRun(
      @Param("sessionId") long sessionId,
      @Param("expectedLeafEntryId") Long expectedLeafEntryId,
      @Param("newLeafEntryId") long newLeafEntryId,
      @Param("runId") long runId,
      @Param("updateTime") LocalDateTime updateTime);

  @Update(
      """
      update harness_session
      set leaf_entry_id = #{newLeafEntryId}, gmt_modified = #{updateTime}, version = version + 1
      where id = #{sessionId} and active_run_id = #{runId}
        and ((#{expectedLeafEntryId} is null and leaf_entry_id is null)
          or leaf_entry_id = #{expectedLeafEntryId})
      """)
  int advanceActiveRunLeaf(
      @Param("sessionId") long sessionId,
      @Param("runId") long runId,
      @Param("expectedLeafEntryId") Long expectedLeafEntryId,
      @Param("newLeafEntryId") long newLeafEntryId,
      @Param("updateTime") LocalDateTime updateTime);

  @Update(
      """
      update harness_session
      set active_run_id = null, gmt_modified = #{updateTime}, version = version + 1
      where id = #{sessionId} and active_run_id = #{runId}
      """)
  int clearActiveRun(
      @Param("sessionId") long sessionId,
      @Param("runId") long runId,
      @Param("updateTime") LocalDateTime updateTime);

  @Select(
      """
      select active_run_id
      from harness_session
      where root_session_id = #{rootSessionId}
        and active_run_id is not null
      order by id
      limit 1
      """)
  Long findAnyActiveRunIdByRoot(@Param("rootSessionId") long rootSessionId);

  @Select(
      """
      select id, agent_definition_id, title, leaf_entry_id, active_run_id,
             parent_session_id, root_session_id, parent_invocation_id, depth, yolo_enabled,
             version, gmt_create as create_time, gmt_modified as update_time
      from harness_session
      where parent_session_id is null
      order by gmt_modified desc, id desc
      """)
  @ResultMap("harnessSessionResultMap")
  List<HarnessSessionDO> listRoots();

  @Update(
      """
      update harness_session
      set yolo_enabled = #{enabled}, gmt_modified = #{updateTime}, version = version + 1
      where id = #{rootSessionId}
        and parent_session_id is null and root_session_id = id
      """)
  int setRootYolo(
      @Param("rootSessionId") long rootSessionId,
      @Param("enabled") boolean enabled,
      @Param("updateTime") LocalDateTime updateTime);

  @Update(
      """
      update harness_session
      set leaf_entry_id = #{newLeafEntryId}, gmt_modified = #{updateTime}, version = version + 1
      where id = #{sessionId}
        and version = #{expectedVersion}
        and ((#{expectedLeafEntryId} is null and leaf_entry_id is null)
             or leaf_entry_id = #{expectedLeafEntryId})
      """)
  int compareAndSetLeaf(
      @Param("sessionId") long sessionId,
      @Param("expectedLeafEntryId") Long expectedLeafEntryId,
      @Param("expectedVersion") long expectedVersion,
      @Param("newLeafEntryId") Long newLeafEntryId,
      @Param("updateTime") LocalDateTime updateTime);
}
