package fun.fengwk.kkstudio.platform.environment.registry;

import org.springframework.jdbc.core.RowMapper;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.DaemonReadyMetadata;
import fun.fengwk.kkstudio.platform.daemon.DaemonReadyMetadataCodec;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@code environment_connection} 行到 {@link EnvironmentConnection} 的映射。
 *
 * <p>只做列读取与解码：连接状态、宿主 metadata 与事件窗口都按行内既有事实还原，不从其它来源补值。状态推进、围栏与通知仍由 {@link EnvironmentRegistry}
 * 负责。
 */
final class EnvironmentConnectionRows {

  private final DaemonReadyMetadataCodec readyMetadataCodec;
  private final EnvironmentStateCodec stateCodec;

  EnvironmentConnectionRows(
      DaemonReadyMetadataCodec readyMetadataCodec, EnvironmentStateCodec stateCodec) {
    this.readyMetadataCodec = readyMetadataCodec;
    this.stateCodec = stateCodec;
  }

  /** 查询结果共用同一映射；行内容与连接状态保持一致。 */
  RowMapper<EnvironmentConnection> mapper() {
    return this::mapRow;
  }

  private EnvironmentConnection mapRow(ResultSet rs, int rowNum) throws SQLException {
    EnvironmentId id = EnvironmentId.of((UUID) rs.getObject("environment_id"));
    UUID owner = (UUID) rs.getObject("owner_node_id");
    UUID leaseToken = (UUID) rs.getObject("lease_token");
    LiveEnvironmentStatus status = LiveEnvironmentStatus.valueOf(rs.getString("status"));
    // 保留事实：任何非 null 的 ready_metadata 都可解码，与当前 status 无关。
    String readyMetadataJson = rs.getString("ready_metadata");
    DaemonReadyMetadata readyMetadata =
        readyMetadataJson == null ? null : readyMetadataCodec.decode(readyMetadataJson);
    // 事件记录与连接状态独立保留。
    List<EnvironmentEvent> recentEvents = stateCodec.decodeEvents(rs.getString("recent_events"));
    Instant lastSeenAt = rs.getObject("last_seen_at", OffsetDateTime.class).toInstant();
    Instant leaseUntil = rs.getObject("lease_until", OffsetDateTime.class).toInstant();
    return new EnvironmentConnection(
        id, owner, leaseToken, status, readyMetadata, recentEvents, lastSeenAt, leaseUntil);
  }
}
