package fun.fengwk.kkstudio.core.ai.chat;

import static fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport.applyDevDatabase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.core.ai.chat.service.ChatService;
import fun.fengwk.kkstudio.core.ai.chat.service.ChatThreadService;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.runtime.thread.command.TestThreads;
import fun.fengwk.kkstudio.core.ai.runtime.thread.service.HarnessThreadQueryService;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.api.CursorPageDTO;

import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** PostgreSQL coverage for Chat↔Thread aggregation, keyset cursors, and transaction boundaries. */
class ChatThreadIntegrationTest extends PostgresSpringTestSupport {

  private static final Instant NOW = Instant.parse("2099-01-01T00:00:00Z");

  @Autowired private ChatService chatService;
  @Autowired private ChatThreadService chatThreadService;
  @Autowired private HarnessThreadQueryService threadQueryService;
  @Autowired private ThreadCommandTransactions threadTransactions;
  @Autowired private JdbcTemplate jdbc;

  @Override
  protected void migrateDatabase(Connection conn) {
    applyDevDatabase(conn);
  }

  @Test
  void globalAndChatPagesUseTimeThenIdKeysetOrderingAndScopeFiltering() {
    ChatDTO firstChat = createChat("first");
    ChatDTO secondChat = createChat("second");
    TestThreads.Created first = TestThreads.create(threadTransactions, "first", NOW);
    TestThreads.Created second = TestThreads.create(threadTransactions, "second", NOW);
    TestThreads.Created third = TestThreads.create(threadTransactions, "third", NOW);

    setTimes(first.threadId(), NOW.plusSeconds(20), NOW.plusSeconds(20));
    setTimes(second.threadId(), NOW.plusSeconds(20), NOW.plusSeconds(20));
    setTimes(third.threadId(), NOW.plusSeconds(10), NOW.plusSeconds(10));

    chatThreadService.associateThread(firstChat.getId(), Long.toString(first.threadId()));
    chatThreadService.associateThread(firstChat.getId(), Long.toString(second.threadId()));
    chatThreadService.associateThread(secondChat.getId(), Long.toString(third.threadId()));

    CursorPageDTO<HarnessThreadDTO> globalFirst = threadQueryService.listAll("recent", null, 2);
    assertEquals(
        List.of(Long.toString(second.threadId()), Long.toString(first.threadId())),
        threadIds(globalFirst));
    assertTrue(globalFirst.getNextCursor() != null);

    CursorPageDTO<HarnessThreadDTO> globalSecond =
        threadQueryService.listAll("recent", globalFirst.getNextCursor(), 2);
    assertEquals(List.of(Long.toString(third.threadId())), threadIds(globalSecond));
    assertTrue(
        Collections.disjoint(
            Set.copyOf(threadIds(globalFirst)), Set.copyOf(threadIds(globalSecond))),
        "keyset pages must not repeat a Thread");
    assertNull(globalSecond.getNextCursor());

    CursorPageDTO<HarnessThreadDTO> createdFirst = threadQueryService.listAll("created", null, 2);
    assertEquals(
        List.of(Long.toString(second.threadId()), Long.toString(first.threadId())),
        threadIds(createdFirst));

    CursorPageDTO<HarnessThreadDTO> chatPage =
        chatThreadService.listThreads(firstChat.getId(), "recent", null, 100);
    assertEquals(
        Set.of(Long.toString(first.threadId()), Long.toString(second.threadId())),
        Set.copyOf(threadIds(chatPage)));

    CursorPageDTO<HarnessThreadDTO> otherChatPage =
        chatThreadService.listThreads(secondChat.getId(), "recent", null, 100);
    assertEquals(List.of(Long.toString(third.threadId())), threadIds(otherChatPage));
  }

  @Test
  void cursorIsBoundToSortAndAssociationIsIdempotent() {
    ChatDTO chat = createChat("cursor");
    TestThreads.Created first = TestThreads.create(threadTransactions, "cursor-1", NOW);
    TestThreads.Created second = TestThreads.create(threadTransactions, "cursor-2", NOW);
    chatThreadService.associateThread(chat.getId(), Long.toString(first.threadId()));
    chatThreadService.associateThread(chat.getId(), Long.toString(first.threadId()));

    assertEquals(
        1L,
        jdbc.queryForObject(
            "select count(*) from chat_thread where chat_id = ? and thread_id = ?",
            Long.class,
            Long.parseLong(chat.getId()),
            first.threadId()));

    setTimes(first.threadId(), NOW, NOW.plusSeconds(1));
    setTimes(second.threadId(), NOW.plusSeconds(1), NOW.plusSeconds(2));
    CursorPageDTO<HarnessThreadDTO> page = threadQueryService.listAll("recent", null, 1);
    assertTrue(page.getNextCursor() != null && !page.getNextCursor().isBlank());
    assertThrows(
        IllegalArgumentException.class,
        () -> threadQueryService.listAll("created", page.getNextCursor(), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> threadQueryService.listAll("recent", "not-a-cursor", 1));
    assertThrows(
        IllegalArgumentException.class, () -> threadQueryService.listAll("recent", null, 101));
  }

  @Test
  void deletingChatRemovesOnlyAssociationsAndPreservesThread() {
    ChatDTO chat = createChat("deletion");
    TestThreads.Created thread = TestThreads.create(threadTransactions, "survivor", NOW);
    chatThreadService.associateThread(chat.getId(), Long.toString(thread.threadId()));

    chatService.deleteChat(chat.getId(), "0");

    assertEquals(
        Long.toString(thread.threadId()),
        threadQueryService.getThread(Long.toString(thread.threadId())).getThreadId());
    assertEquals(
        0L,
        jdbc.queryForObject(
            "select count(*) from chat_thread where chat_id = ?",
            Long.class,
            Long.parseLong(chat.getId())));
  }

  @Test
  void chatScopedCreatePersistsAssociationAndUnknownThreadIsRejected() {
    ChatDTO chat = createChat("scoped-create", "env-default");

    HarnessThreadDTO created = chatThreadService.createThread(chat.getId(), null);

    assertEquals("IDLE", created.getStatus());
    assertNotNull(created.getSessionId());
    assertNotNull(created.getHeadEntryId());
    assertEquals(
        List.of(created.getThreadId()),
        threadIds(chatThreadService.listThreads(chat.getId(), "created", null, 20)));
    assertThrows(
        AiResourceNotFoundException.class,
        () -> chatThreadService.associateThread(chat.getId(), "999999999999"));
  }

  @Test
  void chatScopedCreateSupportsNoDefaultEnvironment() {
    ChatDTO chat = createChat("no-environment");

    HarnessThreadDTO created = chatThreadService.createThread(chat.getId(), null);

    assertEquals("IDLE", created.getStatus());
    assertNotNull(created.getSessionId());
    assertNotNull(created.getHeadEntryId());
  }

  @Test
  void chatScopedCreateRollsBackThreadWhenAssociationFails() {
    ChatDTO chat = createChat("atomic");
    long before = jdbc.queryForObject("select count(*) from harness_thread", Long.class);
    jdbc.execute(
        """
        create function fail_chat_thread_insert() returns trigger
        language plpgsql as $$
        begin
          raise exception 'forced Chat association failure';
        end
        $$;
        """);
    jdbc.execute(
        "create trigger fail_chat_thread_insert before insert on chat_thread"
            + " for each row execute function fail_chat_thread_insert()");

    try {
      assertThrows(
          DataAccessException.class, () -> chatThreadService.createThread(chat.getId(), null));
      assertEquals(before, jdbc.queryForObject("select count(*) from harness_thread", Long.class));
      assertEquals(
          0L,
          jdbc.queryForObject(
              "select count(*) from chat_thread where chat_id = ?",
              Long.class,
              Long.parseLong(chat.getId())));
    } finally {
      jdbc.execute("drop trigger if exists fail_chat_thread_insert on chat_thread");
      jdbc.execute("drop function if exists fail_chat_thread_insert()");
    }
  }

  @Test
  void chatScopedCreateRollsBackAssociationAndThreadWhenThreadCreateFails() {
    ChatDTO chat = createChat("thread-create-atomic", "env-default");
    long threadCount = jdbc.queryForObject("select count(*) from harness_thread", Long.class);
    long sessionCount = jdbc.queryForObject("select count(*) from harness_session", Long.class);
    jdbc.execute(
        """
        create function fail_harness_session_insert() returns trigger
        language plpgsql as $$
        begin
          raise exception 'forced thread create failure';
        end
        $$;
        """);
    jdbc.execute(
        "create trigger fail_harness_session_insert before insert on harness_session"
            + " for each row execute function fail_harness_session_insert()");

    try {
      assertThrows(
          DataAccessException.class, () -> chatThreadService.createThread(chat.getId(), null));
      assertEquals(
          threadCount, jdbc.queryForObject("select count(*) from harness_thread", Long.class));
      assertEquals(
          sessionCount, jdbc.queryForObject("select count(*) from harness_session", Long.class));
      assertEquals(
          0L,
          jdbc.queryForObject(
              "select count(*) from chat_thread where chat_id = ?",
              Long.class,
              Long.parseLong(chat.getId())));
    } finally {
      jdbc.execute("drop trigger if exists fail_harness_session_insert on harness_session");
      jdbc.execute("drop function if exists fail_harness_session_insert()");
    }
  }

  private ChatDTO createChat(String title) {
    return createChat(title, null);
  }

  private ChatDTO createChat(String title, String environmentName) {
    ChatCreateDTO create = new ChatCreateDTO();
    create.setTitle(title);
    create.setAgentName("default-assistant");
    return chatService.createChat(create);
  }

  private void setTimes(long threadId, Instant createdAt, Instant updatedAt) {
    jdbc.update(
        "update harness_thread set created_at = ?, updated_at = ? where id = ?",
        OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC),
        OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC),
        threadId);
  }

  private static List<String> threadIds(CursorPageDTO<HarnessThreadDTO> page) {
    return page.getItems().stream().map(HarnessThreadDTO::getThreadId).toList();
  }
}
