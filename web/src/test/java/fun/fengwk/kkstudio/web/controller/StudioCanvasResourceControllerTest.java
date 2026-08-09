package fun.fengwk.kkstudio.web.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.core.studio.resource.CanvasPresignedUrl;
import fun.fengwk.kkstudio.core.studio.resource.CanvasResourceStorageException;
import fun.fengwk.kkstudio.core.studio.resource.CanvasResourceStorageService;
import fun.fengwk.kkstudio.core.studio.resource.CanvasUploadReservation;
import fun.fengwk.kkstudio.studio.canvas.CanvasResource;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;

import java.time.Instant;
import java.util.Map;

class StudioCanvasResourceControllerTest {

  private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");

  private MockMvc mockMvc;
  private CanvasResourceStorageService storageService;

  @BeforeEach
  void setUp() {
    storageService = mock(CanvasResourceStorageService.class);
    mockMvc =
        standaloneSetup(new StudioCanvasResourceController(storageService))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
            .build();
  }

  /** Reserve 解析严格字符串字段，并用 Canvas DTO 隐藏 bucket/key。 */
  @Test
  void reserveMapsRequestWithoutExposingStorageCoordinates() throws Exception {
    when(storageService.reserve(42L, CanvasResourceKind.IMAGE, "a.png", "image/png", 3L))
        .thenReturn(
            new CanvasUploadReservation(
                99L,
                "PUT",
                "https://s3.fengwk.fun/signed",
                Map.of("Content-Type", "image/png", "If-None-Match", "*"),
                NOW.plusSeconds(600).toString()));

    mockMvc
        .perform(
            post("/api/canvases/42/uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"kind":"IMAGE","filename":"a.png","mediaType":"image/png","size":"3"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("CREATED"))
        .andExpect(jsonPath("$.data.uploadId").value("99"))
        .andExpect(jsonPath("$.data.method").value("PUT"))
        .andExpect(jsonPath("$.data.url").value("https://s3.fengwk.fun/signed"))
        .andExpect(jsonPath("$.data.headers['If-None-Match']").value("*"))
        .andExpect(jsonPath("$.data.key").doesNotExist())
        .andExpect(jsonPath("$.data.bucket").doesNotExist());
    verify(storageService).reserve(42L, CanvasResourceKind.IMAGE, "a.png", "image/png", 3L);
  }

  /** 非规范 bigint、TEXT 和未知字段在 HTTP 边界直接拒绝。 */
  @Test
  void reserveRejectsInvalidRequestShape() throws Exception {
    mockMvc
        .perform(
            post("/api/canvases/42/uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"kind":"IMAGE","filename":"a.png","mediaType":"image/png","size":"03"}
                    """))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/canvases/42/uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"kind":"TEXT","filename":"a.md","mediaType":"text/markdown","size":"3"}
                    """))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/canvases/42/uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"kind":"IMAGE","filename":"a.png","mediaType":"image/png","size":"3",
                     "key":"client-controlled"}
                    """))
        .andExpect(status().isBadRequest());
  }

  /** Complete 返回 Resource DTO，not-found/expired 映射为明确状态码。 */
  @Test
  void completeAndStorageErrorsMapToCanvasApi() throws Exception {
    when(storageService.complete(42L, 99L))
        .thenReturn(
            new CanvasResource(
                99L,
                42L,
                CanvasResourceKind.IMAGE,
                "image/png",
                "a.png",
                3L,
                null,
                "{\"width\":16}",
                NOW));
    mockMvc
        .perform(post("/api/canvases/42/uploads/99/complete"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value("99"))
        .andExpect(jsonPath("$.data.mediaType").value("image/png"));

    when(storageService.complete(42L, 100L))
        .thenThrow(
            new CanvasResourceStorageException(
                CanvasResourceStorageException.Reason.NOT_FOUND, "missing"));
    mockMvc.perform(post("/api/canvases/42/uploads/100/complete")).andExpect(status().isNotFound());

    when(storageService.complete(42L, 101L))
        .thenThrow(
            new CanvasResourceStorageException(
                CanvasResourceStorageException.Reason.EXPIRED, "expired"));
    mockMvc.perform(post("/api/canvases/42/uploads/101/complete")).andExpect(status().isGone());
  }

  /** 原件与 preview URL 都只返回方法、URL、headers 和 expiry。 */
  @Test
  void resourceUrlResponsesHideStorageCoordinates() throws Exception {
    CanvasPresignedUrl url =
        new CanvasPresignedUrl(
            "GET", "https://s3.fengwk.fun/signed", Map.of(), NOW.plusSeconds(600).toString());
    when(storageService.originalUrl(42L, 99L)).thenReturn(url);
    when(storageService.previewUrl(42L, 99L)).thenReturn(url);

    mockMvc
        .perform(post("/api/canvases/42/resources/99/download-url"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.method").value("GET"))
        .andExpect(jsonPath("$.data.key").doesNotExist())
        .andExpect(jsonPath("$.data.bucket").doesNotExist());
    mockMvc
        .perform(post("/api/canvases/42/resources/99/preview-url"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.url").value("https://s3.fengwk.fun/signed"));
  }
}
