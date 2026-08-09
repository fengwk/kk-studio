package fun.fengwk.kkstudio.web.controller;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.core.ai.runtime.resource.ManagedResourceDownload;
import fun.fengwk.kkstudio.core.ai.runtime.resource.ManagedResourceDownloadService;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Harness managed Resource 下载面。
 *
 * <p>调用方只提交内容身份（mediaType/name/size/sha256），Core 重建并读取存储自身拥有的 canonical 引用，绝不接受浏览器传入任意 file
 * URI。响应统一作为 attachment 下载并禁止 MIME sniff，避免不可信 Tool 输出在应用同源下执行。
 */
@RestController
@RequestMapping("/api/ai/runtime/resources")
public class StudioHarnessResourceController {

  private static final String IMMUTABLE_PRIVATE_CACHE = "private, max-age=31536000, immutable";

  private final ManagedResourceDownloadService downloadService;

  public StudioHarnessResourceController(ManagedResourceDownloadService downloadService) {
    this.downloadService = Objects.requireNonNull(downloadService, "downloadService");
  }

  /** 按内容身份读取完整 Tool 输出。 */
  @GetMapping("/{sha256}")
  public ResponseEntity<byte[]> download(
      @PathVariable String sha256,
      @RequestParam String mediaType,
      @RequestParam long size,
      @RequestParam(required = false) String name) {
    ManagedResourceDownload resource = downloadService.download(sha256, mediaType, size, name);
    byte[] content = resource.getContent();
    ContentDisposition disposition =
        ContentDisposition.attachment()
            .filename(resource.getFilename(), StandardCharsets.UTF_8)
            .build();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(resource.getMediaType()))
        .contentLength(content.length)
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .header(HttpHeaders.CACHE_CONTROL, IMMUTABLE_PRIVATE_CACHE)
        .header(HttpHeaders.ETAG, "\"" + resource.getSha256() + "\"")
        .header("X-Content-Type-Options", "nosniff")
        .body(content);
  }
}
