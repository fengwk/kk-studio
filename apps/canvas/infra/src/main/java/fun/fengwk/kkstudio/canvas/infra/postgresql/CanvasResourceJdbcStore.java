package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@code canvas_resource} 的原子 SQL 入口。 */
@Repository
public class CanvasResourceJdbcStore {

  private static final String COLUMNS =
      "id, canvas_id, owner_node_id, resource_index, blob_id, name, text_content, created_at";

  private static final RowMapper<CanvasResourceDO> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        CanvasResourceDO resource = new CanvasResourceDO();
        resource.setId(resultSet.getObject("id", UUID.class));
        resource.setCanvasId(resultSet.getObject("canvas_id", UUID.class));
        resource.setOwnerNodeId(resultSet.getObject("owner_node_id", UUID.class));
        resource.setResourceIndex(resultSet.getObject("resource_index", Integer.class));
        resource.setBlobId(resultSet.getObject("blob_id", UUID.class));
        resource.setName(resultSet.getString("name"));
        resource.setTextContent(resultSet.getString("text_content"));
        resource.setCreatedAt(resultSet.getObject("created_at", OffsetDateTime.class));
        return resource;
      };

  private final JdbcClient jdbc;

  public CanvasResourceJdbcStore(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public int insert(CanvasResourceDO resource) {
    return insertStatement(
            """
            insert into canvas_resource (
                id, canvas_id, owner_node_id, resource_index, blob_id, name, text_content, created_at
            ) values (
                :id, :canvasId, :ownerNodeId, :resourceIndex, :blobId, :name, :textContent, :createdAt
            )
            """,
            resource)
        .update();
  }

  public int insertIfAbsent(CanvasResourceDO resource) {
    return insertStatement(
            """
            insert into canvas_resource (
                id, canvas_id, owner_node_id, resource_index, blob_id, name, text_content, created_at
            ) values (
                :id, :canvasId, :ownerNodeId, :resourceIndex, :blobId, :name, :textContent, :createdAt
            )
            on conflict (id) do nothing
            """,
            resource)
        .update();
  }

  public Optional<CanvasResourceDO> findById(UUID canvasId, UUID id) {
    return jdbc.sql(
            "select " + COLUMNS + " from canvas_resource where canvas_id = :canvasId and id = :id")
        .param("canvasId", canvasId)
        .param("id", id)
        .query(ROW_MAPPER)
        .optional();
  }

  public Optional<CanvasResourceDO> findByIdForUpdate(UUID canvasId, UUID id) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from canvas_resource where canvas_id = :canvasId and id = :id for update")
        .param("canvasId", canvasId)
        .param("id", id)
        .query(ROW_MAPPER)
        .optional();
  }

  public List<CanvasResourceDO> listByCanvas(UUID canvasId) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from canvas_resource where canvas_id = :canvasId order by owner_node_id,"
                + " resource_index")
        .param("canvasId", canvasId)
        .query(ROW_MAPPER)
        .list();
  }

  public List<CanvasResourceDO> listByOwnerNode(UUID canvasId, UUID nodeId) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from canvas_resource where canvas_id = :canvasId and owner_node_id = :nodeId"
                + " order by resource_index")
        .param("canvasId", canvasId)
        .param("nodeId", nodeId)
        .query(ROW_MAPPER)
        .list();
  }

  public int delete(UUID canvasId, UUID id) {
    return jdbc.sql("delete from canvas_resource where canvas_id = :canvasId and id = :id")
        .param("canvasId", canvasId)
        .param("id", id)
        .update();
  }

  public int detachOwner(UUID canvasId, UUID id, UUID ownerNodeId) {
    return jdbc.sql(
            """
            update canvas_resource
            set owner_node_id = null, resource_index = null
            where canvas_id = :canvasId
              and id = :id
              and owner_node_id = :ownerNodeId
            """)
        .param("canvasId", canvasId)
        .param("id", id)
        .param("ownerNodeId", ownerNodeId)
        .update();
  }

  public int attachOwner(UUID canvasId, UUID id, UUID ownerNodeId, int resourceIndex) {
    return jdbc.sql(
            """
            update canvas_resource
            set owner_node_id = :ownerNodeId, resource_index = :resourceIndex
            where canvas_id = :canvasId
              and id = :id
              and owner_node_id is null
              and resource_index is null
            """)
        .param("canvasId", canvasId)
        .param("id", id)
        .param("ownerNodeId", ownerNodeId)
        .param("resourceIndex", resourceIndex)
        .update();
  }

  public int deleteByOwnerNode(UUID canvasId, UUID nodeId) {
    return jdbc.sql(
            "delete from canvas_resource where canvas_id = :canvasId and owner_node_id = :nodeId")
        .param("canvasId", canvasId)
        .param("nodeId", nodeId)
        .update();
  }

  public int deleteByCanvas(UUID canvasId) {
    return jdbc.sql("delete from canvas_resource where canvas_id = :canvasId")
        .param("canvasId", canvasId)
        .update();
  }

  private JdbcClient.StatementSpec insertStatement(String sql, CanvasResourceDO resource) {
    return jdbc.sql(sql)
        .param("id", resource.getId())
        .param("canvasId", resource.getCanvasId())
        .param("ownerNodeId", resource.getOwnerNodeId())
        .param("resourceIndex", resource.getResourceIndex())
        .param("blobId", resource.getBlobId())
        .param("name", resource.getName())
        .param("textContent", resource.getTextContent())
        .param("createdAt", resource.getCreatedAt());
  }
}
