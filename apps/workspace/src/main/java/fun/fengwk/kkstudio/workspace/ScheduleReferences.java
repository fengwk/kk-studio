package fun.fengwk.kkstudio.workspace;

import org.springframework.jdbc.core.simple.JdbcClient;

import fun.fengwk.kkstudio.common.error.DomainException;

import java.util.UUID;

/** Guards deletion of workspace targets retained by schedules. */
final class ScheduleReferences {
  private ScheduleReferences() {}

  static void requireFolderUnused(JdbcClient jdbc, UUID spaceId, UUID folderId) {
    requireUnused(
        jdbc.sql(
                "select id, title from workspace_schedule where workspace_id = :space and folder_id = :target order by id")
            .param("space", spaceId)
            .param("target", folderId));
  }

  static void requireSessionUnused(JdbcClient jdbc, UUID sessionId) {
    requireUnused(
        jdbc.sql("select id, title from workspace_schedule where session_id = :target order by id")
            .param("target", sessionId));
  }

  static void requireThreadUnused(JdbcClient jdbc, UUID threadId) {
    requireUnused(
        jdbc.sql("select id, title from workspace_schedule where thread_id = :target order by id")
            .param("target", threadId));
  }

  private static void requireUnused(JdbcClient.StatementSpec query) {
    var references =
        query
            .query((rs, row) -> rs.getObject("id", UUID.class) + ": " + rs.getString("title"))
            .list();
    if (!references.isEmpty()) {
      throw DomainException.conflict(
          "RESOURCE_IN_USE", "Target is used by schedules: " + String.join(", ", references));
    }
  }
}
