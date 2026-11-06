package fun.fengwk.kkstudio.platform.harness.http;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionFileContentDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionFileListDTO;
import fun.fengwk.kkstudio.platform.vfs.SessionFileQueryService;
import fun.fengwk.kkstudio.platform.vfs.SessionFileQueryService.SessionFileDownload;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

class SessionFileControllerTest {
  private final UUID sessionId = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private final SessionFileQueryService service = mock(SessionFileQueryService.class);
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new SessionFileController(service)).build();

  @Test
  void listsAndReadsWithoutOwnerParameters() throws Exception {
    // 会话坐标与文本窗口直接透传，不要求产品 owner。
    HarnessSessionFileListDTO listing = new HarnessSessionFileListDTO();
    listing.setSessionId(sessionId.toString());
    listing.setPath("vfs:/sessions/" + sessionId + "/");
    listing.setEntries(List.of());
    when(service.list(sessionId, null)).thenReturn(listing);
    mvc.perform(get("/api/harness/sessions/{sessionId}/files", sessionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessionId").value(sessionId.toString()));
    HarnessSessionFileContentDTO text = new HarnessSessionFileContentDTO();
    text.setText("hello");
    when(service.readText(sessionId, "notes", 1, 20, 2)).thenReturn(text);
    mvc.perform(
            get("/api/harness/sessions/{sessionId}/files/content", sessionId)
                .param("path", "notes")
                .param("offset", "1")
                .param("limit", "20")
                .param("columnOffset", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.text").value("hello"));
    verify(service).readText(sessionId, "notes", 1, 20, 2);
  }

  @Test
  void nonTextContentKeepsTheStableNotTextReasonForTheHttpBoundary() throws Exception {
    // 服务以 validation + SESSION_FILE_NOT_TEXT 拒绝非文本内容；控制器不得吞掉或改写原因，
    // 由唯一 problem advice 翻译为 400 并保留 reason。
    when(service.readText(sessionId, "binary.bin", null, null, null))
        .thenThrow(
            DomainException.validationReason(
                SessionFileQueryService.NOT_TEXT_REASON, "file appears to be binary"));
    ServletException failure =
        assertThrows(
            ServletException.class,
            () ->
                mvc.perform(
                    get("/api/harness/sessions/{sessionId}/files/content", sessionId)
                        .param("path", "binary.bin")));
    DomainException error = assertInstanceOf(DomainException.class, failure.getCause());
    assertEquals(DomainErrorCode.VALIDATION, error.code());
    assertEquals(SessionFileQueryService.NOT_TEXT_REASON, error.reason());
  }

  @Test
  void downloadsOriginalBytesWithAttachmentHeaders() throws Exception {
    // 下载保留媒体类型、文件名及完整字节。
    when(service.download(sessionId, "report"))
        .thenReturn(
            new SessionFileDownload(
                "report.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8)));
    mvc.perform(
            get("/api/harness/sessions/{sessionId}/files/download", sessionId)
                .param("path", "report"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Disposition", startsWith("attachment;")))
        .andExpect(header().string("Content-Type", "text/plain"))
        .andExpect(content().bytes("hello".getBytes(StandardCharsets.UTF_8)));
  }
}
