package fun.fengwk.kkstudio.web.canvas;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.http.CanvasResourceController;
import fun.fengwk.kkstudio.canvas.infra.platform.CanvasResourceUrlService;
import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasQueryService;
import fun.fengwk.kkstudio.platform.i18n.MessageService;
import fun.fengwk.kkstudio.platform.storage.dto.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.platform.storage.dto.StorageUrlMethod;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;
import fun.fengwk.kkstudio.web.advice.ProblemAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Canvas Resource 直读预签名 HTTP 边界：Resource → blobId → blob 预签名， 不暴露 bucket/key，TEXT 资源与未知
 * resource/canvas 明确拒绝。
 */
class CanvasResourceControllerTest {

  private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");
  private static final UUID CANVAS = new UUID(0L, 1L);
  private static final UUID NODE = new UUID(0L, 2L);
  private static final UUID BLOB = new UUID(0L, 3L);
  private static final UUID MEDIA_RESOURCE = new UUID(0L, 4L);
  private static final UUID TEXT_RESOURCE = new UUID(0L, 5L);
  private static final UUID MISSING_RESOURCE = new UUID(0L, 6L);
  private static final UUID TEXT_NODE = new UUID(0L, 7L);

  private MockMvc mockMvc;
  private CanvasQueryService queryService;
  private StorageBlobManager blobManager;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    queryService = mock(CanvasQueryService.class);
    blobManager = mock(StorageBlobManager.class);
    mockMvc =
        standaloneSetup(
                new CanvasResourceController(
                    new CanvasResourceUrlService(queryService, blobManager)))
            .setMessageConverters(
                new JacksonJsonHttpMessageConverter(StrictHttpJsonMapperTestSupport.get()))
            .setControllerAdvice(new ProblemAdvice(new MessageService()))
            .build();
    CanvasResource media =
        new CanvasResource(MEDIA_RESOURCE, CANVAS, NODE, 0, BLOB, "a.png", null, NOW);
    CanvasResource text =
        new CanvasResource(TEXT_RESOURCE, CANVAS, TEXT_NODE, 0, null, "note", "hello", NOW);
    when(queryService.findResource(CANVAS, MEDIA_RESOURCE)).thenReturn(Optional.of(media));
    when(queryService.findResource(CANVAS, TEXT_RESOURCE)).thenReturn(Optional.of(text));
    when(queryService.findResource(CANVAS, MISSING_RESOURCE)).thenReturn(Optional.empty());
    when(queryService.findDocument(CANVAS)).thenReturn(Optional.of(mock(CanvasDocument.class)));
  }

  /** 原图与 preview URL 都只返回方法、URL、headers 和 expiry，不暴露 bucket/key。 */
  @Test
  void resourceUrlResponsesHideStorageCoordinates() throws Exception {
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
            post(
                "/api/canvas/canvases/"
                    + CANVAS
                    + "/resources/"
                    + MEDIA_RESOURCE
                    + "/download-url"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.method").value("GET"))
        .andExpect(jsonPath("$.url").value("https://s3.fengwk.fun/signed"))
        .andExpect(jsonPath("$.key").doesNotExist())
        .andExpect(jsonPath("$.bucket").doesNotExist());
    mockMvc
        .perform(
            post(
                "/api/canvas/canvases/" + CANVAS + "/resources/" + MEDIA_RESOURCE + "/preview-url"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.url").value("https://s3.fengwk.fun/signed"));
    verify(blobManager).presignOriginalUrl(BLOB);
    verify(blobManager).presignPreviewUrl(BLOB);
  }

  /** TEXT 资源没有 blob 内容，unknown canvas/resource 与非法 UUID 都映射为明确状态码。 */
  @Test
  void textResourceUnknownAndInvalidRequestsAreRejected() throws Exception {
    mockMvc
        .perform(
            post(
                "/api/canvas/canvases/" + CANVAS + "/resources/" + TEXT_RESOURCE + "/download-url"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post(
                "/api/canvas/canvases/"
                    + CANVAS
                    + "/resources/"
                    + MISSING_RESOURCE
                    + "/download-url"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            post(
                "/api/canvas/canvases/"
                    + new UUID(0L, 99L)
                    + "/resources/"
                    + MEDIA_RESOURCE
                    + "/download-url"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            post("/api/canvas/canvases/not-a-uuid/resources/" + MEDIA_RESOURCE + "/download-url"))
        .andExpect(status().isBadRequest());
    verify(blobManager, never()).presignOriginalUrl(any());
  }
}
