package fun.fengwk.kkstudio.core.agent.run.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.kkstudio.core.agent.run.repo.impl.model.AgentRunDO;
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

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.kkstudio.core.agent.run.repo.impl.model.AgentRunDO;
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

/**
 * @author fengwk
 */
@Mapper
public interface AgentRunMapper extends BaseMapper {

  @Insert(
      """
        insert into agent_run (
            id,
            run_id,
            session_id,
            trigger_event_id,
            status,
            gmt_create,
            gmt_modified,
            version
        ) values (
            #{id},
            #{runId},
            #{sessionId},
            #{triggerEventId},
            #{status},
            #{createTime},
            #{updateTime},
            0
        )
        """)
  int insertSelective(AgentRunDO runDO);

  @Select(
      """
        select
            id,
            run_id,
            session_id,
            trigger_event_id,
            status,
            gmt_create as create_time,
            gmt_modified as update_time
        from agent_run
        where session_id = #{sessionId}
        order by id desc
        """)
  @Results(
      id = "agentRunResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "run_id", property = "runId"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "trigger_event_id", property = "triggerEventId"),
        @Result(column = "status", property = "status"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  List<AgentRunDO> listBySessionId(@Param("sessionId") String sessionId);

  @Select(
      """
        select count(1)
        from agent_run
        where session_id = #{sessionId}
          and (status = 'queued' or status = 'running')
        """)
  int countActiveBySessionId(@Param("sessionId") String sessionId);

  @Select(
      """
        select
            id,
            run_id,
            session_id,
            trigger_event_id,
            status,
            gmt_create as create_time,
            gmt_modified as update_time
        from agent_run
        where (status = 'queued' or status = 'running')
          and gmt_modified < #{threshold}
        order by id asc
        """)
  @ResultMap("agentRunResultMap")
  List<AgentRunDO> listStaleRuns(@Param("threshold") LocalDateTime threshold);

  @Select(
      """
        select
            id,
            run_id,
            session_id,
            trigger_event_id,
            status,
            gmt_create as create_time,
            gmt_modified as update_time
        from agent_run
        where run_id = #{runId}
        """)
  @Results(
      id = "agentRunByIdResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "run_id", property = "runId"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "trigger_event_id", property = "triggerEventId"),
        @Result(column = "status", property = "status"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  AgentRunDO getByRunId(@Param("runId") String runId);

  @Update(
      """
        update agent_run
        set
            status = #{status},
            gmt_modified = #{updateTime}
        where run_id = #{runId}
          and status = #{expectedStatus}
        """)
  int updateStatus(
      @Param("runId") String runId,
      @Param("expectedStatus") String expectedStatus,
      @Param("status") String status,
      @Param("updateTime") LocalDateTime updateTime);

  @Update(
      """
        update agent_run
        set
            status = #{status},
            gmt_modified = #{updateTime}
        where run_id = #{runId}
          and (status = 'queued' or status = 'running')
        """)
  int markFailedFromActive(
      @Param("runId") String runId,
      @Param("status") String status,
      @Param("updateTime") LocalDateTime updateTime);
}
