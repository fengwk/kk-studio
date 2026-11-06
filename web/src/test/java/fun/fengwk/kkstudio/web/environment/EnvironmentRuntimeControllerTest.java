package fun.fengwk.kkstudio.web.environment;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentRuntimeDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentRuntimeStatusDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentUpdateCommandStatus;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentUpdateProgressDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentUpdateStartDTO;
import fun.fengwk.kkstudio.platform.environment.update.EnvironmentUpdateService;
import fun.fengwk.kkstudio.platform.i18n.StudioMessageService;
import fun.fengwk.kkstudio.web.advice.StudioProblemAdvice;
import fun.fengwk.kkstudio.web.environment.cluster.EnvironmentRuntimeOwnerClient;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/** Environment runtime/update-daemon 公开契约：只经 READY owner 读取或执行，缺少路由即 unavailable。 */
class EnvironmentRuntimeControllerTest {

  private static final UUID ENV_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private EnvironmentUpdateService environmentUpdateService;
  private ClusterNodeRegistry clusterNodeRegistry;
  private EnvironmentRuntimeOwnerClient runtimeOwnerClient;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    environmentUpdateService = mock(EnvironmentUpdateService.class);
    clusterNodeRegistry = mock(ClusterNodeRegistry.class);
    runtimeOwnerClient = mock(EnvironmentRuntimeOwnerClient.class);
    when(clusterNodeRegistry.route(any()))
        .thenReturn(Optional.of(ClusterOwnerRoute.local(UUID.randomUUID())));

    EnvironmentRuntimeController controller =
        new EnvironmentRuntimeController(
            new EnvironmentRuntimeRoutingService(
                environmentUpdateService, clusterNodeRegistry, runtimeOwnerClient));
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new StudioProblemAdvice(new StudioMessageService()))
            .build();
  }

  /** 意图：local owner 直接返回内存 runtime 快照，revision/status 与显式 null update 都按 wire 输出。 */
  @Test
  void runtimeEndpointReturnsCurrentSnapshotFromLocalOwner() throws Exception {
    when(environmentUpdateService.runtimeStatus(EnvironmentId.of(ENV_ID)))
        .thenReturn(runtimeStatus("7", "1.0.9", null));

    mockMvc
        .perform(get("/api/platform/environments/" + ENV_ID + "/runtime"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revision").value("7"))
        .andExpect(jsonPath("$.status.daemonVersion").value("1.0.9"))
        .andExpect(jsonPath("$.status.update").value(nullValue()));
  }

  /** 意图：没有 READY owner 路由时明确返回 unavailable，不能伪造空 update 快照。 */
  @Test
  void runtimeEndpointReturnsUnavailableWithoutOwnerRoute() throws Exception {
    when(clusterNodeRegistry.route(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/platform/environments/" + ENV_ID + "/runtime"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("unavailable"));
  }

  /** 意图：非 owner 的 runtime 读取同样只做一次内部 HMAC 调用，不回退本地更新服务。 */
  @Test
  void runtimeEndpointUsesOwnerSingleHop() throws Exception {
    ClusterOwnerRoute remote =
        ClusterOwnerRoute.remote(UUID.randomUUID(), URI.create("https://owner.internal"));
    when(clusterNodeRegistry.route(any())).thenReturn(Optional.of(remote));
    when(runtimeOwnerClient.runtimeStatus(remote, EnvironmentId.of(ENV_ID)))
        .thenReturn(runtimeStatus("9", "1.0.9", null));

    mockMvc
        .perform(get("/api/platform/environments/" + ENV_ID + "/runtime"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revision").value("9"));
    verify(environmentUpdateService, never()).runtimeStatus(any());
  }

  /** 意图：start update 必须显式表达命令仅进入发送队列，不得让 UI 把发送前快照误报为已启动。 */
  @Test
  void startUpdateReturnsUnconfirmedCommandStatus() throws Exception {
    EnvironmentUpdateStartDTO started = new EnvironmentUpdateStartDTO();
    started.setCommandStatus(EnvironmentUpdateCommandStatus.SENT_UNCONFIRMED);
    started.setRuntimeStatus(runtimeStatus("7", "1.0.9", null));
    when(environmentUpdateService.startUpdate(EnvironmentId.of(ENV_ID))).thenReturn(started);

    mockMvc
        .perform(post("/api/platform/environments/" + ENV_ID + "/update-daemon"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.commandStatus").value("SENT_UNCONFIRMED"))
        .andExpect(jsonPath("$.runtimeStatus.status.daemonVersion").value("1.0.9"));
  }

  /** 意图：非 owner 节点只做一次 HMAC 内部调用，不在本地执行更新。 */
  @Test
  void updateUsesOwnerSingleHop() throws Exception {
    ClusterOwnerRoute remote =
        ClusterOwnerRoute.remote(UUID.randomUUID(), URI.create("https://owner.internal"));
    when(clusterNodeRegistry.route(any())).thenReturn(Optional.of(remote));
    EnvironmentUpdateStartDTO started = new EnvironmentUpdateStartDTO();
    started.setCommandStatus(EnvironmentUpdateCommandStatus.SENT_UNCONFIRMED);
    started.setRuntimeStatus(runtimeStatus("8", "1.0.9", null));
    when(runtimeOwnerClient.startUpdate(remote, EnvironmentId.of(ENV_ID))).thenReturn(started);

    mockMvc
        .perform(post("/api/platform/environments/" + ENV_ID + "/update-daemon"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.commandStatus").value("SENT_UNCONFIRMED"));
    verify(environmentUpdateService, never()).startUpdate(any());
  }

  private static EnvironmentRuntimeStatusDTO runtimeStatus(
      String revision, String daemonVersion, EnvironmentUpdateProgressDTO update) {
    EnvironmentRuntimeDTO status = new EnvironmentRuntimeDTO();
    status.setDaemonVersion(daemonVersion);
    status.setUpdate(update);
    EnvironmentRuntimeStatusDTO snapshot = new EnvironmentRuntimeStatusDTO();
    snapshot.setRevision(revision);
    snapshot.setStatus(status);
    return snapshot;
  }
}
