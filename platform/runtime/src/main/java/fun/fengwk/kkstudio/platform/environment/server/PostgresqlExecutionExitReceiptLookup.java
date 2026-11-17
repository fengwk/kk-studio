package fun.fengwk.kkstudio.platform.environment.server;

import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.server.ExecutionExitReceiptLookup;

import java.util.Objects;
import java.util.UUID;

/** Reads durable remote exit acknowledgements directly from the platform database. */
public final class PostgresqlExecutionExitReceiptLookup implements ExecutionExitReceiptLookup {

  private static final String EXISTS_SQL =
      """
      select exists (
          select 1
            from harness_execution_exit
           where execution_id = ?
             and environment_id = ?
             and kind = 'REMOTE'
             and state = 'REMOTE_ACKNOWLEDGED'
      )
      """;

  private final JdbcTemplate jdbcTemplate;

  public PostgresqlExecutionExitReceiptLookup(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  @Override
  public boolean isAcknowledged(EnvironmentId environmentId, UUID remoteExecutionId) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(remoteExecutionId, "remoteExecutionId");
    return jdbcTemplate
        .queryForObject(EXISTS_SQL, Boolean.class, remoteExecutionId, environmentId.value())
        .booleanValue();
  }
}
