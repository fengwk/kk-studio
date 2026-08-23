package fun.fengwk.kkstudio.platform.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

class PostgresqlIntegrityViolationClassifierTest {

  @Test
  void recognizesOnlyNestedPostgresqlForeignKeyViolations() {
    assertTrue(
        PostgresqlIntegrityViolationClassifier.isForeignKeyViolation(integrityFailure("23503")));
    assertFalse(
        PostgresqlIntegrityViolationClassifier.isForeignKeyViolation(integrityFailure("22001")));
    assertFalse(PostgresqlIntegrityViolationClassifier.isForeignKeyViolation(null));
  }

  private static DataIntegrityViolationException integrityFailure(String sqlState) {
    return new DataIntegrityViolationException(
        "database integrity failure", new SQLException("database failure", sqlState));
  }
}
