package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.i18n.StudioMessageService;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwner;
import fun.fengwk.kkstudio.platform.vfs.SessionFileQueryService;
import fun.fengwk.kkstudio.platform.vfs.SessionFileQueryService.SessionFileDownload;
import fun.fengwk.kkstudio.share.runtime.HarnessSessionFileContentDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessSessionFileEntryDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessSessionFileListDTO;
import fun.fengwk.kkstudio.web.advice.ApiResponseStatusAdvice;
import fun.fengwk.kkstudio.web.advice.StudioDomainErrorAdvice;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** 会话文件只读 HTTP 契约：列目录、文本窗口、下载与按归属授权的失败语义。 */
class StudioSessionFileControllerTest {

  private static final UUID SESSION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final SessionOwner OWNER = new SessionOwner("CHAT", "chat-1");

  private SessionFileQueryService queryService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    queryService = mock(SessionFileQueryService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new StudioSessionFileController(queryService))
            .setControllerAdvice(
                new ApiResponseStatusAdvice(),
                new StudioDomainErrorAdvice(new StudioMessageService()))
            .build();
  }

  @Test
  void listsSessionDirectoryEntries() throws Exception {
    HarnessSessionFileListDTO dto = new HarnessSessionFileListDTO();
    dto.setSessionId(SESSION_ID.toString());
    dto.setPath("vfs:/sessions/" + SESSION_ID + "/");
    dto.setEntries(List.of(fileEntry("MEMORY.md", 2, 12L), directoryEntry("tmp")));
    when(queryService.list(eq(OWNER), eq(SESSION_ID), eq(null))).thenReturn(dto);

    mockMvc
        .perform(
            get("/api/sessions/{sessionId}/files", SESSION_ID)
                .param("ownerKind", "CHAT")
                .param("ownerId", "chat-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries[0].name").value("MEMORY.md"))
        .andExpect(jsonPath("$.entries[0].directory").value(false))
        .andExpect(jsonPath("$.entries[0].revision").value(2))
        .andExpect(jsonPath("$.entries[0].sizeBytes").value(12))
        .andExpect(jsonPath("$.entries[1].name").value("tmp/"))
        .andExpect(jsonPath("$.entries[1].directory").value(true))
        .andExpect(jsonPath("$.entries[1].sizeBytes").doesNotExist());
  }

  @Test
  void returnsBoundedTextWindow() throws Exception {
    HarnessSessionFileContentDTO dto = new HarnessSessionFileContentDTO();
    dto.setSessionId(SESSION_ID.toString());
    dto.setPath("vfs:/sessions/" + SESSION_ID + "/notes.txt");
    dto.setSizeBytes(6);
    dto.setRevision(3);
    dto.setUpdatedAt(1_700_000_000_000L);
    dto.setText("path: vfs:/sessions/x/notes.txt\n\n1|hello");
    when(queryService.readText(
            eq(OWNER),
            eq(SESSION_ID),
            eq("vfs:/sessions/" + SESSION_ID + "/notes.txt"),
            eq(1),
            eq(20),
            eq(null)))
        .thenReturn(dto);

    mockMvc
        .perform(
            get("/api/sessions/{sessionId}/files/content", SESSION_ID)
                .param("ownerKind", "CHAT")
                .param("ownerId", "chat-1")
                .param("path", "vfs:/sessions/" + SESSION_ID + "/notes.txt")
                .param("offset", "1")
                .param("limit", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revision").value(3))
        .andExpect(jsonPath("$.sizeBytes").value(6))
        .andExpect(jsonPath("$.text").value("path: vfs:/sessions/x/notes.txt\n\n1|hello"));
  }

  @Test
  void downloadsTheCompleteFileAsAnAttachment() throws Exception {
    when(queryService.download(eq(OWNER), eq(SESSION_ID), eq("vfs:/sessions/x/report.txt")))
        .thenReturn(
            new SessionFileDownload(
                "report.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8)));

    mockMvc
        .perform(
            get("/api/sessions/{sessionId}/files/download", SESSION_ID)
                .param("ownerKind", "CHAT")
                .param("ownerId", "chat-1")
                .param("path", "vfs:/sessions/x/report.txt"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Disposition", startsWith("attachment;")))
        .andExpect(header().string("Content-Type", "text/plain"));
  }

  /** 未知 owner、越权与不存在的文件都返回 404：不暴露会话内容的可枚举性。 */
  @Test
  void ownershipAndMissingFilesAreIndistinguishable() throws Exception {
    when(queryService.list(eq(OWNER), eq(SESSION_ID), eq(null)))
        .thenThrow(
            DomainException.notFound("session_file", "session is not owned by the given owner"));

    mockMvc
        .perform(
            get("/api/sessions/{sessionId}/files", SESSION_ID)
                .param("ownerKind", "CHAT")
                .param("ownerId", "chat-1"))
        .andExpect(status().isNotFound());

    when(queryService.download(eq(OWNER), eq(SESSION_ID), eq("vfs:/sessions/x/missing.txt")))
        .thenThrow(
            DomainException.notFound("session_file", "No such file: vfs:/sessions/x/missing.txt"));

    mockMvc
        .perform(
            get("/api/sessions/{sessionId}/files/download", SESSION_ID)
                .param("ownerKind", "CHAT")
                .param("ownerId", "chat-1")
                .param("path", "vfs:/sessions/x/missing.txt"))
        .andExpect(status().isNotFound());
  }

  /** ownerId 格式非法时与「不存在」一致返回 404，而不是 500。 */
  @Test
  void malformedOwnerIdIsReportedAsNotFound() throws Exception {
    mockMvc
        .perform(
            get("/api/sessions/{sessionId}/files", SESSION_ID)
                .param("ownerKind", "CHAT")
                .param("ownerId", "  "))
        .andExpect(status().isNotFound());
  }

  /** 二进制或超预算内容返回 validation，不返回伪文本。 */
  @Test
  void unreadableContentIsRejected() throws Exception {
    when(queryService.readText(
            eq(OWNER),
            eq(SESSION_ID),
            eq("vfs:/sessions/x/blob.bin"),
            eq(null),
            eq(null),
            eq(null)))
        .thenThrow(DomainException.validation("session_file", "content is not valid UTF-8 text"));

    mockMvc
        .perform(
            get("/api/sessions/{sessionId}/files/content", SESSION_ID)
                .param("ownerKind", "CHAT")
                .param("ownerId", "chat-1")
                .param("path", "vfs:/sessions/x/blob.bin"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
  }

  @Test
  void ownerParametersAreRequired() throws Exception {
    mockMvc
        .perform(get("/api/sessions/{sessionId}/files", SESSION_ID))
        .andExpect(status().isBadRequest());
  }

  private static HarnessSessionFileEntryDTO fileEntry(String name, int revision, long size) {
    HarnessSessionFileEntryDTO entry = new HarnessSessionFileEntryDTO();
    entry.setName(name);
    entry.setPath(name);
    entry.setDirectory(false);
    entry.setSizeBytes(size);
    entry.setRevision(revision);
    entry.setUpdatedAt(1_700_000_000_000L);
    return entry;
  }

  private static HarnessSessionFileEntryDTO directoryEntry(String name) {
    HarnessSessionFileEntryDTO entry = new HarnessSessionFileEntryDTO();
    entry.setName(name + "/");
    entry.setPath(name + "/");
    entry.setDirectory(true);
    return entry;
  }
}
