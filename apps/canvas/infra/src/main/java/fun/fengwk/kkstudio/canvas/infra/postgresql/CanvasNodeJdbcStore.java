package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@code canvas_node} 的原子 SQL 入口。 */
@Repository
public class CanvasNodeJdbcStore {

  private static final String COLUMNS =
      "id, canvas_id, name, x, y, width, height, group_id, \"function\"::text as function_json";

  private static final RowMapper<CanvasNodeDO> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        CanvasNodeDO node = new CanvasNodeDO();
        node.setId(resultSet.getObject("id", UUID.class));
        node.setCanvasId(resultSet.getObject("canvas_id", UUID.class));
        node.setName(resultSet.getString("name"));
        node.setX(resultSet.getDouble("x"));
        node.setY(resultSet.getDouble("y"));
        node.setWidth(resultSet.getDouble("width"));
        node.setHeight(resultSet.getDouble("height"));
        node.setGroupId(resultSet.getObject("group_id", UUID.class));
        node.setFunctionJson(resultSet.getString("function_json"));
        return node;
      };

  private final JdbcClient jdbc;

  public CanvasNodeJdbcStore(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public int insert(CanvasNodeDO node) {
    return jdbc.sql(
            """
            insert into canvas_node (
                id, canvas_id, name, x, y, width, height, group_id, "function"
            ) values (
                :id, :canvasId, :name, :x, :y, :width, :height, :groupId,
                cast(:functionJson as jsonb)
            )
            """)
        .param("id", node.getId())
        .param("canvasId", node.getCanvasId())
        .param("name", node.getName())
        .param("x", node.getX())
        .param("y", node.getY())
        .param("width", node.getWidth())
        .param("height", node.getHeight())
        .param("groupId", node.getGroupId())
        .param("functionJson", node.getFunctionJson())
        .update();
  }

  public List<CanvasNodeDO> listByCanvas(UUID canvasId) {
    return jdbc.sql(
            "select " + COLUMNS + " from canvas_node where canvas_id = :canvasId order by id")
        .param("canvasId", canvasId)
        .query(ROW_MAPPER)
        .list();
  }

  public Optional<CanvasNodeDO> findById(UUID canvasId, UUID id) {
    return jdbc.sql(
            "select " + COLUMNS + " from canvas_node where id = :id and canvas_id = :canvasId")
        .param("canvasId", canvasId)
        .param("id", id)
        .query(ROW_MAPPER)
        .optional();
  }

  public Optional<CanvasNodeDO> findByIdForUpdate(UUID canvasId, UUID id) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from canvas_node where id = :id and canvas_id = :canvasId for update")
        .param("canvasId", canvasId)
        .param("id", id)
        .query(ROW_MAPPER)
        .optional();
  }

  public int update(CanvasNodeDO node) {
    return jdbc.sql(
            """
            update canvas_node
            set name = :name, x = :x, y = :y, width = :width, height = :height,
                group_id = :groupId, "function" = cast(:functionJson as jsonb)
            where id = :id and canvas_id = :canvasId
            """)
        .param("id", node.getId())
        .param("canvasId", node.getCanvasId())
        .param("name", node.getName())
        .param("x", node.getX())
        .param("y", node.getY())
        .param("width", node.getWidth())
        .param("height", node.getHeight())
        .param("groupId", node.getGroupId())
        .param("functionJson", node.getFunctionJson())
        .update();
  }

  public int deleteById(UUID canvasId, UUID id) {
    return jdbc.sql("delete from canvas_node where id = :id and canvas_id = :canvasId")
        .param("canvasId", canvasId)
        .param("id", id)
        .update();
  }
}
