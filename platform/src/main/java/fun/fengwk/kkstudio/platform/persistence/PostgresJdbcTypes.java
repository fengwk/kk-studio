package fun.fengwk.kkstudio.platform.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * PostgreSQL {@code timestamptz} 与 {@link Instant} 的显式转换。
 *
 * <p>行映射统一读 {@code OffsetDateTime} 再转 {@code Instant}；参数统一以 UTC {@code OffsetDateTime} 绑定，避免依赖驱动对
 * {@code Instant} 的隐式支持。
 */
public final class PostgresJdbcTypes {

  private PostgresJdbcTypes() {}

  public static Instant toInstant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  public static OffsetDateTime toOffsetDateTime(Instant value) {
    return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
  }
}
