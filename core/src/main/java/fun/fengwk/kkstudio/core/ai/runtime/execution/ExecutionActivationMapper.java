package fun.fengwk.kkstudio.core.ai.runtime.execution;

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

/** harness_execution_activation 的通用 MyBatis 映射边界。 */
@Mapper
public interface ExecutionActivationMapper extends BaseMapper {

  @Insert(
      """
      insert into harness_execution_activation
          (target_kind, target_id, environment_name, activation_state, wake_at)
      values
          (#{kind}, #{id}, #{environmentName}, 'SCHEDULED', #{wakeAt})
      on conflict (target_kind, target_id) do update set
          wake_at = excluded.wake_at
      where harness_execution_activation.activation_state = 'SCHEDULED'
        and harness_execution_activation.environment_name
            is not distinct from excluded.environment_name
        and excluded.wake_at < harness_execution_activation.wake_at
      """)
  int schedule(
      @Param("kind") String kind,
      @Param("id") long id,
      @Param("environmentName") String environmentName,
      @Param("wakeAt") OffsetDateTime wakeAt);

  @Insert(
      """
      insert into harness_execution_activation
          (target_kind, target_id, environment_name, activation_state, wake_at)
      values
          (#{kind}, #{id}, #{environmentName}, 'PARKED', #{wakeAt})
      on conflict (target_kind, target_id) do nothing
      """)
  int park(
      @Param("kind") String kind,
      @Param("id") long id,
      @Param("environmentName") String environmentName,
      @Param("wakeAt") OffsetDateTime wakeAt);

  @Select(
      """
      select target_kind, target_id, environment_name, activation_state, wake_at
      from harness_execution_activation
      where target_kind = #{kind} and target_id = #{id}
      for update
      """)
  @Results(
      id = "executionActivationLockResultMap",
      value = {
        @Result(column = "target_kind", property = "targetKind"),
        @Result(column = "target_id", property = "targetId"),
        @Result(column = "environment_name", property = "environmentName"),
        @Result(column = "activation_state", property = "activationState"),
        @Result(column = "wake_at", property = "wakeAt")
      })
  ExecutionActivationDO lockForUpdate(@Param("kind") String kind, @Param("id") long id);

  @Select(
      """
      select target_kind, target_id, environment_name, activation_state, wake_at
      from harness_execution_activation
      where target_kind = #{kind} and target_id = #{id}
        and activation_state = 'SCHEDULED'
        and wake_at <= #{now}
      for update
      """)
  @Results(
      id = "executionActivationDueLockResultMap",
      value = {
        @Result(column = "target_kind", property = "targetKind"),
        @Result(column = "target_id", property = "targetId"),
        @Result(column = "environment_name", property = "environmentName"),
        @Result(column = "activation_state", property = "activationState"),
        @Result(column = "wake_at", property = "wakeAt")
      })
  ExecutionActivationDO lockDueForUpdate(
      @Param("kind") String kind, @Param("id") long id, @Param("now") OffsetDateTime now);

  @Update(
      """
      update harness_execution_activation
      set wake_at = #{wakeAt}
      where target_kind = #{kind} and target_id = #{id}
      """)
  int rescheduleLocked(
      @Param("kind") String kind, @Param("id") long id, @Param("wakeAt") OffsetDateTime wakeAt);

  @Update(
      """
      update harness_execution_activation
      set activation_state = 'PARKED',
          wake_at = #{wakeAt}
      where target_kind = #{kind} and target_id = #{id}
      """)
  int parkLocked(
      @Param("kind") String kind, @Param("id") long id, @Param("wakeAt") OffsetDateTime wakeAt);

  @Update(
      """
      update harness_execution_activation
      set activation_state = 'SCHEDULED',
          wake_at = #{wakeAt}
      where target_kind = #{kind} and target_id = #{id}
      """)
  int activateLocked(
      @Param("kind") String kind, @Param("id") long id, @Param("wakeAt") OffsetDateTime wakeAt);

  @Delete(
      """
      delete from harness_execution_activation
      where target_kind = #{kind} and target_id = #{id}
      """)
  int deleteByPk(@Param("kind") String kind, @Param("id") long id);

  @Select(
      "<script>"
          + "<choose>"
          + "  <when test='environmentNames == null or environmentNames.length == 0'>"
          + "    select target_kind, target_id, environment_name, activation_state, wake_at"
          + "    from harness_execution_activation"
          + "    where activation_state = 'SCHEDULED'"
          + "      and wake_at &lt;= #{now}"
          + "      and environment_name is null"
          + "    order by wake_at, target_kind, target_id"
          + "    limit #{limit}"
          + "  </when>"
          + "  <otherwise>"
          + "    select target_kind, target_id, environment_name, activation_state, wake_at"
          + "    from harness_execution_activation"
          + "    where activation_state = 'SCHEDULED'"
          + "      and wake_at &lt;= #{now}"
          + "      and (environment_name is null or environment_name in "
          + "        <foreach collection='environmentNames' item='environmentName' open='(' separator=',' close=')'>"
          + "          #{environmentName}"
          + "        </foreach>"
          + "      )"
          + "    order by wake_at, target_kind, target_id"
          + "    limit #{limit}"
          + "  </otherwise>"
          + "</choose>"
          + "</script>")
  @Results(
      id = "executionActivationResultMap",
      value = {
        @Result(column = "target_kind", property = "targetKind"),
        @Result(column = "target_id", property = "targetId"),
        @Result(column = "environment_name", property = "environmentName"),
        @Result(column = "activation_state", property = "activationState"),
        @Result(column = "wake_at", property = "wakeAt")
      })
  List<ExecutionActivationDO> findEligibleDue(
      @Param("now") OffsetDateTime now,
      @Param("environmentNames") String[] environmentNames,
      @Param("limit") int limit);

  @Select(
      "<script>"
          + "<choose>"
          + "  <when test='environmentNames == null or environmentNames.length == 0'>"
          + "    select min(wake_at) from harness_execution_activation"
          + "    where activation_state = 'SCHEDULED' and environment_name is null"
          + "  </when>"
          + "  <otherwise>"
          + "    select min(wake_at) from harness_execution_activation"
          + "    where activation_state = 'SCHEDULED'"
          + "      and (environment_name is null or environment_name in "
          + "        <foreach collection='environmentNames' item='environmentName' open='(' separator=',' close=')'>"
          + "          #{environmentName}"
          + "        </foreach>"
          + "      )"
          + "  </otherwise>"
          + "</choose>"
          + "</script>")
  OffsetDateTime findNearestEligibleWakeAt(@Param("environmentNames") String[] environmentNames);

  @Select(
      "select target_kind, target_id, environment_name, activation_state, wake_at "
          + "from harness_execution_activation order by wake_at, target_kind, target_id")
  @Results(
      id = "executionActivationAllResultMap",
      value = {
        @Result(column = "target_kind", property = "targetKind"),
        @Result(column = "target_id", property = "targetId"),
        @Result(column = "environment_name", property = "environmentName"),
        @Result(column = "activation_state", property = "activationState"),
        @Result(column = "wake_at", property = "wakeAt")
      })
  List<ExecutionActivationDO> findAll();
}
