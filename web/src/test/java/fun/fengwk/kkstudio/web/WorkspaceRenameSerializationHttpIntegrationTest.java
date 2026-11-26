package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.postgresql.PGConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Real PostgreSQL concurrency contract for workspace thread rename over HTTP. */
class WorkspaceRenameSerializationHttpIntegrationTest extends WebPostgresTestSupport {
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final long LOCK_WAIT_TIMEOUT_SECONDS = 10;

  @LocalServerPort private int port;
  @Autowired private ObjectMapper mapper;

  /** A structure commit invalidates the rename's RR snapshot, but a fresh CAS retry can succeed. */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void mapsSerializationFailureToRetryableConflictAndKeepsThreadCas() throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    String spacePath = createSpace(client);
    JsonNode initialTree = getJson(client, spacePath + "/tree");
    String folderId = initialTree.at("/folders/0/id").asText();
    JsonNode session =
        sendJson(
            client,
            "POST",
            spacePath + "/sessions",
            "{\"folderId\":\"%s\",\"message\":[{\"type\":\"TEXT\",\"text\":\"First\"}]}"
                .formatted(folderId),
            UUID.randomUUID().toString(),
            200);
    String threadId = session.get("threadId").asText();
    long treeVersion =
        Long.parseLong(getJson(client, spacePath + "/tree").path("treeVersion").asText());
    JsonNode placement = getJson(client, spacePath + "/threads/" + threadId);
    String expectedVersion = placement.get("version").asText();

    ExecutorService requestExecutor = Executors.newSingleThreadExecutor();
    Future<HttpResponse<String>> rename = null;
    try (Connection structure =
        DriverManager.getConnection(sharedJdbcUrl(), sharedJdbcUsername(), sharedJdbcPassword())) {
      structure.setAutoCommit(false);
      structure.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      try (PreparedStatement folderUpdate =
          structure.prepareStatement(
              "update workspace_folder set name = ?, name_key = ? where workspace_id = ? and id = ?")) {
        folderUpdate.setString(1, "Concurrent structure");
        folderUpdate.setString(2, "concurrent structure");
        folderUpdate.setObject(3, UUID.fromString(spaceId(spacePath)));
        folderUpdate.setObject(4, UUID.fromString(folderId));
        assertEquals(1, folderUpdate.executeUpdate());
      }
      try (PreparedStatement treeUpdate =
          structure.prepareStatement(
              "update chat_space set tree_version = tree_version + 1 where id = ?")) {
        treeUpdate.setObject(1, UUID.fromString(spaceId(spacePath)));
        assertEquals(1, treeUpdate.executeUpdate());
      }

      rename =
          requestExecutor.submit(
              () ->
                  send(
                      client,
                      "PATCH",
                      spacePath + "/threads/" + threadId,
                      "{\"title\":\"Retry title\",\"expectedVersion\":\"%s\"}"
                          .formatted(expectedVersion),
                      null));
      assertRenameIsWaitingForSpaceLock(structure.unwrap(PGConnection.class).getBackendPID());
      structure.commit();

      HttpResponse<String> conflict = rename.get(REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      assertEquals(409, conflict.statusCode(), conflict.body());
      JsonNode changedTree = getJson(client, spacePath + "/tree");
      assertEquals("Concurrent structure", changedTree.at("/folders/0/name").asText());
      JsonNode problem = mapper.readTree(conflict.body());
      assertEquals("conflict", problem.path("code").asText());
      // ProblemAdvice represents detail codes using its established reason field.
      assertEquals("THREAD_RENAME_RETRY", problem.path("reason").asText());

      JsonNode retried =
          sendJson(
              client,
              "PATCH",
              spacePath + "/threads/" + threadId,
              "{\"title\":\"Retry title\",\"expectedVersion\":\"%s\"}".formatted(expectedVersion),
              null,
              200);
      assertEquals("Retry title", retried.path("title").asText());
      assertEquals(
          Long.toString(Long.parseLong(expectedVersion) + 1), retried.path("version").asText());
      JsonNode projectedTree = getJson(client, spacePath + "/tree");
      assertEquals(Long.toString(treeVersion + 1), projectedTree.path("treeVersion").asText());
      assertEquals(
          "Retry title", projectedTree.at("/folders/0/resources/0/threads/0/title").asText());

      HttpResponse<String> staleCas =
          send(
              client,
              "PATCH",
              spacePath + "/threads/" + threadId,
              "{\"title\":\"Stale title\",\"expectedVersion\":\"%s\"}".formatted(expectedVersion),
              null);
      assertEquals(409, staleCas.statusCode(), staleCas.body());
      assertEquals("version_conflict", mapper.readTree(staleCas.body()).path("code").asText());
      assertEquals(
          "Retry title",
          getJson(client, spacePath + "/threads/" + threadId).path("title").asText());
    } finally {
      if (rename != null && !rename.isDone()) {
        rename.cancel(true);
      }
      requestExecutor.shutdownNow();
      assertTrue(requestExecutor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private void assertRenameIsWaitingForSpaceLock(int blockerPid) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOCK_WAIT_TIMEOUT_SECONDS);
    try (Connection monitor =
            DriverManager.getConnection(
                sharedJdbcUrl(), sharedJdbcUsername(), sharedJdbcPassword());
        PreparedStatement statement =
            monitor.prepareStatement(
                """
                select count(*)
                from pg_stat_activity activity
                join pg_locks lock on lock.pid = activity.pid
                where activity.datname = current_database()
                  and activity.state = 'active'
                  and activity.wait_event_type = 'Lock'
                  and activity.query ilike '%from chat_space where id =%'
                  and activity.query ilike '%for update%'
                  and lock.granted = false
                  and ? = any(pg_blocking_pids(activity.pid))
                """)) {
      statement.setInt(1, blockerPid);
      statement.setQueryTimeout((int) LOCK_WAIT_TIMEOUT_SECONDS);
      while (System.nanoTime() < deadline) {
        try (ResultSet result = statement.executeQuery()) {
          result.next();
          if (result.getInt(1) > 0) {
            return;
          }
        }
        Thread.onSpinWait();
      }
    }
    throw new AssertionError("Thread rename never appeared as a PostgreSQL lock waiter");
  }

  private String createSpace(HttpClient client) throws Exception {
    JsonNode created =
        sendJson(
            client,
            "POST",
            "/api/chat-spaces",
            "{\"title\":\"C03 workspace\",\"rootFolderName\":\"Root\",\"defaultAgentName\":\"default-assistant\"}",
            UUID.randomUUID().toString(),
            200);
    return "/api/chat-spaces/" + created.path("id").asText();
  }

  private JsonNode getJson(HttpClient client, String path) throws Exception {
    HttpResponse<String> response = send(client, "GET", path, null, null);
    assertEquals(200, response.statusCode(), response.body());
    return mapper.readTree(response.body());
  }

  private JsonNode sendJson(
      HttpClient client, String method, String path, String body, String key, int expectedStatus)
      throws Exception {
    HttpResponse<String> response = send(client, method, path, body, key);
    assertEquals(expectedStatus, response.statusCode(), response.body());
    return mapper.readTree(response.body());
  }

  private HttpResponse<String> send(
      HttpClient client, String method, String path, String body, String key) throws Exception {
    URI uri = URI.create("http://localhost:" + port + path);
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Cookie", adminCookieHeader())
            .header("Origin", "http://localhost:" + port);
    if (body != null) {
      request.header("Content-Type", "application/json");
    }
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    return client.send(
        request
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String spaceId(String spacePath) {
    return spacePath.substring(spacePath.lastIndexOf('/') + 1);
  }
}
