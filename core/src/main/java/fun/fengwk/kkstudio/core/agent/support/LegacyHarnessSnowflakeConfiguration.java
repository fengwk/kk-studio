package fun.fengwk.kkstudio.core.agent.support;

import fun.fengwk.convention4j.common.idgen.snowflakes.FixedWorkerIdClient;
import fun.fengwk.convention4j.common.idgen.snowflakes.WorkerIdClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps the temporary legacy Harness Snowflake bridge on its explicitly configured fixed worker.
 *
 * <p>Adding Spring Data Redis makes convention4j's Redis worker-id configuration eligible. When
 * {@code convention.snowflake-id.worker-id} is present, this user configuration registers the fixed
 * client before deferred auto-configuration so Redis availability cannot override the explicit
 * value. Delete this configuration together with {@link AgentIdGenerator} after the remaining
 * Harness durable IDs move to PostgreSQL sequences.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "convention.snowflake-id",
    name = {"initial-timestamp", "worker-id"})
public class LegacyHarnessSnowflakeConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public WorkerIdClient legacyHarnessFixedWorkerIdClient(
      @Value("${convention.snowflake-id.worker-id}") long workerId) {
    return new FixedWorkerIdClient(workerId);
  }
}
