package fun.fengwk.kkstudio.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fun.fengwk.convention4j.springboot.starter.web.result.ResultResponseBodyAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryFailureCode;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryListResult;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryLister;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentDirectoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentDirectoryEntryDTO;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * {@link StudioEnvironmentDirectoryController} HTTP 契约（standalone MockMvc）：成功 DTO 形状、默认 root 路径与
 * 400/404/409/504/502 错误映射。
 */
class StudioEnvironmentDirectoryControllerTest {

  private EnvironmentDirectoryLister directoryLister;
  private SystemSettingsSnapshot snapshot;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    directoryLister = mock(EnvironmentDirectoryLister.class);
    // 目录浏览超时来自 SystemSettings.environment（此处覆盖为 5 秒），而不是 bootstrap properties。
    SystemSettings settings =
        new SystemSettings(
            SystemSettings.Tool.DEFAULT,
            SystemSettings.AiRuntime.DEFAULT,
            new SystemSettings.Environment(8L * 1024 * 1024, 60_000L, 5_000L),
            SystemSettings.Integrations.DEFAULT,
            SystemSettings.StorageMedia.DEFAULT,
            SystemSettings.Advanced.DEFAULT);
    snapshot = new SystemSettingsSnapshot(settings);
    StudioEnvironmentDirectoryController controller =
        new StudioEnvironmentDirectoryController(directoryLister, snapshot);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ResultResponseBodyAdvice())
            .build();
  }

  @Test
  void returnsTypedDirectoryListing() throws Exception {
    EnvironmentDirectoryDTO dto = new EnvironmentDirectoryDTO();
    dto.setPath("src");
    dto.setDisplayPath("src");
    dto.setParentPath(".");
    dto.setTruncated(true);
    dto.setGitBranch("main");
    EnvironmentDirectoryEntryDTO entry = new EnvironmentDirectoryEntryDTO();
    entry.setName("main");
    entry.setPath("src/main");
    dto.setEntries(List.of(entry));
    when(directoryLister.listDirectory(any(), any(), any()))
        .thenReturn(
            CompletableFuture.completedFuture(new EnvironmentDirectoryListResult.Loaded(dto)));

    performAsync(get("/api/ai/environments/env-1/directories").param("path", "src"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.path").value("src"))
        .andExpect(jsonPath("$.data.displayPath").value("src"))
        .andExpect(jsonPath("$.data.parentPath").value("."))
        .andExpect(jsonPath("$.data.truncated").value(true))
        .andExpect(jsonPath("$.data.gitBranch").value("main"))
        .andExpect(jsonPath("$.data.entries[0].name").value("main"))
        .andExpect(jsonPath("$.data.entries[0].path").value("src/main"));

    verify(directoryLister)
        .listDirectory(new EnvironmentName("env-1"), "src", Duration.ofSeconds(5));
  }

  /** 目录超时在每次 HTTP 请求现读快照：构造后 replace 必须传到 lister。 */
  @Test
  void usesLiveDirectoryListTimeoutAfterSnapshotReplace() throws Exception {
    when(directoryLister.listDirectory(any(), any(), any()))
        .thenReturn(
            CompletableFuture.completedFuture(
                new EnvironmentDirectoryListResult.Loaded(emptyListing("src"))));
    SystemSettings.Environment base = snapshot.get().environment();
    snapshot.replace(
        new SystemSettings(
            SystemSettings.Tool.DEFAULT,
            SystemSettings.AiRuntime.DEFAULT,
            new SystemSettings.Environment(
                base.maxResourceBytes(), base.heartbeatTimeoutMillis(), 2_000L),
            SystemSettings.Integrations.DEFAULT,
            SystemSettings.StorageMedia.DEFAULT,
            SystemSettings.Advanced.DEFAULT));

    performAsync(get("/api/ai/environments/env-1/directories").param("path", "src"))
        .andExpect(status().isOk());

    verify(directoryLister)
        .listDirectory(new EnvironmentName("env-1"), "src", Duration.ofMillis(2_000));
  }

  @Test
  void defaultsPathToDotForRoot() throws Exception {
    when(directoryLister.listDirectory(any(), any(), any()))
        .thenReturn(
            CompletableFuture.completedFuture(
                new EnvironmentDirectoryListResult.Loaded(emptyListing("."))));

    performAsync(get("/api/ai/environments/env-1/directories"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.path").value("."));

    verify(directoryLister).listDirectory(new EnvironmentName("env-1"), ".", Duration.ofSeconds(5));
  }

  @Test
  void invalidPathMapsToBadRequest() throws Exception {
    ResultActions actions =
        failed(EnvironmentDirectoryFailureCode.INVALID_PATH, "path must not contain '..' segments")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode.code").value("INVALID_PATH"));
  }

  @Test
  void notDirectoryMapsToBadRequest() throws Exception {
    failed(EnvironmentDirectoryFailureCode.NOT_DIRECTORY, "path is not a directory")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode.code").value("NOT_DIRECTORY"));
  }

  @Test
  void notFoundMapsToNotFound() throws Exception {
    failed(EnvironmentDirectoryFailureCode.NOT_FOUND, "path does not exist")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode.code").value("NOT_FOUND"));
  }

  /** 环境未知（registry 无条目）映射 404；环境已注册但未 READY/不可用映射 409。 */
  @Test
  void unknownEnvironmentMapsToNotFound() throws Exception {
    failed(EnvironmentDirectoryFailureCode.ENVIRONMENT_NOT_FOUND, "env-1 is not registered")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode.code").value("ENVIRONMENT_NOT_FOUND"));
  }

  @Test
  void unavailableEnvironmentMapsToConflict() throws Exception {
    failed(EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE, "env-1 is not ready")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode.code").value("ENVIRONMENT_UNAVAILABLE"));
  }

  @Test
  void timeoutMapsToGatewayTimeout() throws Exception {
    failed(EnvironmentDirectoryFailureCode.TIMEOUT, "env-1 directory listing timed out")
        .andExpect(status().isGatewayTimeout())
        .andExpect(jsonPath("$.errorCode.code").value("TIMEOUT"));
  }

  @Test
  void ioErrorMapsToBadGateway() throws Exception {
    failed(EnvironmentDirectoryFailureCode.IO_ERROR, "cannot read directory")
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.errorCode.code").value("IO_ERROR"));
  }

  /** CompletionStage 异常完成也由 async dispatch 映射到既有 504/502 契约，而不是 servlet 线程 join 异常。 */
  @Test
  void mapsExceptionalCompletionAfterAsyncDispatch() throws Exception {
    when(directoryLister.listDirectory(any(), any(), any()))
        .thenReturn(CompletableFuture.failedFuture(new TimeoutException("daemon timed out")));
    performAsync(get("/api/ai/environments/env-1/directories").param("path", "src"))
        .andExpect(status().isGatewayTimeout())
        .andExpect(jsonPath("$.errorCode.code").value("TIMEOUT"));

    when(directoryLister.listDirectory(any(), any(), any()))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("transport failed")));
    performAsync(get("/api/ai/environments/env-1/directories").param("path", "src"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.errorCode.code").value("IO_ERROR"));
  }

  @Test
  void invalidEnvironmentNameMapsToBadRequestWithoutCallingLister() throws Exception {
    performAsync(get("/api/ai/environments/Not-Canonical/directories"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode.code").value("INVALID_ENVIRONMENT_NAME"));
    verify(directoryLister, never()).listDirectory(any(), any(), any());
  }

  private ResultActions failed(EnvironmentDirectoryFailureCode code, String message)
      throws Exception {
    when(directoryLister.listDirectory(any(), any(), any()))
        .thenReturn(
            CompletableFuture.completedFuture(
                new EnvironmentDirectoryListResult.Failed(code, message)));
    return performAsync(get("/api/ai/environments/env-1/directories").param("path", "src"));
  }

  private ResultActions performAsync(MockHttpServletRequestBuilder requestBuilder)
      throws Exception {
    MvcResult initial =
        mockMvc.perform(requestBuilder).andExpect(request().asyncStarted()).andReturn();
    return mockMvc.perform(asyncDispatch(initial));
  }

  private static EnvironmentDirectoryDTO emptyListing(String path) {
    EnvironmentDirectoryDTO dto = new EnvironmentDirectoryDTO();
    dto.setPath(path);
    dto.setDisplayPath(path);
    dto.setParentPath(".");
    dto.setTruncated(false);
    dto.setGitBranch(null);
    dto.setEntries(List.of());
    return dto;
  }
}
