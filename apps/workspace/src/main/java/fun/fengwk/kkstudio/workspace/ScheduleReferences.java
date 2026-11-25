package fun.fengwk.kkstudio.workspace;

import org.springframework.jdbc.core.simple.JdbcClient;

import fun.fengwk.kkstudio.common.error.DomainException;

import java.util.List;
import java.util.UUID;

/** Guards deletion of workspace targets retained by schedules. */
final class ScheduleReferences {
  private ScheduleReferences() {}

  static void requireUnused(JdbcClient jdbc, String targetColumn, UUID targetId) {
    if (!List.of("session_id", "thread_id", "turn_end_id").contains(targetColumn)) {
      throw new IllegalArgumentException("Unsupported schedule target column");
    }
    var references =
        jdbc.sql(
                "select id, title from workspace_schedule where "
                    + targetColumn
                    + " = :target order by id")
            .param("target", targetId)
            .query((rs, row) -> rs.getObject("id", UUID.class) + ": " + rs.getString("title"))
            .list();
    if (!references.isEmpty()) {
      throw DomainException.conflict(
          "RESOURCE_IN_USE", "Target is used by schedules: " + String.join(", ", references));
    }
  }
}
