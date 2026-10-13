package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@code canvas_group} 的原子 SQL 入口。 */
@Repository
public class CanvasGroupJdbcStore {

  private static final String COLUMNS = "id, canvas_id, title, x, y, width, height";

  private static final RowMapper<CanvasGroupDO> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        CanvasGroupDO group = new CanvasGroupDO();
        group.setId(resultSet.getObject("id", UUID.class));
        group.setCanvasId(resultSet.getObject("canvas_id", UUID.class));
        group.setTitle(resultSet.getString("title"));
        group.setX(resultSet.getDouble("x"));
        group.setY(resultSet.getDouble("y"));
        group.setWidth(resultSet.getDouble("width"));
        group.setHeight(resultSet.getDouble("height"));
        return group;
      };

  private final JdbcClient jdbc;

  public CanvasGroupJdbcStore(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public int insert(CanvasGroupDO group) {
    return jdbc.sql(
            """
            insert into canvas_group (id, canvas_id, title, x, y, width, height)
            values (:id, :canvasId, :title, :x, :y, :width, :height)
            """)
        .param("id", group.getId())
        .param("canvasId", group.getCanvasId())
        .param("title", group.getTitle())
        .param("x", group.getX())
        .param("y", group.getY())
        .param("width", group.getWidth())
        .param("height", group.getHeight())
        .update();
  }

  public List<CanvasGroupDO> listByCanvas(UUID canvasId) {
    return jdbc.sql(
            "select " + COLUMNS + " from canvas_group where canvas_id = :canvasId order by id")
        .param("canvasId", canvasId)
        .query(ROW_MAPPER)
        .list();
  }

  public Optional<CanvasGroupDO> findById(UUID canvasId, UUID id) {
    return jdbc.sql(
            "select " + COLUMNS + " from canvas_group where id = :id and canvas_id = :canvasId")
        .param("canvasId", canvasId)
        .param("id", id)
        .query(ROW_MAPPER)
        .optional();
  }

  public int update(CanvasGroupDO group) {
    return jdbc.sql(
            """
            update canvas_group
            set title = :title, x = :x, y = :y, width = :width, height = :height
            where id = :id and canvas_id = :canvasId
            """)
        .param("id", group.getId())
        .param("canvasId", group.getCanvasId())
        .param("title", group.getTitle())
        .param("x", group.getX())
        .param("y", group.getY())
        .param("width", group.getWidth())
        .param("height", group.getHeight())
        .update();
  }

  public int deleteById(UUID canvasId, UUID id) {
    return jdbc.sql("delete from canvas_group where id = :id and canvas_id = :canvasId")
        .param("canvasId", canvasId)
        .param("id", id)
        .update();
  }
}
