package fun.fengwk.kkstudio.core.persistence;

import java.sql.SQLException;

/** 按 SQLSTATE 分类 PostgreSQL 完整性失败，不依赖驱动特有类型。 */
public final class PostgresqlIntegrityViolationClassifier {

  private static final String FOREIGN_KEY_VIOLATION = "23503";

  private PostgresqlIntegrityViolationClassifier() {}

  /** 返回 throwable 的 cause chain 中是否包含 PostgreSQL 外键违例。 */
  public static boolean isForeignKeyViolation(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof SQLException sqlException
          && FOREIGN_KEY_VIOLATION.equals(sqlException.getSQLState())) {
        return true;
      }
    }
    return false;
  }
}
