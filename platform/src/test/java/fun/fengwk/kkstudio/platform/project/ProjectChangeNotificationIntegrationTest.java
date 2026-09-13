package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 验证 Project/Issue 数据库失效通知只在事务提交后发送 canonical project id。 */
class ProjectChangeNotificationIntegrationTest extends ProjectTestSupport {

  private static final String CHANNEL = "project_issue_changed";

  @Autowired private ProjectService projectService;
  @Autowired private IssueService issueService;

  @Test
  void committedProjectAndIssueMutationsPublishProjectIdWhileRollbackStaysSilent()
      throws Exception {
    String agentName = createTestAgent();
    try (Connection listener = jdbcTemplate.getDataSource().getConnection();
        Statement listen = listener.createStatement()) {
      listener.setAutoCommit(true);
      listen.execute("LISTEN " + CHANNEL);
      PGConnection notifications = listener.unwrap(PGConnection.class);

      Project project = projectService.createProject("Notification project", "", agentName);
      assertEquals(List.of(project.getId().toString()), awaitPayloads(notifications));

      issueService.createIssue(
          project.getId(), "Notification issue", "", agentName, null, IssueStatus.TODO);
      assertEquals(List.of(project.getId().toString()), awaitPayloads(notifications));

      try (Connection writer = jdbcTemplate.getDataSource().getConnection();
          Statement update = writer.createStatement()) {
        writer.setAutoCommit(false);
        update.executeUpdate(
            "update project set title = 'Rolled back' where id = '" + project.getId() + "'");
        assertTrue(readNotifications(notifications, Duration.ofMillis(100)).isEmpty());
        writer.rollback();
      }
      assertTrue(readNotifications(notifications, Duration.ofMillis(200)).isEmpty());
    }
  }

  private static List<String> awaitPayloads(PGConnection connection) throws Exception {
    return readNotifications(connection, Duration.ofSeconds(5));
  }

  private static List<String> readNotifications(PGConnection connection, Duration timeout)
      throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    List<String> payloads = new ArrayList<>();
    while (System.nanoTime() < deadline && payloads.isEmpty()) {
      PGNotification[] received = connection.getNotifications(100);
      if (received == null) {
        continue;
      }
      for (PGNotification notification : received) {
        if (CHANNEL.equals(notification.getName())) {
          payloads.add(notification.getParameter());
        }
      }
    }
    return payloads;
  }
}
