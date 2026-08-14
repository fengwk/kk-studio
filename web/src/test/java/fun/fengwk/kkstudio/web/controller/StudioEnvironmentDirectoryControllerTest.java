package fun.fengwk.kkstudio.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fun.fengwk.convention4j.springboot.starter.web.result.ResultResponseBodyAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentGatewayProperties;
import fun.fengwk.kkstudio.core.ai.environment.service.EnvironmentDirectoryListResult;
import fun.fengwk.kkstudio.core.ai.environment.service.EnvironmentDirectoryLister;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonDirectoryFailureCode;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentDirectoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentDirectoryEntryDTO;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@link StudioEnvironmentDirectoryController} HTTP 契约（standalone MockMvc）：成功 DTO 形状、默认 root 路径与
 * 400/404/504/502 错误映射。
 */
class StudioEnvironmentDirectoryControllerTest {

  private EnvironmentDirectoryLister directoryLister;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    directoryLister = mock(EnvironmentDirectoryLister.class);
    EnvironmentGatewayProperties gatewayProperties = new EnvironmentGatewayProperties();
    gatewayProperties.setDirectoryListTimeout(Duration.ofSeconds(5));
    StudioEnvironmentDirectoryController controller =
        new StudioEnvironmentDirectoryController(directoryLister, gatewayProperties);
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
    entry.setPath("src/main");
    entry.setDisplayPath("main");
    dto.setEntries(List.of(entry));
    when(directoryLister.listDirectory(any(), any(), any()))
        .thenReturn(
            CompletableFuture.completedFuture(new EnvironmentDirectoryListResult.Loaded(dto)));

    mockMvc
        .perform(get("/api/ai/environments/env-1/directories").param("path", "src"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.path").value("src"))
        .andExpect(jsonPath("$.data.displayPath").value("src"))
        .andExpect(jsonPath("$.data.parentPath").value("."))
        .andExpect(jsonPath("$.data.truncated").value(true))
        .andExpect(jsonPath("$.data.gitBranch").value("main"))
        .andExpect(jsonPath("$.data.entries[0].path").value("src/main"))
        .andExpect(jsonPath("$.data.entries[0].displayPath").value("main"));

    verify(directoryLister)
        .listDirectory(new EnvironmentName("env-1"), "src", Duration.ofSeconds(5));
  }

  @Test
  void defaultsPathToDotForRoot() throws Exception {
    when(directoryLister.listDirectory(any(), any(), any()))
        .thenReturn(
            CompletableFuture.completedFuture(
                new EnvironmentDirectoryListResult.Loaded(emptyListing("."))));

    mockMvc
        .perform(get("/api/ai/environments/env-1/directories"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.path").value("."));

    verify(directoryLister).listDirectory(new EnvironmentName("env-1"), ".", Duration.ofSeconds(5));
  }

  @Test
  void invalidPathMapsToBadRequest() throws Exception {
    ResultActions actions =
        failed(DaemonDirectoryFailureCode.INVALID_PATH, "path must not contain '..' segments")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode.code").value("INVALID_PATH"));
  }

  @Test
  void notDirectoryMapsToBadRequest() throws Exception {
    failed(DaemonDirectoryFailureCode.NOT_DIRECTORY, "path is not a directory")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode.code").value("NOT_DIRECTORY"));
  }

  @Test
  void notFoundMapsToNotFound() throws Exception {
    failed(DaemonDirectoryFailureCode.NOT_FOUND, "path does not exist")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode.code").value("NOT_FOUND"));
  }

  @Test
  void offlineEnvironmentMapsToNotFound() throws Exception {
    failed(DaemonDirectoryFailureCode.OFFLINE, "env-1 is offline")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode.code").value("OFFLINE"));
  }

  @Test
  void timeoutMapsToGatewayTimeout() throws Exception {
    failed(DaemonDirectoryFailureCode.TIMEOUT, "env-1 directory listing timed out")
        .andExpect(status().isGatewayTimeout())
        .andExpect(jsonPath("$.errorCode.code").value("TIMEOUT"));
  }

  @Test
  void ioErrorMapsToBadGateway() throws Exception {
    failed(DaemonDirectoryFailureCode.IO_ERROR, "cannot read directory")
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.errorCode.code").value("IO_ERROR"));
  }

  @Test
  void invalidEnvironmentNameMapsToBadRequestWithoutCallingLister() throws Exception {
    mockMvc
        .perform(get("/api/ai/environments/Not-Canonical/directories"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode.code").value("INVALID_ENVIRONMENT_NAME"));
    verify(directoryLister, never()).listDirectory(any(), any(), any());
  }

  private ResultActions failed(DaemonDirectoryFailureCode code, String message) throws Exception {
    when(directoryLister.listDirectory(any(), any(), any()))
        .thenReturn(
            CompletableFuture.completedFuture(
                new EnvironmentDirectoryListResult.Failed(code, message)));
    return mockMvc.perform(get("/api/ai/environments/env-1/directories").param("path", "src"));
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
