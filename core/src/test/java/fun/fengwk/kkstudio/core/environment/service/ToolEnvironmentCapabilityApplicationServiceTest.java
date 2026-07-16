package fun.fengwk.kkstudio.core.environment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fun.fengwk.kkstudio.core.environment.repo.ToolEnvironmentRepository;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolCapabilitiesCodec.DaemonToolCapabilities;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ToolEnvironmentCapabilityApplicationServiceTest {

  private static final Instant FIXED_INSTANT = Instant.parse("2026-07-17T08:00:00Z");
  private static final LocalDateTime FIXED_LOCAL =
      LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

  private final DaemonToolCapabilitiesCodec codec = new DaemonToolCapabilitiesCodec();
  private ToolEnvironmentRepository repository;
  private ToolEnvironmentCapabilityApplicationService service;

  @BeforeEach
  void setUp() {
    repository = mock(ToolEnvironmentRepository.class);
    Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    service = new ToolEnvironmentCapabilityApplicationService(repository, codec, clock);
  }

  @Test
  void updateCapabilitiesPersistsCanonicalPayloadAndUpdatesLastSeen() {
    when(repository.updateCapabilities(eq(42L), any(), eq(FIXED_LOCAL))).thenReturn(true);

    String callerJson =
        "{ \"tools\" : [ "
            + "{\"name\":\"shell\",\"version\":\"1\",\"description\":\"shell tool\","
            + "\"rendererKey\":\"r\",\"executionMode\":\"ENVIRONMENT\","
            + "\"sideEffect\":\"READ_ONLY\",\"timeoutMillis\":3000,"
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{},"
            + "\"required\":[],\"additionalProperties\":false}} ] }";

    service.updateCapabilities("42", callerJson);

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(repository).updateCapabilities(eq(42L), captor.capture(), eq(FIXED_LOCAL));
    String persisted = captor.getValue();
    // Round-trip canonical: persisted JSON must re-decode to the same descriptor list.
    DaemonToolCapabilities roundTripped = codec.decode(persisted);
    assertEquals(1, roundTripped.tools().size());
    ToolDescriptor restored = roundTripped.tools().get(0);
    assertEquals("shell", restored.name());
    assertEquals("1", restored.version());
    assertEquals("r", restored.rendererKey());
    assertEquals(ToolExecutionMode.ENVIRONMENT, restored.executionMode());
  }

  @Test
  void updateCapabilitiesAcceptsEmptyCapabilities() {
    when(repository.updateCapabilities(eq(42L), any(), eq(FIXED_LOCAL))).thenReturn(true);

    service.updateCapabilities("42", "{\"tools\":[]}");

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(repository).updateCapabilities(eq(42L), captor.capture(), eq(FIXED_LOCAL));
    DaemonToolCapabilities roundTripped = codec.decode(captor.getValue());
    assertTrue(roundTripped.tools().isEmpty());
  }

  @Test
  void updateCapabilitiesRejectsMalformedPayload() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.updateCapabilities("42", "{\"tools\":[{"));
    assertTrue(error.getMessage().contains("canonical Daemon CAPABILITIES payload"));
    verify(repository, never()).updateCapabilities(anyLong(), any(), any());
  }

  @Test
  void updateCapabilitiesRejectsNonEnvironmentDescriptor() {
    // Build a non-ENVIRONMENT payload manually because the codec now rejects it at the
    // constructor boundary as well; the service must still reject it through the codec path.
    String json =
        "{\"tools\":[{\"name\":\"cloud-tool\",\"version\":\"1\",\"description\":\"cloud\","
            + "\"rendererKey\":\"r\",\"executionMode\":\"CLOUD\",\"sideEffect\":\"READ_ONLY\","
            + "\"timeoutMillis\":0,\"inputSchema\":{\"type\":\"object\",\"properties\":{},"
            + "\"required\":[],\"additionalProperties\":false}}]}";
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> service.updateCapabilities("42", json));
    assertTrue(error.getMessage().contains("canonical Daemon CAPABILITIES payload"));
    verify(repository, never()).updateCapabilities(anyLong(), any(), any());
  }

  @Test
  void updateCapabilitiesRejectsNull() {
    assertThrows(IllegalArgumentException.class, () -> service.updateCapabilities("42", null));
    verify(repository, never()).updateCapabilities(anyLong(), any(), any());
  }

  @Test
  void updateCapabilitiesRejectsMalformedId() {
    assertThrows(
        IllegalArgumentException.class, () -> service.updateCapabilities("+1", "{\"tools\":[]}"));
    assertThrows(
        IllegalArgumentException.class, () -> service.updateCapabilities("-1", "{\"tools\":[]}"));
    assertThrows(
        IllegalArgumentException.class, () -> service.updateCapabilities("0", "{\"tools\":[]}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.updateCapabilities("99999999999999999999", "{\"tools\":[]}"));
    verify(repository, never()).updateCapabilities(anyLong(), any(), any());
  }

  @Test
  void updateCapabilitiesFailsWhenEnvironmentMissing() {
    when(repository.updateCapabilities(eq(999L), any(), eq(FIXED_LOCAL))).thenReturn(false);
    assertThrows(
        NoSuchElementException.class, () -> service.updateCapabilities("999", "{\"tools\":[]}"));
  }

  @Test
  void heartbeatUpdatesOnlyLastSeen() {
    when(repository.heartbeat(7L, FIXED_LOCAL)).thenReturn(true);

    service.heartbeat("7");

    verify(repository).heartbeat(7L, FIXED_LOCAL);
    verify(repository, never()).updateCapabilities(anyLong(), any(), any());
  }

  @Test
  void heartbeatFailsWhenEnvironmentMissing() {
    when(repository.heartbeat(999L, FIXED_LOCAL)).thenReturn(false);
    assertThrows(NoSuchElementException.class, () -> service.heartbeat("999"));
  }

  @Test
  void heartbeatRejectsMalformedId() {
    assertThrows(IllegalArgumentException.class, () -> service.heartbeat("+1"));
    assertThrows(IllegalArgumentException.class, () -> service.heartbeat("  "));
    assertThrows(IllegalArgumentException.class, () -> service.heartbeat("0"));
    verify(repository, never()).heartbeat(anyLong(), any());
  }
}
