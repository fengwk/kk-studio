package fun.fengwk.kkstudio.core.environment.service;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.environment.repo.ToolEnvironmentRepository;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocolException;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolCapabilitiesCodec;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;

/**
 * Daemon-facing application service for updating Environment capability facts.
 *
 * <p>REST CRUD never writes {@code capabilitiesJson} or {@code lastSeenAt}; both are owned by this
 * service so that callers cannot forge capabilities or heartbeat timing.
 *
 * <p>Empty capabilities ({@code {"tools":[]}}) are a valid representation of a freshly registered
 * Environment or a connected daemon that has no exposed tools.
 *
 * <p>Storage is round-tripped through {@link DaemonToolCapabilitiesCodec#encode} so the persisted
 * payload is canonical regardless of caller formatting.
 */
@Service
public class ToolEnvironmentCapabilityApplicationService {

  private final ToolEnvironmentRepository repository;
  private final DaemonToolCapabilitiesCodec codec;
  private final Clock clock;

  public ToolEnvironmentCapabilityApplicationService(
      ToolEnvironmentRepository repository, DaemonToolCapabilitiesCodec codec, Clock clock) {
    this.repository = repository;
    this.codec = codec;
    this.clock = clock;
  }

  /**
   * Atomically replaces canonical capabilities and updates {@code lastSeenAt}.
   *
   * <p>The supplied JSON is decoded strictly so malformed/unknown shapes are rejected before any
   * write; the canonical re-encoded form is what gets persisted.
   */
  public void updateCapabilities(String id, String capabilitiesJson) {
    long parsed = ToolEnvironmentIds.parsePositive(id, "id");
    if (capabilitiesJson == null) {
      throw new IllegalArgumentException("capabilitiesJson must not be null");
    }
    DaemonToolCapabilitiesCodec.DaemonToolCapabilities capabilities;
    try {
      capabilities = codec.decode(capabilitiesJson);
    } catch (DaemonProtocolException error) {
      throw new IllegalArgumentException(
          "capabilitiesJson must be a canonical Daemon CAPABILITIES payload: " + error.getMessage(),
          error);
    }
    String canonical = codec.encode(capabilities);
    LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    if (!repository.updateCapabilities(parsed, canonical, now)) {
      throw new NoSuchElementException("environment not found: " + id);
    }
  }

  /** Atomically updates {@code lastSeenAt} without touching canonical capabilities. */
  public void heartbeat(String id) {
    long parsed = ToolEnvironmentIds.parsePositive(id, "id");
    LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    if (!repository.heartbeat(parsed, now)) {
      throw new NoSuchElementException("environment not found: " + id);
    }
  }
}
