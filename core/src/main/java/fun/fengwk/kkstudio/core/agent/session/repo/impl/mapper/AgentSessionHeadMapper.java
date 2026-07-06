package fun.fengwk.kkstudio.core.agent.session.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.kkstudio.core.agent.session.repo.impl.model.AgentSessionHeadDO;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.kkstudio.core.agent.session.repo.impl.model.AgentSessionHeadDO;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * @author fengwk
 */
@Mapper
public interface AgentSessionHeadMapper extends BaseMapper {

  @Insert(
      """
        insert into agent_session_head (
            id,
            head_id,
            session_id,
            head_name,
            head_event_id,
            version
        ) values (
            #{id},
            #{headId},
            #{sessionId},
            #{headName},
            #{headEventId},
            0
        )
        """)
  int insertSelective(AgentSessionHeadDO sessionHeadDO);

  @Select(
      """
        select
            id,
            head_id,
            session_id,
            head_name,
            head_event_id
        from agent_session_head
        where session_id = #{sessionId}
        order by id asc
        """)
  @Results(
      id = "agentSessionHeadResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "head_id", property = "headId"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "head_name", property = "headName"),
        @Result(column = "head_event_id", property = "headEventId")
      })
  List<AgentSessionHeadDO> listBySessionId(@Param("sessionId") String sessionId);

  @Update(
      """
        update agent_session_head
        set head_event_id = #{headEventId}
        where session_id = #{sessionId}
          and head_name = #{headName}
        """)
  int updateHeadEventId(
      @Param("sessionId") String sessionId,
      @Param("headName") String headName,
      @Param("headEventId") String headEventId);
}
