package fun.fengwk.kkstudio.project.repo.impl.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * project 表组 {@link ResultSet} 行读取原语：统一 UUID、可空数值与 {@code timestamptz(3)} 的双向映射。
 *
 * <p>时间戳读写沿用驱动真实支持的 {@link Timestamp}：pgjdbc 以 JVM 默认时区作为会话时区，写入与回读得到同一绝对时刻，毫秒精度由列类型保证。
 */
final class ProjectRows {

  private ProjectRows() {}

  static UUID uuid(ResultSet resultSet, String column) throws SQLException {
    return resultSet.getObject(column, UUID.class);
  }

  static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
    return resultSet.getObject(column, Long.class);
  }

  static Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
    return resultSet.getObject(column, Integer.class);
  }

  static Boolean nullableBoolean(ResultSet resultSet, String column) throws SQLException {
    return resultSet.getObject(column, Boolean.class);
  }

  static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
    Timestamp value = resultSet.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }
}
