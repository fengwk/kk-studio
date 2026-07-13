package fun.fengwk.kkstudio.core.harness.session.store.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import java.time.LocalDateTime;
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
          id, session_id, workspace_id, parent_session_id, leaf_entry_id, gmt_create, gmt_modified, version
      ) values (
          #{id}, #{sessionId}, #{workspaceId}, #{parentSessionId}, #{leafEntryId},
          #{createTime}, #{createTime}, 0
      )
      """)
  int insert(HarnessSessionDO session);

  @Select(
      """
      select id, session_id, workspace_id, parent_session_id, leaf_entry_id, gmt_create as create_time
      from harness_session
      where session_id = #{sessionId}
      """)
  @Results(
      id = "harnessSessionResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "workspace_id", property = "workspaceId"),
        @Result(column = "parent_session_id", property = "parentSessionId"),
        @Result(column = "leaf_entry_id", property = "leafEntryId"),
        @Result(column = "create_time", property = "createTime")
      })
  HarnessSessionDO find(@Param("sessionId") String sessionId);

  @Update(
      """
      update harness_session
      set leaf_entry_id = #{newLeafEntryId}, gmt_modified = #{updateTime}
      where session_id = #{sessionId}
        and ((#{expectedLeafEntryId} is null and leaf_entry_id is null)
             or leaf_entry_id = #{expectedLeafEntryId})
      """)
  int compareAndSetLeaf(
      @Param("sessionId") String sessionId,
      @Param("expectedLeafEntryId") String expectedLeafEntryId,
      @Param("newLeafEntryId") String newLeafEntryId,
      @Param("updateTime") LocalDateTime updateTime);
}
