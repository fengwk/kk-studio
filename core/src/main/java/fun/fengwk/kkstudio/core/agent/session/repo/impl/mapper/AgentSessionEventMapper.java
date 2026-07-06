package fun.fengwk.kkstudio.core.agent.session.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.core.agent.session.repo.impl.model.AgentSessionEventDO;

import java.util.List;

/**
 * @author fengwk
 */
@Mapper
public interface AgentSessionEventMapper extends BaseMapper {

  @Insert(
      """
        insert into agent_session_event (
            id,
            event_id,
            session_id,
            parent_event_id,
            run_id,
            event_type,
            payload_type,
            payload_json,
            gmt_create,
            version
        ) values (
            #{id},
            #{eventId},
            #{sessionId},
            #{parentEventId},
            #{runId},
            #{eventType},
            #{payloadType},
            #{payloadJson},
            #{createTime},
            0
        )
        """)
  int insertSelective(AgentSessionEventDO sessionEventDO);

  @Select(
      """
        select
            id,
            event_id,
            session_id,
            parent_event_id,
            run_id,
            event_type,
            payload_type,
            payload_json,
            gmt_create as create_time
        from agent_session_event
        where session_id = #{sessionId}
        order by id asc
        """)
  @Results(
      id = "agentSessionEventResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "event_id", property = "eventId"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "parent_event_id", property = "parentEventId"),
        @Result(column = "run_id", property = "runId"),
        @Result(column = "event_type", property = "eventType"),
        @Result(column = "payload_type", property = "payloadType"),
        @Result(column = "payload_json", property = "payloadJson"),
        @Result(column = "create_time", property = "createTime")
      })
  List<AgentSessionEventDO> listBySessionId(@Param("sessionId") String sessionId);

  @Select(
      """
        select
            id,
            event_id,
            session_id,
            parent_event_id,
            run_id,
            event_type,
            payload_type,
            payload_json,
            gmt_create as create_time
        from agent_session_event
        where session_id = #{sessionId}
          and id > (
            select id
            from agent_session_event
            where session_id = #{sessionId}
              and event_id = #{afterEventId}
          )
        order by id asc
        """)
  @ResultMap("agentSessionEventResultMap")
  List<AgentSessionEventDO> listBySessionIdAfterEventId(
      @Param("sessionId") String sessionId, @Param("afterEventId") String afterEventId);
}
