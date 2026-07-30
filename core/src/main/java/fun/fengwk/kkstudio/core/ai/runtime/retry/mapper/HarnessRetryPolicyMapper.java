package fun.fengwk.kkstudio.core.ai.runtime.retry.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.core.ai.runtime.retry.HarnessRetryPolicyDO;

/** 单例 Harness retry policy 的最小持久化映射。 */
@Mapper
public interface HarnessRetryPolicyMapper extends BaseMapper {

  @Select(
      """
      select id,
             max_retries as maxRetries,
             backoff_strategy as backoffStrategy,
             base_delay_millis as baseDelayMillis,
             max_delay_millis as maxDelayMillis
      from harness_retry_policy
      where id = 1
      """)
  HarnessRetryPolicyDO find();

  /**
   * 单例 id=1 的原子 upsert。{@code insert} 与 {@code update} 在同一语句内合并，不读取当前行；失败原因 （例如 constraint 违反）会直接以
   * JDBC 异常抛出。
   */
  @Insert(
      """
      insert into harness_retry_policy (
          id, max_retries, backoff_strategy, base_delay_millis, max_delay_millis
      ) values (
          1, #{maxRetries}, #{backoffStrategy}, #{baseDelayMillis}, #{maxDelayMillis}
      )
      on conflict (id) do update set
          max_retries = excluded.max_retries,
          backoff_strategy = excluded.backoff_strategy,
          base_delay_millis = excluded.base_delay_millis,
          max_delay_millis = excluded.max_delay_millis
      """)
  int upsert(
      @Param("maxRetries") int maxRetries,
      @Param("backoffStrategy") String backoffStrategy,
      @Param("baseDelayMillis") long baseDelayMillis,
      @Param("maxDelayMillis") long maxDelayMillis);
}
