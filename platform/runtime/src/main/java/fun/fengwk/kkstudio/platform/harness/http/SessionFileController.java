package fun.fengwk.kkstudio.platform.harness.http;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionFileContentDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionFileListDTO;
import fun.fengwk.kkstudio.platform.vfs.SessionFileQueryService;
import fun.fengwk.kkstudio.platform.vfs.SessionFileQueryService.SessionFileDownload;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** 会话目录、文本窗口和原始文件的只读 HTTP 入口。 */
@RestController
@RequestMapping("/api/harness/sessions/{sessionId}/files")
public class SessionFileController {
  private final SessionFileQueryService queryService;

  public SessionFileController(SessionFileQueryService queryService) {
    this.queryService = Objects.requireNonNull(queryService, "queryService");
  }

  @GetMapping
  public HarnessSessionFileListDTO list(
      @PathVariable UUID sessionId, @RequestParam(required = false) String path) {
    return queryService.list(sessionId, path);
  }

  @GetMapping("/content")
  public HarnessSessionFileContentDTO content(
      @PathVariable UUID sessionId,
      @RequestParam String path,
      @RequestParam(required = false) Integer offset,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) Integer columnOffset) {
    return queryService.readText(sessionId, path, offset, limit, columnOffset);
  }

  @GetMapping("/download")
  public ResponseEntity<byte[]> download(@PathVariable UUID sessionId, @RequestParam String path) {
    SessionFileDownload download = queryService.download(sessionId, path);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(download.mediaType()))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(download.bytes());
  }
}
