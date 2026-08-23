package fun.fengwk.kkstudio.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.web.storage.FixedObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Canvas Resource 直读预签名 HTTP 边界：Resource → blobId → blob 预签名， 不暴露 bucket/key，TEXT 资源与未知
 * resource/canvas 明确拒绝。
 */
class StudioCanvasResourceControllerTest {

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
    FixedObjectProvider<StorageBlobManager> blobManagers = new FixedObjectProvider<>(blobManager);
    mockMvc =
        standaloneSetup(new StudioCanvasResourceController(queryService, blobManagers))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
            .build();
    when(queryService.findSnapshot(CANVAS)).thenReturn(Optional.of(snapshot()));
  }

  /** 原图与 preview URL 都只返回方法、URL、headers 和 expiry，不暴露 bucket/key。 */
  @Test
  void resourceUrlResponsesHideStorageCoordinates() throws Exception {
    StoragePresignedUrlDTO signed =
        StoragePresignedUrlDTO.builder()
            .method("GET")
            .url("https://s3.fengwk.fun/signed")
            .headers(Map.of())
            .expiresAt(NOW.plusSeconds(600).toString())
            .build();
    when(blobManager.presignOriginalUrl(BLOB)).thenReturn(signed);
    when(blobManager.presignPreviewUrl(BLOB)).thenReturn(signed);

    mockMvc
        .perform(post("/api/canvases/" + CANVAS + "/resources/" + MEDIA_RESOURCE + "/download-url"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.method").value("GET"))
        .andExpect(jsonPath("$.data.url").value("https://s3.fengwk.fun/signed"))
        .andExpect(jsonPath("$.data.key").doesNotExist())
        .andExpect(jsonPath("$.data.bucket").doesNotExist());
    mockMvc
        .perform(post("/api/canvases/" + CANVAS + "/resources/" + MEDIA_RESOURCE + "/preview-url"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.url").value("https://s3.fengwk.fun/signed"));
    verify(blobManager).presignOriginalUrl(BLOB);
    verify(blobManager).presignPreviewUrl(BLOB);
  }

  /** TEXT 资源没有 blob 内容，unknown canvas/resource 与非法 UUID 都映射为明确状态码。 */
  @Test
  void textResourceUnknownAndInvalidRequestsAreRejected() throws Exception {
    mockMvc
        .perform(post("/api/canvases/" + CANVAS + "/resources/" + TEXT_RESOURCE + "/download-url"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/canvases/" + CANVAS + "/resources/" + MISSING_RESOURCE + "/download-url"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            post(
                "/api/canvases/"
                    + new UUID(0L, 99L)
                    + "/resources/"
                    + MEDIA_RESOURCE
                    + "/download-url"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(post("/api/canvases/not-a-uuid/resources/" + MEDIA_RESOURCE + "/download-url"))
        .andExpect(status().isBadRequest());
    verify(blobManager, never()).presignOriginalUrl(any());
  }

  private static CanvasSnapshot snapshot() {
    CanvasDocument document = new CanvasDocument(CANVAS, "demo", 3, NOW, NOW);
    CanvasResource media =
        new CanvasResource(MEDIA_RESOURCE, CANVAS, NODE, 0, BLOB, "a.png", null, NOW);
    CanvasResource text =
        new CanvasResource(TEXT_RESOURCE, CANVAS, TEXT_NODE, 0, null, "note", "hello", NOW);
    CanvasResourceNode mediaNode =
        new CanvasResourceNode(
            NODE,
            CANVAS,
            "image",
            new CanvasTransform(1, 2, 100, 80),
            null,
            List.of(media),
            null,
            null);
    CanvasResourceNode textNode =
        new CanvasResourceNode(
            TEXT_NODE,
            CANVAS,
            "note",
            new CanvasTransform(1, 2, 100, 80),
            null,
            List.of(text),
            null,
            null);
    return new CanvasSnapshot(document, List.of(mediaNode, textNode), List.of(), List.of());
  }
}
