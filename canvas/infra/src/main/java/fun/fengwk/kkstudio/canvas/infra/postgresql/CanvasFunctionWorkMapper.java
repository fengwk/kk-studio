package fun.fengwk.kkstudio.canvas.infra.postgresql;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.UUID;

/** {@code canvas_function_run} durable claim/lease 的原子 SQL 入口。 */
@Mapper
public interface CanvasFunctionWorkMapper extends BaseMapper {

  @Results(
      id = "claimedCanvasFunctionRunMap",
      value = {
        @Result(column = "node_id", property = "nodeId"),
        @Result(column = "request_id", property = "requestId"),
        @Result(column = "status", property = "status"),
        @Result(column = "attempt", property = "attempt"),
        @Result(column = "available_at", property = "availableAt"),
        @Result(column = "lease_token", property = "leaseToken"),
        @Result(column = "lease_until", property = "leaseUntil"),
        @Result(column = "state_json", property = "stateJson"),
        @Result(column = "error", property = "error"),
        @Result(column = "updated_at", property = "updatedAt"),
        @Result(column = "created_at", property = "createdAt")
      })
  @Select(
      """
      with candidate as (
          select node_id
          from canvas_function_run
          where (status = 'READY' and available_at <= #{now})
             or (status = 'RUNNING' and lease_until <= #{now})
          order by
              case when status = 'READY' then available_at else lease_until end,
              created_at,
              node_id
          for update skip locked
          limit 1
      )
      update canvas_function_run run
      set status = 'RUNNING',
          attempt = run.attempt + 1,
          available_at = null,
          lease_token = #{leaseToken},
          lease_until = #{leaseUntil},
          updated_at = #{now}
      from candidate
      where run.node_id = candidate.node_id
      returning run.node_id, run.request_id, run.status, run.attempt, run.available_at,
                run.lease_token, run.lease_until, run.state_json, run.error, run.updated_at,
                run.created_at
      """)
  CanvasFunctionRunDO claimNext(
      @Param("now") OffsetDateTime now,
      @Param("leaseToken") String leaseToken,
      @Param("leaseUntil") OffsetDateTime leaseUntil);

  @Update(
      """
      update canvas_function_run
      set lease_until = #{leaseUntil}
      where node_id = #{nodeId}
        and request_id = #{requestId}
        and status = 'RUNNING'
        and lease_token = #{leaseToken}
        and lease_until > #{now}
        and #{leaseUntil} > lease_until
      """)
  int renew(
      @Param("nodeId") UUID nodeId,
      @Param("requestId") UUID requestId,
      @Param("leaseToken") String leaseToken,
      @Param("now") OffsetDateTime now,
      @Param("leaseUntil") OffsetDateTime leaseUntil);

  @Update(
      """
      update canvas_function_run
      set status = 'READY',
          available_at = #{availableAt},
          lease_token = null,
          lease_until = null,
          updated_at = #{now}
      where node_id = #{nodeId}
        and request_id = #{requestId}
        and status = 'RUNNING'
        and lease_token = #{leaseToken}
        and lease_until > #{now}
      """)
  int reschedule(
      @Param("nodeId") UUID nodeId,
      @Param("requestId") UUID requestId,
      @Param("leaseToken") String leaseToken,
      @Param("now") OffsetDateTime now,
      @Param("availableAt") OffsetDateTime availableAt);

  @Select(
      """
      select count(*)
      from canvas_function_run
      where node_id = #{nodeId}
        and request_id = #{requestId}
        and status = 'RUNNING'
        and lease_token = #{leaseToken}
        and lease_until > #{now}
      """)
  int countOwned(
      @Param("nodeId") UUID nodeId,
      @Param("requestId") UUID requestId,
      @Param("leaseToken") String leaseToken,
      @Param("now") OffsetDateTime now);
}
