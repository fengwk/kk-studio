package fun.fengwk.kkstudio.core.harness.retry.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import fun.fengwk.kkstudio.core.harness.retry.HarnessRetryPolicyDO;

import java.time.OffsetDateTime;

/** 单例 Harness retry policy 的最小持久化映射。 */
@Mapper
public interface HarnessRetryPolicyMapper extends BaseMapper {
  @Select(
      """
      select id,
             max_retries as maxRetries,
             backoff_strategy as backoffStrategy,
             base_delay_millis as baseDelayMillis,
             max_delay_millis as maxDelayMillis,
             created_at as createTime,
             updated_at as updateTime
      from harness_retry_policy
      where id = 1
      """)
  HarnessRetryPolicyDO find();

  @Select(
      """
      select id,
             max_retries as maxRetries,
             backoff_strategy as backoffStrategy,
             base_delay_millis as baseDelayMillis,
             max_delay_millis as maxDelayMillis,
             created_at as createTime,
             updated_at as updateTime
      from harness_retry_policy
      where id = 1
      for update
      """)
  HarnessRetryPolicyDO findForUpdate();

  @Insert(
      """
      insert into harness_retry_policy (
          id, max_retries, backoff_strategy, base_delay_millis, max_delay_millis,
          created_at, updated_at
      ) values (
          1, #{maxRetries}, #{backoffStrategy}, #{baseDelayMillis}, #{maxDelayMillis},
          #{createTime}, #{updateTime}
      )
      """)
  int insert(HarnessRetryPolicyDO policy);

  @Update(
      """
      update harness_retry_policy
      set max_retries = #{maxRetries},
          backoff_strategy = #{backoffStrategy},
          base_delay_millis = #{baseDelayMillis},
          max_delay_millis = #{maxDelayMillis},
          updated_at = #{updateTime}
      where id = 1
      """)
  int update(
      @Param("maxRetries") int maxRetries,
      @Param("backoffStrategy") String backoffStrategy,
      @Param("baseDelayMillis") long baseDelayMillis,
      @Param("maxDelayMillis") long maxDelayMillis,
      @Param("updateTime") OffsetDateTime updateTime);
}
