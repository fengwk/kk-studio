package fun.fengwk.kkstudio.web.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.platform.harness.resource.ManagedResourceDownload;
import fun.fengwk.kkstudio.platform.harness.resource.ManagedResourceDownloadService;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

/** Managed Resource 完整输出下载 HTTP 契约。 */
@AutoConfigureMockMvc
class StudioHarnessResourceControllerTest extends WebPostgresTestSupport {

  private static final String SHA =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ManagedResourceDownloadService downloadService;

  @Test
  void downloadsOnlyTheStoreOwnedContentAddressedResourceAsAnAttachment() throws Exception {
    byte[] payload = "abc".getBytes();
    ManagedResourceDownload resource =
        new ManagedResourceDownload("result.txt", "text/plain", SHA, payload);
    when(downloadService.download(SHA, "text/plain", 3L, "result.txt")).thenReturn(resource);

    mockMvc
        .perform(
            get("/api/ai/runtime/resources/{sha256}", SHA)
                .param("mediaType", "text/plain")
                .param("size", "3")
                .param("name", "result.txt"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.TEXT_PLAIN))
        .andExpect(content().bytes(payload))
        .andExpect(
            header().string(HttpHeaders.CONTENT_DISPOSITION, Matchers.containsString("attachment")))
        .andExpect(
            header().string(HttpHeaders.CONTENT_DISPOSITION, Matchers.containsString("result.txt")))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string(HttpHeaders.ETAG, "\"" + SHA + "\""));

    verify(downloadService).download(SHA, "text/plain", 3L, "result.txt");
  }
}
