package fun.fengwk.kkstudio.core.persistence;

import java.sql.SQLException;

/**
 * Classifies PostgreSQL integrity failures by SQLSTATE without depending on driver-specific types.
 */
public final class PostgresqlIntegrityViolationClassifier {

  private static final String FOREIGN_KEY_VIOLATION = "23503";

  private PostgresqlIntegrityViolationClassifier() {}

  /** Returns whether the throwable's cause chain contains a PostgreSQL foreign-key violation. */
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
