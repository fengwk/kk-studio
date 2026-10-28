package fun.fengwk.kkstudio.web.environment.cluster;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthenticationException;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.environment.update.EnvironmentUpdateService;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentRuntimeDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentRuntimeStatusDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentUpdateStartDTO;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/** 内部 runtime 入口：认证先于副作用，只有本节点 READY owner 才调用本地更新服务。 */
class ClusterEnvironmentRuntimeInternalControllerTest {

  private static final UUID ENV_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private EnvironmentUpdateService updateService;
  private ClusterNodeRegistry clusterNodeRegistry;
  private ClusterInternalAuthentication authentication;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    updateService = mock(EnvironmentUpdateService.class);
    clusterNodeRegistry = mock(ClusterNodeRegistry.class);
    authentication = mock(ClusterInternalAuthentication.class);
    when(clusterNodeRegistry.route(any()))
        .thenReturn(Optional.of(ClusterOwnerRoute.local(UUID.randomUUID())));
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ClusterEnvironmentRuntimeInternalController(
                    updateService, clusterNodeRegistry, authentication))
            .build();
  }

  @Test
  void returnsLocalRuntimeSnapshotAfterAuthenticationAndOwnerCheck() throws Exception {
    when(updateService.runtimeStatus(any())).thenReturn(runtimeStatus("3"));

    mockMvc
        .perform(get("/internal/environments/" + ENV_ID + "/runtime"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revision").value("3"))
        .andExpect(jsonPath("$.status.daemonVersion").value("1.0.9"));
  }

  @Test
  void forwardsUpdateToLocalServiceAndReturnsCommandStatus() throws Exception {
    EnvironmentUpdateStartDTO started = new EnvironmentUpdateStartDTO();
    started.setCommandStatus(EnvironmentUpdateStartDTO.SENT_UNCONFIRMED);
    started.setRuntimeStatus(runtimeStatus("4"));
    when(updateService.startUpdate(any())).thenReturn(started);

    mockMvc
        .perform(post("/internal/environments/" + ENV_ID + "/update"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.commandStatus").value("SENT_UNCONFIRMED"));
  }

  @Test
  void rejectsAuthenticationFailureBeforeOwnerOrServiceAccess() throws Exception {
    doThrow(new ClusterInternalAuthenticationException("bad signature"))
        .when(authentication)
        .verify(any(), any(), any());

    mockMvc
        .perform(get("/internal/environments/" + ENV_ID + "/runtime"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("unauthorized"));
  }

  @Test
  void rejectsNonOwnerNode() throws Exception {
    when(clusterNodeRegistry.route(any()))
        .thenReturn(
            Optional.of(
                ClusterOwnerRoute.remote(UUID.randomUUID(), URI.create("https://other.internal"))));

    mockMvc
        .perform(get("/internal/environments/" + ENV_ID + "/runtime"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("environment_unavailable"));
  }

  private static EnvironmentRuntimeStatusDTO runtimeStatus(String revision) {
    EnvironmentRuntimeDTO status = new EnvironmentRuntimeDTO();
    status.setDaemonVersion("1.0.9");
    EnvironmentRuntimeStatusDTO snapshot = new EnvironmentRuntimeStatusDTO();
    snapshot.setRevision(revision);
    snapshot.setStatus(status);
    return snapshot;
  }
}
