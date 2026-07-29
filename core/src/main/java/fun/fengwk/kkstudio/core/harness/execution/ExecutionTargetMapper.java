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
 * Narrow MyBatis mapper for {@code harness_execution_target}. All reads and writes are bounded to
 * the columns this package owns; no JOINs, no domain fan-out.
 *
 * <p>Methods that take a {@code routeKeys} parameter expand the values through a MyBatis {@code
 * foreach}. Empty or {@code null} arrays select only {@code route_key IS NULL} rows.
 */
@Mapper
public interface ExecutionTargetMapper extends BaseMapper {

  @Insert(
      """
      insert into harness_execution_target
          (target_kind, target_id, route_key, available_at)
      values
          (#{kind}, #{id}, #{routeKey}, #{availableAt})
      on conflict (target_kind, target_id) do update set
          route_key = excluded.route_key,
          available_at = excluded.available_at
      where excluded.available_at < harness_execution_target.available_at
      """)
  int schedule(
      @Param("kind") String kind,
      @Param("id") long id,
      @Param("routeKey") String routeKey,
      @Param("availableAt") OffsetDateTime availableAt);

  @Select(
      """
      select target_kind, target_id, route_key, available_at
      from harness_execution_target
      where target_kind = #{kind} and target_id = #{id} and available_at <= #{now}
      for update
      """)
  @Results(
      id = "executionTargetRowLockResultMap",
      value = {
        @Result(column = "target_kind", property = "targetKind"),
        @Result(column = "target_id", property = "targetId"),
        @Result(column = "route_key", property = "routeKey"),
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
      with oldest as (
          select target_id
          from harness_execution_target
          where target_kind = 'TOOL_INVOCATION' and route_key = #{routeKey}
          order by available_at, target_id
          limit 1
          for update skip locked
      )
      update harness_execution_target t
      set available_at = #{availableAt}
      from oldest
      where t.target_kind = 'TOOL_INVOCATION'
        and t.target_id = oldest.target_id
        and t.available_at > #{availableAt}
      """)
  int activateOldestEnvironment(
      @Param("routeKey") String routeKey, @Param("availableAt") OffsetDateTime availableAt);

  @Select(
      "<script>"
          + "<choose>"
          + "  <when test='routeKeys == null or routeKeys.length == 0'>"
          + "    select target_kind, target_id, route_key, available_at"
          + "    from harness_execution_target"
          + "    where available_at &lt;= #{now}"
          + "      and route_key is null"
          + "    order by available_at, target_kind, target_id"
          + "    limit #{limit}"
          + "  </when>"
          + "  <otherwise>"
          + "    select target_kind, target_id, route_key, available_at"
          + "    from harness_execution_target"
          + "    where available_at &lt;= #{now}"
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
          + "    select min(available_at) from harness_execution_target where route_key is null"
          + "  </when>"
          + "  <otherwise>"
          + "    select min(available_at) from harness_execution_target"
          + "    where route_key is null or route_key in "
          + "      <foreach collection='routeKeys' item='routeKey' open='(' separator=',' close=')'>"
          + "        #{routeKey}"
          + "      </foreach>"
          + "  </otherwise>"
          + "</choose>"
          + "</script>")
  OffsetDateTime findNearestEligibleAvailableAt(@Param("routeKeys") String[] routeKeys);

  @Select(
      "select target_kind, target_id, route_key, available_at "
          + "from harness_execution_target order by available_at, target_kind, target_id")
  @Results(
      id = "executionTargetRowAllResultMap",
      value = {
        @Result(column = "target_kind", property = "targetKind"),
        @Result(column = "target_id", property = "targetId"),
        @Result(column = "route_key", property = "routeKey"),
        @Result(column = "available_at", property = "availableAt")
      })
  List<ExecutionTargetDO> findAll();
}
