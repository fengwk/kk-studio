package fun.fengwk.kkstudio.platform.environment.update;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link EnvironmentUpdateRepository} 的 PostgreSQL 实现。
 *
 * <p>准入的原子性来自 {@code uk_environment_update_active} 部分唯一索引（活动阶段每个 Environment 至多一行）；并发插入只有一行成功，
 * 冲突收敛为 {@link DuplicateKeyException} 并返回 false。阶段推进是带来源集合的条件更新。
 */
@Repository
public class PostgresqlEnvironmentUpdateRepository implements EnvironmentUpdateRepository {

  private static final String COLUMNS =
      "operation_id, environment_id, target_version, phase, error, created_at, updated_at";

  private static final String FIND_SQL =
      "select " + COLUMNS + " from environment_update_operation where operation_id = ?";

  private static final String FIND_ACTIVE_SQL =
      "select "
          + COLUMNS
          + " from environment_update_operation where environment_id = ?"
          + " and phase in ('PENDING','RUNNING','PREPARED')";

  private static final String FIND_LATEST_SQL =
      "select "
          + COLUMNS
          + " from environment_update_operation where environment_id = ?"
          + " order by created_at desc, operation_id desc limit 1";

  private static final String LIST_SQL =
      "select "
          + COLUMNS
          + " from environment_update_operation where environment_id = ?"
          + " order by created_at desc, operation_id desc limit ?";

  private static final String INSERT_SQL =
      "insert into environment_update_operation"
          + " (operation_id, environment_id, target_version, phase, error, created_at, updated_at)"
          + " values (?, ?, ?, ?, ?, ?, ?)";

  private static final String ADVANCE_SQL =
      "update environment_update_operation set phase = ?, error = ?,"
          + " updated_at = statement_timestamp()"
          + " where operation_id = ? and phase in (%s)";

  private final JdbcTemplate jdbcTemplate;

  public PostgresqlEnvironmentUpdateRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  @Override
  public Optional<EnvironmentUpdateOperation> find(String operationId) {
    List<EnvironmentUpdateOperation> rows = jdbcTemplate.query(FIND_SQL, ROW_MAPPER, operationId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<EnvironmentUpdateOperation> findActive(EnvironmentId environmentId) {
    List<EnvironmentUpdateOperation> rows =
        jdbcTemplate.query(FIND_ACTIVE_SQL, ROW_MAPPER, environmentId.value());
    return rows.stream().findFirst();
  }

  @Override
  public Optional<EnvironmentUpdateOperation> findLatest(EnvironmentId environmentId) {
    List<EnvironmentUpdateOperation> rows =
        jdbcTemplate.query(FIND_LATEST_SQL, ROW_MAPPER, environmentId.value());
    return rows.stream().findFirst();
  }

  @Override
  public List<EnvironmentUpdateOperation> list(EnvironmentId environmentId, int limit) {
    if (limit <= 0) {
      return List.of();
    }
    return jdbcTemplate.query(LIST_SQL, ROW_MAPPER, environmentId.value(), limit);
  }

  @Override
  public boolean insertPending(EnvironmentUpdateOperation operation) {
    try {
      return jdbcTemplate.update(
              INSERT_SQL,
              UUID.fromString(operation.operationId()),
              operation.environmentId().value(),
              operation.targetVersion(),
              operation.phase().name(),
              operation.error(),
              OffsetDateTime.ofInstant(operation.createdAt(), ZoneOffset.UTC),
              OffsetDateTime.ofInstant(operation.updatedAt(), ZoneOffset.UTC))
          > 0;
    } catch (DuplicateKeyException error) {
      return false;
    }
  }

  @Override
  public boolean advance(
      String operationId,
      EnvironmentUpdatePhase next,
      String error,
      Set<EnvironmentUpdatePhase> allowedFrom) {
    if (allowedFrom.isEmpty()) {
      return false;
    }
    String placeholders = allowedFrom.stream().map(ignored -> "?").collect(Collectors.joining(","));
    String sql = String.format(ADVANCE_SQL, placeholders);
    List<Object> args = new ArrayList<>();
    args.add(next.name());
    args.add(error);
    args.add(UUID.fromString(operationId));
    allowedFrom.forEach(phase -> args.add(phase.name()));
    return jdbcTemplate.update(sql, args.toArray()) > 0;
  }

  private static final RowMapper<EnvironmentUpdateOperation> ROW_MAPPER =
      (ResultSet rs, int rowNum) -> mapRow(rs);

  private static EnvironmentUpdateOperation mapRow(ResultSet rs) throws SQLException {
    return new EnvironmentUpdateOperation(
        rs.getObject("operation_id", UUID.class).toString(),
        EnvironmentId.of(rs.getObject("environment_id", UUID.class)),
        rs.getString("target_version"),
        EnvironmentUpdatePhase.valueOf(rs.getString("phase")),
        rs.getString("error"),
        toInstant(rs.getObject("created_at", OffsetDateTime.class)),
        toInstant(rs.getObject("updated_at", OffsetDateTime.class)));
  }

  private static Instant toInstant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
