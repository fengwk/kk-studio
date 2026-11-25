package fun.fengwk.kkstudio.web.canvas;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.canvas.http.CanvasResourceController;
import fun.fengwk.kkstudio.canvas.infra.platform.CanvasResourceUrlService;
import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasQueryService;
import fun.fengwk.kkstudio.platform.account.StudioBrowserSession;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.account.StudioPrincipalArgumentResolver;
import fun.fengwk.kkstudio.platform.account.StudioRole;
import fun.fengwk.kkstudio.platform.i18n.MessageService;
import fun.fengwk.kkstudio.platform.orchestration.ResourceAccess;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Kind;
import fun.fengwk.kkstudio.platform.storage.dto.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.platform.storage.dto.StorageUrlMethod;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;
import fun.fengwk.kkstudio.web.advice.ProblemAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Blob URL routes expose only resources held by an authorized Canvas. */
class CanvasResourceControllerTest {

  private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");
  private static final UUID CANVAS = new UUID(0L, 1L);
  private static final UUID BLOB = new UUID(0L, 3L);
  private static final UUID USER = new UUID(0L, 9L);
  private static final StudioPrincipal PRINCIPAL =
      new StudioPrincipal(USER, "user", StudioRole.USER);

  private MockMvc mockMvc;
  private CanvasQueryService queryService;
  private StorageBlobManager blobManager;
  private ResourceAccess resourceAccess;

  @BeforeEach
  void setUp() {
    queryService = mock(CanvasQueryService.class);
    blobManager = mock(StorageBlobManager.class);
    resourceAccess = mock(ResourceAccess.class);
    mockMvc =
        standaloneSetup(
                new CanvasResourceController(
                    new CanvasResourceUrlService(queryService, blobManager, resourceAccess)))
            .setCustomArgumentResolvers(new StudioPrincipalArgumentResolver())
            .setMessageConverters(
                new JacksonJsonHttpMessageConverter(StrictHttpJsonMapperTestSupport.get()))
            .setControllerAdvice(new ProblemAdvice(new MessageService()))
            .build();
    when(queryService.containsHeldBlob(CANVAS, BLOB)).thenReturn(true);
  }

  /** 原件与预览 URL 返回公开签名数据，但不暴露存储坐标。 */
  @Test
  void blobUrlResponsesHideStorageCoordinates() throws Exception {
    StoragePresignedUrlDTO signed =
        StoragePresignedUrlDTO.builder()
            .method(StorageUrlMethod.GET)
            .url("https://s3.fengwk.fun/signed")
            .headers(Map.of())
            .expiresAt(NOW.plusSeconds(600))
            .build();
    when(blobManager.presignOriginalUrl(BLOB)).thenReturn(signed);
    when(blobManager.presignPreviewUrl(BLOB)).thenReturn(signed);

    mockMvc
        .perform(
            post("/api/canvas/canvases/" + CANVAS + "/blobs/" + BLOB + "/download-url")
                .requestAttr(StudioBrowserSession.PRINCIPAL_ATTRIBUTE, PRINCIPAL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.method").value("GET"))
        .andExpect(jsonPath("$.url").value("https://s3.fengwk.fun/signed"))
        .andExpect(jsonPath("$.key").doesNotExist())
        .andExpect(jsonPath("$.bucket").doesNotExist());
    mockMvc
        .perform(
            post("/api/canvas/canvases/" + CANVAS + "/blobs/" + BLOB + "/preview-url")
                .requestAttr(StudioBrowserSession.PRINCIPAL_ATTRIBUTE, PRINCIPAL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.url").value("https://s3.fengwk.fun/signed"));
    verify(resourceAccess, times(2)).require(PRINCIPAL, Kind.CANVAS, CANVAS);
    verify(blobManager).presignOriginalUrl(BLOB);
    verify(blobManager).presignPreviewUrl(BLOB);
  }

  /** 不持有的 Blob 一律不存在；路径 UUID 格式错误被拒绝。 */
  @Test
  void rejectsUnheldBlobAndInvalidIds() throws Exception {
    UUID missingBlob = new UUID(0L, 4L);
    when(queryService.containsHeldBlob(CANVAS, missingBlob)).thenReturn(false);
    mockMvc
        .perform(
            post("/api/canvas/canvases/" + CANVAS + "/blobs/" + missingBlob + "/download-url")
                .requestAttr(StudioBrowserSession.PRINCIPAL_ATTRIBUTE, PRINCIPAL))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            post("/api/canvas/canvases/not-a-uuid/blobs/" + BLOB + "/download-url")
                .requestAttr(StudioBrowserSession.PRINCIPAL_ATTRIBUTE, PRINCIPAL))
        .andExpect(status().isBadRequest());
    verify(blobManager, never()).presignOriginalUrl(missingBlob);
  }
}
