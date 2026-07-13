package fun.fengwk.kkstudio.core.agent.session.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import fun.fengwk.kkstudio.core.agent.session.repo.impl.model.AgentSessionDO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author fengwk
 */
@Mapper
public interface AgentSessionMapper extends BaseMapper {

  @Select("select count(*) from agent_session")
  long countAll();

  @Insert(
      """
        insert into agent_session (
            id,
            session_id,
            agent_id,
            agent_name,
            title,
            current_head_event_id,
            gmt_create,
            gmt_modified,
            version
        ) values (
            #{id},
            #{sessionId},
            #{agentId},
            #{agentName},
            #{title},
            #{currentHeadEventId},
            #{createTime},
            #{updateTime},
            0
        )
        """)
  int insertSelective(AgentSessionDO sessionDO);

  @Select(
      """
        select
            id,
            session_id,
            agent_id,
            agent_name,
            title,
            current_head_event_id,
            gmt_create as create_time,
            gmt_modified as update_time
        from agent_session
        order by gmt_modified desc, id desc
        limit #{offset}, #{limit}
        """)
  @Results(
      id = "agentSessionResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "agent_id", property = "agentId"),
        @Result(column = "agent_name", property = "agentName"),
        @Result(column = "title", property = "title"),
        @Result(column = "current_head_event_id", property = "currentHeadEventId"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  List<AgentSessionDO> pageAll(@Param("offset") long offset, @Param("limit") int limit);

  @Select(
      """
        select
            id,
            session_id,
            agent_id,
            agent_name,
            title,
            current_head_event_id,
            gmt_create as create_time,
            gmt_modified as update_time
        from agent_session
        where session_id = #{sessionId}
        """)
  @ResultMap("agentSessionResultMap")
  AgentSessionDO getBySessionId(@Param("sessionId") String sessionId);

  @Select(
      """
        select
            id,
            session_id,
            agent_id,
            agent_name,
            title,
            current_head_event_id,
            gmt_create as create_time,
            gmt_modified as update_time
        from agent_session
        where session_id = #{sessionId}
        for update
        """)
  @ResultMap("agentSessionResultMap")
  AgentSessionDO getBySessionIdForUpdate(@Param("sessionId") String sessionId);

  @Update(
      """
        update agent_session
        set
            title = #{title},
            gmt_modified = #{updateTime}
        where session_id = #{sessionId}
        """)
  int updateTitleBySessionId(
      @Param("sessionId") String sessionId,
      @Param("title") String title,
      @Param("updateTime") LocalDateTime updateTime);

  @Delete("delete from agent_session where session_id = #{sessionId}")
  int deleteBySessionId(@Param("sessionId") String sessionId);

  @Update(
      """
        update agent_session
        set
            current_head_event_id = #{currentHeadEventId},
            gmt_modified = #{updateTime}
        where session_id = #{sessionId}
          and current_head_event_id = #{expectedCurrentHeadEventId}
        """)
  int compareAndSetCurrentHeadEventId(
      @Param("sessionId") String sessionId,
      @Param("expectedCurrentHeadEventId") String expectedCurrentHeadEventId,
      @Param("currentHeadEventId") String currentHeadEventId,
      @Param("updateTime") LocalDateTime updateTime);
}
