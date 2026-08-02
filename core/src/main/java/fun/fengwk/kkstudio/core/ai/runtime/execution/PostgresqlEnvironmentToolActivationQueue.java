package fun.fengwk.kkstudio.core.ai.runtime.execution;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** PostgreSQL Environment Tool FIFO 激活适配器。 */
@Repository
public class PostgresqlEnvironmentToolActivationQueue implements EnvironmentToolActivationQueue {

  private final EnvironmentToolActivationMapper mapper;

  public PostgresqlEnvironmentToolActivationQueue(EnvironmentToolActivationMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public boolean activateOldestTool(String environmentName, Instant wakeAt) {
    Objects.requireNonNull(environmentName, "environmentName");
    Objects.requireNonNull(wakeAt, "wakeAt");
    if (environmentName.isBlank()) {
      throw new IllegalArgumentException("environmentName must not be blank");
    }
    if (!environmentName.equals(environmentName.strip())) {
      throw new IllegalArgumentException(
          "environmentName must not have leading or trailing whitespace");
    }
    if (environmentName.length() > 128) {
      throw new IllegalArgumentException("environmentName must be <= 128 characters");
    }
    return mapper.activateOldestTool(environmentName, offset(wakeAt)) > 0;
  }

  private static OffsetDateTime offset(Instant value) {
    return OffsetDateTime.ofInstant(
        Objects.requireNonNull(value, "value").truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
  }
}
