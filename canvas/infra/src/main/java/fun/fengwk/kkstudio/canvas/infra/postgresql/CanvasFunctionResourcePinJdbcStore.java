package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** {@code canvas_function_resource_pin} 的原子 SQL 入口。 */
@Repository
public class CanvasFunctionResourcePinJdbcStore {

  private static final RowMapper<CanvasFunctionResourcePinDO> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        CanvasFunctionResourcePinDO pin = new CanvasFunctionResourcePinDO();
        pin.setCanvasId(resultSet.getObject("canvas_id", UUID.class));
        pin.setNodeId(resultSet.getObject("node_id", UUID.class));
        pin.setRequestId(resultSet.getObject("request_id", UUID.class));
        pin.setRole(resultSet.getString("role"));
        pin.setResourceId(resultSet.getObject("resource_id", UUID.class));
        return pin;
      };

  private final JdbcClient jdbc;

  public CanvasFunctionResourcePinJdbcStore(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /** 单条多行 INSERT：SQL 只由固定行模板拼装，占位符名由行序号生成，用户值一律走参数绑定。 */
  public int insertAll(List<CanvasFunctionResourcePinDO> pins) {
    if (pins.isEmpty()) {
      return 0;
    }
    StringBuilder sql =
        new StringBuilder(
            "insert into canvas_function_resource_pin"
                + " (canvas_id, node_id, request_id, role, resource_id) values ");
    Map<String, Object> params = new LinkedHashMap<>();
    for (int index = 0; index < pins.size(); index++) {
      CanvasFunctionResourcePinDO pin = pins.get(index);
      if (index > 0) {
        sql.append(", ");
      }
      sql.append("(:canvasId")
          .append(index)
          .append(", :nodeId")
          .append(index)
          .append(", :requestId")
          .append(index)
          .append(", :role")
          .append(index)
          .append(", :resourceId")
          .append(index)
          .append(')');
      params.put("canvasId" + index, pin.getCanvasId());
      params.put("nodeId" + index, pin.getNodeId());
      params.put("requestId" + index, pin.getRequestId());
      params.put("role" + index, pin.getRole());
      params.put("resourceId" + index, pin.getResourceId());
    }
    return jdbc.sql(sql.toString()).params(params).update();
  }

  public List<CanvasFunctionResourcePinDO> findByRun(UUID canvasId, UUID nodeId, UUID requestId) {
    return jdbc.sql(
            """
            select canvas_id, node_id, request_id, role, resource_id
            from canvas_function_resource_pin
            where canvas_id = :canvasId and node_id = :nodeId and request_id = :requestId
            order by role, resource_id
            """)
        .param("canvasId", canvasId)
        .param("nodeId", nodeId)
        .param("requestId", requestId)
        .query(ROW_MAPPER)
        .list();
  }

  public List<CanvasFunctionResourcePinDO> findByNode(UUID canvasId, UUID nodeId) {
    return jdbc.sql(
            """
            select canvas_id, node_id, request_id, role, resource_id
            from canvas_function_resource_pin
            where canvas_id = :canvasId and node_id = :nodeId
            order by request_id, role, resource_id
            """)
        .param("canvasId", canvasId)
        .param("nodeId", nodeId)
        .query(ROW_MAPPER)
        .list();
  }

  public List<CanvasFunctionResourcePinDO> findByCanvas(UUID canvasId) {
    return jdbc.sql(
            """
            select canvas_id, node_id, request_id, role, resource_id
            from canvas_function_resource_pin
            where canvas_id = :canvasId
            order by node_id, request_id, role, resource_id
            """)
        .param("canvasId", canvasId)
        .query(ROW_MAPPER)
        .list();
  }

  public int countByResource(UUID canvasId, UUID resourceId) {
    return jdbc.sql(
            """
            select count(1)
            from canvas_function_resource_pin
            where canvas_id = :canvasId and resource_id = :resourceId
            """)
        .param("canvasId", canvasId)
        .param("resourceId", resourceId)
        .query((resultSet, rowNumber) -> resultSet.getInt(1))
        .single();
  }

  public int deleteByRun(UUID canvasId, UUID nodeId, UUID requestId) {
    return jdbc.sql(
            """
            delete from canvas_function_resource_pin
            where canvas_id = :canvasId and node_id = :nodeId and request_id = :requestId
            """)
        .param("canvasId", canvasId)
        .param("nodeId", nodeId)
        .param("requestId", requestId)
        .update();
  }

  public int deleteByNode(UUID canvasId, UUID nodeId) {
    return jdbc.sql(
            """
            delete from canvas_function_resource_pin
            where canvas_id = :canvasId and node_id = :nodeId
            """)
        .param("canvasId", canvasId)
        .param("nodeId", nodeId)
        .update();
  }

  public int deleteByCanvas(UUID canvasId) {
    return jdbc.sql("delete from canvas_function_resource_pin where canvas_id = :canvasId")
        .param("canvasId", canvasId)
        .update();
  }
}
