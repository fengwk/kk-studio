package fun.fengwk.kkstudio.workspace;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
class WorkspaceResourceOwnership implements ResourceOwnership {
  private final JdbcClient jdbc;

  WorkspaceResourceOwnership(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Map<UUID, Owner> findOwners(Kind kind, Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    String column =
        switch (kind) {
          case SESSION -> "session_id";
          case CANVAS -> "canvas_id";
        };
    return jdbc
        .sql(
            """
        select r.id, s.id as space_id, s.title, s.owner_user_id
        from workspace_resource r join chat_space s on s.id = r.workspace_id
        where r.%s = any(cast(:ids as uuid[]))
        """
                .formatted(column))
        .param("ids", ids.toArray(UUID[]::new))
        .query(
            (rs, index) ->
                Map.entry(
                    rs.getObject("id", UUID.class),
                    new Owner(
                        rs.getObject("space_id", UUID.class),
                        rs.getString("title"),
                        rs.getObject("owner_user_id", UUID.class))))
        .stream()
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
  }
}
