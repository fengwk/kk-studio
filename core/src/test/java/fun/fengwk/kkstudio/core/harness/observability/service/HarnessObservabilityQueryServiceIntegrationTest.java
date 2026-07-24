package fun.fengwk.kkstudio.core.harness.observability.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.harness.interaction.store.mapper.InteractionMapper;
import fun.fengwk.kkstudio.core.harness.model.worker.ModelInvocationMapper;
import fun.fengwk.kkstudio.core.harness.observability.service.impl.HarnessObservabilityDtoConverter;
import fun.fengwk.kkstudio.core.harness.observability.service.impl.HarnessObservabilityQueryServiceImpl;
import fun.fengwk.kkstudio.core.harness.query.HarnessQueryDtoConverter;
import fun.fengwk.kkstudio.core.harness.query.HarnessQueryRow;
import fun.fengwk.kkstudio.core.harness.query.PostgresqlHarnessQueryMapper;
import fun.fengwk.kkstudio.core.harness.tool.worker.PostgresqlToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.worker.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.Artifact;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ArtifactStore;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.share.model.ToolInvocationDTO;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Observability query 单元级边界：ToolInvocation 投影、Artifact 查找。
 *
 * <p>PostgreSQL snapshot 端到端见 {@code PostgresqlHarnessQueryServiceIntegrationTest}。
 */
class HarnessObservabilityQueryServiceIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  private static final ToolDescriptorJsonCodec TOOL_DESCRIPTOR_CODEC =
      new ToolDescriptorJsonCodec();

  private PostgresqlHarnessQueryMapper queryMapper;
  private PostgresqlToolInvocationMapper invocationMapper;
  private ModelInvocationMapper modelInvocationMapper;
  private InteractionMapper interactionMapper;
  private ArtifactStore artifactStore;
  private HarnessObservabilityQueryServiceImpl service;

  @BeforeEach
  void setUp() {
    queryMapper = mock(PostgresqlHarnessQueryMapper.class);
    invocationMapper = mock(PostgresqlToolInvocationMapper.class);
    modelInvocationMapper = mock(ModelInvocationMapper.class);
    interactionMapper = mock(InteractionMapper.class);
    artifactStore = mock(ArtifactStore.class);
    service =
        new HarnessObservabilityQueryServiceImpl(
            queryMapper,
            invocationMapper,
            modelInvocationMapper,
            interactionMapper,
            artifactStore,
            new HarnessQueryDtoConverter(),
            new HarnessObservabilityDtoConverter(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void projectsToolInvocationsAndArtifactLookup() {
    long threadId = 7_100_001L;
    ToolInvocationDO row = new ToolInvocationDO();
    row.setId(9_007_199_254_740_993L);
    row.setThreadId(threadId);
    row.setSessionId(7_100_101L);
    row.setAssistantEntryId(7_100_201L);
    row.setOrdinal(0);
    row.setToolCallId("call-1");
    row.setDescriptorJson(
        TOOL_DESCRIPTOR_CODEC.encode(
            new ToolDescriptor(
                "bash",
                "1",
                "run",
                "bash",
                new ToolParamsSchema("", Map.of(), Set.of(), false),
                ToolExecutionLocation.PLATFORM,
                ToolSideEffect.READ_ONLY,
                Duration.ofSeconds(1))));
    row.setArgumentsJson("{}");
    row.setLocation("PLATFORM");
    row.setExecutionEpoch(0L);
    row.setStatus("QUEUED");
    row.setAttempt(1);
    when(invocationMapper.listByThread(threadId)).thenReturn(List.of(row));
    when(invocationMapper.find(row.getId())).thenReturn(row);
    when(artifactStore.find("9001"))
        .thenReturn(
            Optional.of(
                new Artifact(9001L, "text/plain", "identity", new byte[] {1, 2}, 2, "hash")));

    List<ToolInvocationDTO> listed = service.listToolInvocations(Long.toString(threadId));
    assertEquals(1, listed.size());
    assertEquals("bash", listed.get(0).getToolName());
    assertEquals("QUEUED", listed.get(0).getStatus());
    assertEquals("bash", service.getToolInvocation(Long.toString(row.getId())).getToolName());
    assertEquals(2, service.getArtifact("9001").sizeBytes());
    assertTrue(service.listSessionTasks("1").isEmpty());
  }

  @Test
  void requiresKnownSessionForRootActivities() {
    when(queryMapper.findSession(1L)).thenReturn(null);
    assertThrows(IllegalArgumentException.class, () -> service.listRootActivities("1", 0, 10));
    HarnessQueryRow session = new HarnessQueryRow();
    session.setId(2L);
    when(queryMapper.findSession(2L)).thenReturn(session);
    when(queryMapper.listSessionTree(2L)).thenReturn(List.of(session));
    when(queryMapper.listThreadViewsBySession(2L)).thenReturn(List.of());
    assertTrue(service.listRootActivities("2", 0, 10).isEmpty());
  }
}
