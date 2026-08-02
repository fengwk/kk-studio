package fun.fengwk.kkstudio.core.ai.runtime.thread.command;

import static fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport.newConnection;

import fun.fengwk.kkstudio.harness.runtime.thread.HarnessThread;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

/** Test helper for the atomic Session + ROOT + bound Thread command. */
public final class TestThreads {

  private TestThreads() {}

  public record Created(
      long threadId, long executionEpoch, long sessionId, long rootEntryId, HarnessThread thread) {}

  public static Created create(ThreadCommandTransactions transactions, String title, Instant now) {
    return create(transactions, title, null, now);
  }

  public static Created create(
      ThreadCommandTransactions transactions, String title, String environmentName, Instant now) {
    HarnessThread thread = transactions.createThread(title, environmentName, now);
    long sessionId = sessionId(thread.headEntryId());
    return new Created(
        thread.id(), thread.executionEpoch(), sessionId, thread.headEntryId(), thread);
  }

  private static long sessionId(long rootEntryId) {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement("select session_id from harness_entry where id = ?")) {
      statement.setLong(1, rootEntryId);
      try (var result = statement.executeQuery()) {
        if (!result.next()) {
          throw new AssertionError("created Thread root entry is missing: " + rootEntryId);
        }
        return result.getLong(1);
      }
    } catch (SQLException exception) {
      throw new AssertionError("cannot read created Thread session", exception);
    }
  }
}
