package fun.fengwk.kkstudio.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** {@code chat} keyset 分页在真实 PostgreSQL 上的边界：页推进、时间并列、title/id 次序与 null title。 */
class ChatRepositoryPaginationIntegrationTest extends PostgresSpringTestSupport {

  private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

  @Autowired private ChatRepository chatRepository;
  @Autowired private JdbcTemplate jdbc;

  /** 翻页严格按游标推进：无重复、无遗漏，最后一页之后的游标返回空页。 */
  @Test
  void pagesAdvanceWithoutOverlapOrGap() {
    UUID first = chatRow("first", BASE.plusSeconds(1), BASE.plusSeconds(5));
    UUID second = chatRow("second", BASE.plusSeconds(2), BASE.plusSeconds(4));
    UUID third = chatRow("third", BASE.plusSeconds(3), BASE.plusSeconds(3));
    UUID fourth = chatRow("fourth", BASE.plusSeconds(4), BASE.plusSeconds(2));
    UUID fifth = chatRow("fifth", BASE.plusSeconds(5), BASE.plusSeconds(1));

    ChatCursor start = ChatCursor.parse(null);
    List<Chat> page1 = chatRepository.listPage(start, 2);
    assertEquals(List.of(first, second), ids(page1));
    ChatCursor afterPage1 = ChatCursor.parse(ChatCursor.of(page1.getLast()).encode());

    List<Chat> page2 = chatRepository.listPage(afterPage1, 2);
    assertEquals(List.of(third, fourth), ids(page2));
    ChatCursor afterPage2 = ChatCursor.parse(ChatCursor.of(page2.getLast()).encode());

    List<Chat> page3 = chatRepository.listPage(afterPage2, 2);
    assertEquals(List.of(fifth), ids(page3));
    ChatCursor afterPage3 = ChatCursor.parse(ChatCursor.of(page3.getLast()).encode());

    assertTrue(chatRepository.listPage(afterPage3, 2).isEmpty());
  }

  /** 同一 updated_at/created_at 时按 title 升序、再按 id 升序推进。 */
  @Test
  void tiesOrderByTitleThenId() {
    UUID titleB = chatRow("b", BASE, BASE);
    UUID titleA1 = chatRowWithId("a", BASE, BASE, new UUID(0L, 1L));
    UUID titleA2 = chatRowWithId("a", BASE, BASE, new UUID(0L, 2L));
    UUID titleC = chatRow("c", BASE, BASE);

    List<UUID> expected = List.of(titleA1, titleA2, titleB, titleC);
    assertEquals(expected, ids(chatRepository.listPage(null, 10)));

    List<Chat> firstPage = chatRepository.listPage(null, 1);
    ChatCursor afterFirst = ChatCursor.of(firstPage.getLast());
    List<Chat> rest = chatRepository.listPage(afterFirst, 10);
    assertEquals(expected.subList(1, expected.size()), ids(rest));
  }

  /** null title 保持 PostgreSQL ASC 的 NULLS LAST，并且仍能沿游标继续翻页。 */
  @Test
  void nullTitlesSortLastAndRemainPageable() {
    UUID null1 = chatRow(null, BASE, BASE);
    UUID null2 = chatRow(null, BASE, BASE);
    UUID titled = chatRow("a", BASE, BASE);

    List<Chat> all = chatRepository.listPage(null, 10);
    assertEquals(3, all.size());
    assertEquals(titled, all.getFirst().getId());
    assertEquals(Set.of(null1, null2), Set.copyOf(ids(all).subList(1, 3)));

    ChatCursor afterTitled = ChatCursor.of(all.getFirst());
    List<Chat> rest = chatRepository.listPage(afterTitled, 1);
    ChatCursor afterFirstNull = ChatCursor.of(rest.getFirst());
    List<Chat> last = chatRepository.listPage(afterFirstNull, 10);
    assertEquals(1, last.size());
    assertTrue(!last.getFirst().getId().equals(rest.getFirst().getId()));
  }

  /** 非法游标形状在进入 SQL 前失败，不产生静默首屏。 */
  @Test
  void invalidCursorIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> ChatCursor.parse("bad"));
    assertThrows(
        IllegalArgumentException.class, () -> ChatCursor.parse("v1:-1:2:YQ:" + UUID.randomUUID()));
    assertThrows(
        IllegalArgumentException.class, () -> ChatCursor.parse("v1:1:2:%%%:" + UUID.randomUUID()));
    assertThrows(IllegalArgumentException.class, () -> ChatCursor.parse("v1:1:2:YQ:not-a-uuid"));
  }

  private UUID chatRow(String title, Instant createdAt, Instant updatedAt) {
    return chatRowWithId(title, createdAt, updatedAt, UUID.randomUUID());
  }

  private UUID chatRowWithId(String title, Instant createdAt, Instant updatedAt, UUID id) {
    jdbc.update(
        "insert into chat (id, title, agent_name, created_at, updated_at, version)"
            + " values (?, ?, 'schema-agent', ?, ?, 0)",
        id,
        title,
        Timestamp.from(createdAt),
        Timestamp.from(updatedAt));
    return id;
  }

  private static List<UUID> ids(List<Chat> chats) {
    return chats.stream().map(Chat::getId).toList();
  }
}
