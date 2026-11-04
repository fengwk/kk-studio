package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@code canvas_document} 的原子 SQL 入口。 */
@Repository
public class CanvasDocumentJdbcStore {

  private static final String COLUMNS = "id, title, revision, created_at, updated_at";

  private static final RowMapper<CanvasDocumentDO> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        CanvasDocumentDO document = new CanvasDocumentDO();
        document.setId(resultSet.getObject("id", UUID.class));
        document.setTitle(resultSet.getString("title"));
        document.setRevision(resultSet.getLong("revision"));
        document.setCreatedAt(resultSet.getObject("created_at", OffsetDateTime.class));
        document.setUpdatedAt(resultSet.getObject("updated_at", OffsetDateTime.class));
        return document;
      };

  private final JdbcClient jdbc;

  public CanvasDocumentJdbcStore(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public int insert(CanvasDocumentDO document) {
    return jdbc.sql(
            """
            insert into canvas_document (id, title, revision, created_at, updated_at)
            values (:id, :title, 0, current_timestamp, current_timestamp)
            """)
        .param("id", document.getId())
        .param("title", document.getTitle())
        .update();
  }

  public Optional<CanvasDocumentDO> findById(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from canvas_document where id = :id")
        .param("id", id)
        .query(ROW_MAPPER)
        .optional();
  }

  public Optional<CanvasDocumentDO> findByIdForUpdate(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from canvas_document where id = :id for update")
        .param("id", id)
        .query(ROW_MAPPER)
        .optional();
  }

  /** 归属/授权路径的轻量锁：KEY SHARE 阻止 owner 删除，但不串行化同 Canvas 的并发命令与接受。 */
  public Optional<CanvasDocumentDO> findByIdForKeyShare(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from canvas_document where id = :id for key share")
        .param("id", id)
        .query(ROW_MAPPER)
        .optional();
  }

  public List<CanvasDocumentDO> listAll() {
    return jdbc.sql("select " + COLUMNS + " from canvas_document order by updated_at desc, id desc")
        .query(ROW_MAPPER)
        .list();
  }

  /** CAS 推进同步位置：仅当当前 revision 等于 expected 时前进，返回受影响行数。 */
  public int advanceRevision(UUID id, long expectedRevision, long newRevision) {
    return jdbc.sql(
            """
            update canvas_document
            set revision = :newRevision, updated_at = greatest(updated_at, clock_timestamp())
            where id = :id and revision = :expectedRevision
            """)
        .param("id", id)
        .param("expectedRevision", expectedRevision)
        .param("newRevision", newRevision)
        .update();
  }

  public int deleteById(UUID id) {
    return jdbc.sql("delete from canvas_document where id = :id").param("id", id).update();
  }
}
