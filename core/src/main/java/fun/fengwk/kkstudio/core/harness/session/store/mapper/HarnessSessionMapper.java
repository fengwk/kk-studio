package fun.fengwk.kkstudio.core.harness.session.store.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface HarnessSessionMapper extends BaseMapper {
  @Insert(
      """
      insert into harness_session (
          id, workspace_id, agent_definition_id, title, leaf_entry_id, active_run_id,
          parent_session_id, root_session_id, parent_invocation_id, depth, yolo_enabled,
          gmt_create, gmt_modified, version
      ) values (
          #{id}, #{workspaceId}, #{agentDefinitionId}, #{title}, #{leafEntryId}, #{activeRunId},
          #{parentSessionId}, #{rootSessionId}, #{parentInvocationId}, #{depth}, #{yoloEnabled},
          #{createTime}, #{updateTime}, #{version}
      )
      """)
  int insert(HarnessSessionDO session);

  @Select(
      """
      select id, workspace_id, agent_definition_id, title, leaf_entry_id, active_run_id,
             parent_session_id, root_session_id, parent_invocation_id, depth, yolo_enabled,
             version, gmt_create as create_time, gmt_modified as update_time
      from harness_session
      where id = #{sessionId}
      """)
  @Results(
      id = "harnessSessionResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "workspace_id", property = "workspaceId"),
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
