package fun.fengwk.kkstudio.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.browser.ConnectionIdentity;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUse;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseClient;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseRegistry;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseView;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.error.AiEnvironmentIdeUnavailableException;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 意图：生产 gateway 只把 typed client 结果映射为窄证明，缺失为空、失败不可用，未绑定意图不产生续租证明。 */
class IdeBrowserUseClientGatewayTest {

  private static final UUID LOCAL_NODE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID REMOTE_NODE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final UUID CONNECTION = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
  private static final UUID DOCUMENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final EnvironmentId ENV =
      EnvironmentId.parse("22222222-2222-2222-2222-222222222222");
  private static final UUID VIEW = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final UUID CLAIM = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final UUID UNBOUND_VIEW =
      UUID.fromString("55555555-5555-5555-5555-555555555555");
  private static final UUID UNBOUND_CLAIM =
      UUID.fromString("66666666-6666-6666-6666-666666666666");

  private IdeBrowserUseRegistry registry;
  private ClusterNodeRegistry nodes;
  private IdeBrowserUseClientGateway gateway;

  @BeforeEach
  void setUp() {
    registry = new IdeBrowserUseRegistry();
    nodes = mock(ClusterNodeRegistry.class);
    IdeBrowserUseClient client =
        new IdeBrowserUseClient(
            HttpClient.newHttpClient(),
            JsonMapper.builder().build(),
            mock(ClusterInternalAuthentication.class),
            nodes,
            registry,
            LOCAL_NODE);
    gateway = new IdeBrowserUseClientGateway(client);
  }

  @Test
  void mapsExactIntentAndOnlyBoundProofs() {
    ConnectionIdentity identity = new ConnectionIdentity(LOCAL_NODE, CONNECTION);
    long generation = registry.register(identity);
    registry.accept(
        identity,
        generation,
        new IdeBrowserUse(
            DOCUMENT,
            4L,
            List.of(
                new IdeBrowserUseView(ENV, VIEW, CLAIM, 7L),
                new IdeBrowserUseView(ENV, UNBOUND_VIEW, UNBOUND_CLAIM, null))));

    assertTrue(
        gateway.findExactIntent(LOCAL_NODE, CONNECTION, DOCUMENT, ENV, VIEW, CLAIM));
    IdeBrowserUseGateway.Proofs proofs =
        gateway.activeBoundProofs(LOCAL_NODE, CONNECTION, DOCUMENT).orElseThrow();

    assertEquals(DOCUMENT, proofs.documentId());
    assertEquals(4L, proofs.revision());
    assertEquals(
        List.of(new IdeBrowserUseGateway.Proof(ENV, VIEW, DOCUMENT, 7L)), proofs.views());
    verifyNoMoreInteractions(nodes);
  }

  @Test
  void missingConnectionIsEmptyNotFabricatedUsage() {
    assertFalse(
        gateway.findExactIntent(LOCAL_NODE, CONNECTION, DOCUMENT, ENV, VIEW, CLAIM));
    assertTrue(gateway.activeBoundProofs(LOCAL_NODE, CONNECTION, DOCUMENT).isEmpty());
  }

  @Test
  void remoteQueryWithoutLiveOriginIsUnavailableAndNotRetried() {
    when(nodes.liveInternalOrigin(REMOTE_NODE)).thenReturn(Optional.empty());

    assertThrows(
        AiEnvironmentIdeUnavailableException.class,
        () -> gateway.findExactIntent(REMOTE_NODE, CONNECTION, DOCUMENT, ENV, VIEW, CLAIM));
    verify(nodes).liveInternalOrigin(REMOTE_NODE);
    verifyNoMoreInteractions(nodes);
  }

  @Test
  void unexpectedClientFailureIsWrappedAsUnavailable() {
    IllegalStateException failure = new IllegalStateException("cluster registry unavailable");
    when(nodes.liveInternalOrigin(REMOTE_NODE)).thenThrow(failure);

    AiEnvironmentIdeUnavailableException error =
        assertThrows(
            AiEnvironmentIdeUnavailableException.class,
            () -> gateway.activeBoundProofs(REMOTE_NODE, CONNECTION, DOCUMENT));

    assertInstanceOf(IllegalStateException.class, error.getCause());
    verify(nodes).liveInternalOrigin(REMOTE_NODE);
    verifyNoMoreInteractions(nodes);
  }
}
