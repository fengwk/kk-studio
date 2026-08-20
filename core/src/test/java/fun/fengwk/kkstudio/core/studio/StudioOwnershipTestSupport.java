package fun.fengwk.kkstudio.core.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.core.ai.chat.repo.impl.mapper.ChatMapper;
import fun.fengwk.kkstudio.core.ai.chat.repo.impl.model.ChatDO;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasDocumentMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasDocumentDO;

import java.util.UUID;

/**
 * Chat/Canvas 归属仓库集成测试的共享装配与 fixture 构建。
 *
 * <p>owner 行与 {@code harness_session} 行直接经 mapper / JDBC 构造（跳过 ChatService/DurableCanvasService 的
 * 校验分支），使测试聚焦于归属关系的 FK/PK/互斥契约本身。每个测试前由 {@link PostgresSpringTestSupport} 重置并重新迁移 schema。
 */
public abstract class StudioOwnershipTestSupport extends PostgresSpringTestSupport {

  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected ChatMapper chatMapper;
  @Autowired protected CanvasDocumentMapper canvasDocumentMapper;

  /** 建一个 Chat owner 行并返回 id。 */
  protected UUID chatOwner() {
    UUID id = uuid();
    ChatDO chat = new ChatDO();
    chat.setId(id);
    chat.setTitle("owner-" + id);
    chat.setAgentName("schema-agent");
    chat.setVersion(0L);
    assertEquals(1, chatMapper.insert(chat));
    return id;
  }

  /** 建一个 Canvas owner 行并返回 id。 */
  protected UUID canvasOwner() {
    UUID id = uuid();
    CanvasDocumentDO document = new CanvasDocumentDO();
    document.setId(id);
    document.setTitle("owner-" + id);
    document.setVersion(0L);
    assertEquals(1, canvasDocumentMapper.insert(document));
    return id;
  }

  /** 插入一个 harness_session 行（最小合法形态）。 */
  protected void sessionRow(UUID sessionId) {
    jdbc.update(
        "insert into harness_session (id, created_at) values (?, current_timestamp)", sessionId);
  }

  protected long count(String sql, Object... args) {
    Long value = jdbc.queryForObject(sql, Long.class, args);
    return value == null ? 0L : value;
  }

  protected static UUID uuid() {
    return UUID.randomUUID();
  }
}
