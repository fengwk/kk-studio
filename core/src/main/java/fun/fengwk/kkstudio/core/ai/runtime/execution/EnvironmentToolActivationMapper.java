package fun.fengwk.kkstudio.core.ai.runtime.execution;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;

/** Environment Tool FIFO 激活的专用 SQL 边界。 */
@Mapper
public interface EnvironmentToolActivationMapper extends BaseMapper {

  @Update(
      """
      with oldest_member as materialized (
          select invocation.id, invocation.status
          from harness_tool_invocation invocation
          join harness_execution_activation candidate
            on candidate.target_kind = 'TOOL_INVOCATION'
           and candidate.target_id = invocation.id
          where candidate.environment_name = #{environmentName}
            and invocation.environment_name = #{environmentName}
            and invocation.status in (
                'QUEUED', 'RUNNING', 'RETRY_WAIT', 'WAITING_INTERACTION'
            )
          order by invocation.created_at, invocation.assistant_entry_id,
                   invocation.ordinal, invocation.id
          limit 1
      ), oldest as (
          select activation.target_id, oldest_member.status
          from harness_execution_activation activation
          join oldest_member on oldest_member.id = activation.target_id
          where activation.target_kind = 'TOOL_INVOCATION'
          for update of activation skip locked
      )
      update harness_execution_activation activation
      set activation_state = 'SCHEDULED',
          wake_at = #{wakeAt}
      from oldest
      where activation.target_kind = 'TOOL_INVOCATION'
        and activation.target_id = oldest.target_id
        and oldest.status = 'QUEUED'
        and (
            activation.activation_state = 'PARKED'
            or activation.wake_at > #{wakeAt}
        )
      """)
  int activateOldestTool(
      @Param("environmentName") String environmentName, @Param("wakeAt") OffsetDateTime wakeAt);
}
