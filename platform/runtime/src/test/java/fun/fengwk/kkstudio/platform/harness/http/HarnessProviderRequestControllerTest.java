package fun.fengwk.kkstudio.platform.harness.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.harness.builtin.query.ProviderRequestPreviewService;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessProviderRequestPreviewDTO;

import java.time.Instant;
import java.util.UUID;

/** 历史 Provider 请求预览端点：路径绑定、成功投影与领域冲突的直通。 */
class HarnessProviderRequestControllerTest {

  private static final UUID SESSION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID ENTRY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  private final ProviderRequestPreviewService service = mock(ProviderRequestPreviewService.class);
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new HarnessProviderRequestController(service)).build();

  @Test
  void mapsSessionAndEntryPathToHistoricalPreview() throws Exception {
    HarnessProviderRequestPreviewDTO preview = new HarnessProviderRequestPreviewDTO();
    preview.setKind(HarnessProviderRequestPreviewDTO.Kind.HISTORICAL_REQUEST_PREVIEW);
    preview.setGeneratedAt(Instant.ofEpochMilli(1_700_000_000_000L));
    preview.setProviderType("OPENAI");
    preview.setModelName("gpt-5");
    preview.setBodyByteSize(2);
    preview.setBodyJson("{}");
    preview.setSourceHeadEntryId(ENTRY_ID.toString());
    preview.setNotice(HarnessProviderRequestPreviewDTO.HISTORICAL_NOTICE);
    when(service.previewHistorical(SESSION_ID, ENTRY_ID)).thenReturn(preview);

    mvc.perform(
            get(
                "/api/harness/builtin/sessions/{sessionId}/entries/{entryId}/provider-request",
                SESSION_ID,
                ENTRY_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("HISTORICAL_REQUEST_PREVIEW"))
        .andExpect(jsonPath("$.providerType").value("OPENAI"))
        .andExpect(jsonPath("$.modelName").value("gpt-5"))
        .andExpect(jsonPath("$.bodyByteSize").value(2))
        .andExpect(jsonPath("$.bodyJson").value("{}"))
        .andExpect(jsonPath("$.sourceHeadEntryId").value(ENTRY_ID.toString()))
        .andExpect(jsonPath("$.notice").value(HarnessProviderRequestPreviewDTO.HISTORICAL_NOTICE));

    verify(service).previewHistorical(SESSION_ID, ENTRY_ID);
  }

  @Test
  void domainConflictPassesThroughWithoutHttpBoundaryTranslation() {
    DomainException conflict = DomainException.conflict("PREVIEW_UNSUPPORTED", "unsupported");
    when(service.previewHistorical(SESSION_ID, ENTRY_ID)).thenThrow(conflict);

    ServletException failure =
        assertThrows(
            ServletException.class,
            () ->
                mvc.perform(
                    get(
                        "/api/harness/builtin/sessions/{sessionId}/entries/{entryId}/provider-request",
                        SESSION_ID,
                        ENTRY_ID)));
    DomainException error = assertInstanceOf(DomainException.class, failure.getCause());
    assertSame(conflict, error);
    assertEquals(DomainErrorCode.CONFLICT, error.code());
    assertEquals("PREVIEW_UNSUPPORTED", error.reason());
  }
}
