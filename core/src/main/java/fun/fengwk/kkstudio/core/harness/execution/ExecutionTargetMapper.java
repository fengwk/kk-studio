package fun.fengwk.kkstudio.core.harness.execution;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Narrow MyBatis mapper for {@code harness_execution_target}. Target-only reads and writes are
 * bounded to the columns this package owns; FIFO environment activation additionally joins the
 * durable Tool invocation table to order route heads by domain creation order.
 *
 * <p>Methods that take a {@code routeKeys} parameter expand the values through a MyBatis {@code
 * foreach}. Empty or {@code null} arrays select only {@code route_key IS NULL} rows.
 */
@Mapper
public interface ExecutionTargetMapper extends BaseMapper {

  @Insert(
      """
      insert into harness_execution_target
          (target_kind, target_id, route_key, dispatch_enabled, available_at)
      values
          (#{kind}, #{id}, #{routeKey}, true, #{availableAt})
      on conflict (target_kind, target_id) do update set
          route_key = excluded.route_key,
          dispatch_enabled = true,
          available_at = excluded.available_at
      where not harness_execution_target.dispatch_enabled
         or excluded.available_at < harness_execution_target.available_at
      """)
  int schedule(
      @Param("kind") String kind,
      @Param("id") long id,
      @Param("routeKey") String routeKey,
      @Param("availableAt") OffsetDateTime availableAt);

  @Insert(
      """
      insert into harness_execution_target
          (target_kind, target_id, route_key, dispatch_enabled, available_at)
      values
          (#{kind}, #{id}, #{routeKey}, false, #{availableAt})
      on conflict (target_kind, target_id) do nothing
      """)
  int park(
      @Param("kind") String kind,
      @Param("id") long id,
      @Param("routeKey") String routeKey,
      @Param("availableAt") OffsetDateTime availableAt);

  @Select(
      """
      select target_kind, target_id, route_key, dispatch_enabled, available_at
      from harness_execution_target
      where target_kind = #{kind} and target_id = #{id}
      for update
      """)
  @Results(
      id = "executionTargetRowLockResultMap",
      value = {
        @Result(column = "target_kind", property = "targetKind"),
        @Result(column = "target_id", property = "targetId"),
        @Result(column = "route_key", property = "routeKey"),
        @Result(column = "dispatch_enabled", property = "dispatchEnabled"),
        @Result(column = "available_at", property = "availableAt")
      })
  ExecutionTargetDO lockForUpdate(@Param("kind") String kind, @Param("id") long id);

  @Select(
      """
      select target_kind, target_id, route_key, dispatch_enabled, available_at
      from harness_execution_target
      where target_kind = #{kind} and target_id = #{id}
        and dispatch_enabled
        and available_at <= #{now}
      for update
      """)
  @Results(
      id = "executionTargetRowDueLockResultMap",
      value = {
        @Result(column = "target_kind", property = "targetKind"),
        @Result(column = "target_id", property = "targetId"),
        @Result(column = "route_key", property = "routeKey"),
        @Result(column = "dispatch_enabled", property = "dispatchEnabled"),
        @Result(column = "available_at", property = "availableAt")
      })
  ExecutionTargetDO lockDueForUpdate(
      @Param("kind") String kind, @Param("id") long id, @Param("now") OffsetDateTime now);

  @Update(
      """
      update harness_execution_target
      set route_key = #{routeKey}, available_at = #{availableAt}
      where target_kind = #{kind} and target_id = #{id}
      """)
  int rescheduleLocked(
      @Param("kind") String kind,
      @Param("id") long id,
      @Param("routeKey") String routeKey,
      @Param("availableAt") OffsetDateTime availableAt);

  @Delete(
      """
      delete from harness_execution_target
      where target_kind = #{kind} and target_id = #{id}
      """)
  int deleteByPk(@Param("kind") String kind, @Param("id") long id);

  @Update(
      """
      with oldest_member as materialized (
          select invocation.id, invocation.status
          from harness_tool_invocation invocation
          join harness_execution_target candidate
            on candidate.target_kind = 'TOOL_INVOCATION'
           and candidate.target_id = invocation.id
          where candidate.route_key = #{routeKey}
            and invocation.location = 'ENVIRONMENT'
            and invocation.environment_name = #{routeKey}
            and invocation.status in (
                'QUEUED', 'RUNNING', 'RETRY_WAIT', 'WAITING_INTERACTION'
            )
          order by invocation.created_at, invocation.assistant_entry_id,
                   invocation.ordinal, invocation.id
          limit 1
      ), oldest as (
          select target.target_id, oldest_member.status
          from harness_execution_target target
          join oldest_member on oldest_member.id = target.target_id
          where target.target_kind = 'TOOL_INVOCATION'
          for update of target skip locked
      )
      update harness_execution_target target
      set dispatch_enabled = true,
          available_at = #{availableAt}
      from oldest
      where target.target_kind = 'TOOL_INVOCATION'
        and target.target_id = oldest.target_id
        and oldest.status = 'QUEUED'
        and (not target.dispatch_enabled or target.available_at > #{availableAt})
      """)
  int activateOldestEnvironment(
      @Param("routeKey") String routeKey, @Param("availableAt") OffsetDateTime availableAt);

  @Select(
      "<script>"
          + "<choose>"
          + "  <when test='routeKeys == null or routeKeys.length == 0'>"
          + "    select target_kind, target_id, route_key, dispatch_enabled, available_at"
          + "    from harness_execution_target"
          + "    where dispatch_enabled"
          + "      and available_at &lt;= #{now}"
          + "      and route_key is null"
          + "    order by available_at, target_kind, target_id"
          + "    limit #{limit}"
          + "  </when>"
          + "  <otherwise>"
          + "    select target_kind, target_id, route_key, dispatch_enabled, available_at"
          + "    from harness_execution_target"
          + "    where dispatch_enabled"
          + "      and available_at &lt;= #{now}"
          + "      and (route_key is null or route_key in "
          + "        <foreach collection='routeKeys' item='routeKey' open='(' separator=',' close=')'>"
          + "          #{routeKey}"
          + "        </foreach>"
          + "      )"
          + "    order by available_at, target_kind, target_id"
          + "    limit #{limit}"
          + "  </otherwise>"
          + "</choose>"
          + "</script>")
  @Results(
      id = "executionTargetRowResultMap",
      value = {
        @Result(column = "target_kind", property = "targetKind"),
        @Result(column = "target_id", property = "targetId"),
        @Result(column = "route_key", property = "routeKey"),
        @Result(column = "dispatch_enabled", property = "dispatchEnabled"),
        @Result(column = "available_at", property = "availableAt")
      })
  List<ExecutionTargetDO> findEligibleDue(
      @Param("now") OffsetDateTime now,
      @Param("routeKeys") String[] routeKeys,
      @Param("limit") int limit);

  @Select(
      "<script>"
          + "<choose>"
          + "  <when test='routeKeys == null or routeKeys.length == 0'>"
          + "    select min(available_at) from harness_execution_target"
          + " where dispatch_enabled and route_key is null"
          + "  </when>"
          + "  <otherwise>"
          + "    select min(available_at) from harness_execution_target"
          + "    where dispatch_enabled and (route_key is null or route_key in "
          + "      <foreach collection='routeKeys' item='routeKey' open='(' separator=',' close=')'>"
          + "        #{routeKey}"
          + "      </foreach>"
          + "      )"
          + "  </otherwise>"
          + "</choose>"
          + "</script>")
  OffsetDateTime findNearestEligibleAvailableAt(@Param("routeKeys") String[] routeKeys);

  @Select(
      "select target_kind, target_id, route_key, dispatch_enabled, available_at "
          + "from harness_execution_target order by available_at, target_kind, target_id")
  @Results(
      id = "executionTargetRowAllResultMap",
      value = {
        @Result(column = "target_kind", property = "targetKind"),
        @Result(column = "target_id", property = "targetId"),
        @Result(column = "route_key", property = "routeKey"),
        @Result(column = "dispatch_enabled", property = "dispatchEnabled"),
        @Result(column = "available_at", property = "availableAt")
      })
  List<ExecutionTargetDO> findAll();
}
