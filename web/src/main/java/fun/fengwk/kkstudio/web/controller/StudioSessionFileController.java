package fun.fengwk.kkstudio.web.controller;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.platform.http.ApiResponse;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwner;
import fun.fengwk.kkstudio.platform.vfs.SessionFileQueryService;
import fun.fengwk.kkstudio.platform.vfs.SessionFileQueryService.SessionFileAccessException;
import fun.fengwk.kkstudio.platform.vfs.SessionFileQueryService.SessionFileContentException;
import fun.fengwk.kkstudio.platform.vfs.SessionFileQueryService.SessionFileDownload;
import fun.fengwk.kkstudio.platform.vfs.SessionFileQueryService.SessionFileNotFoundException;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionFileContentDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionFileListDTO;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 会话文件面板的只读 HTTP：列目录、文本内容与下载。
 *
 * <p>全部读取都先按 {@code ownerKind} + {@code ownerId} 校验 Session 归属：未知 owner、越权、跨会话路径与不存在的文件都返回 404，
 * 不区分「没有权限」与「不存在」。写操作不在这里开放——会话目录由文件工具写入。
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/files")
public class StudioSessionFileController {

  private final SessionFileQueryService queryService;

  public StudioSessionFileController(SessionFileQueryService queryService) {
    this.queryService = Objects.requireNonNull(queryService, "queryService");
  }

  /** 列出会话目录的直属条目；省略 {@code path} 表示会话根目录。 */
  @GetMapping
  public ApiResponse<HarnessSessionFileListDTO> list(
      @PathVariable String sessionId,
      @RequestParam String ownerKind,
      @RequestParam String ownerId,
      @RequestParam(required = false) String path) {
    return translate(() -> queryService.list(owner(ownerKind, ownerId), id(sessionId), path));
  }

  /** 读取会话文件的文本窗口；{@code offset} 默认 1 行，{@code limit} 默认且最大 2000 行。 */
  @GetMapping("/content")
  public ApiResponse<HarnessSessionFileContentDTO> content(
      @PathVariable String sessionId,
      @RequestParam String ownerKind,
      @RequestParam String ownerId,
      @RequestParam String path,
      @RequestParam(required = false) Integer offset,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) Integer columnOffset) {
    return translate(
        () ->
            queryService.readText(
                owner(ownerKind, ownerId), id(sessionId), path, offset, limit, columnOffset));
  }

  /** 下载会话文件的完整字节：文件名取路径末段，媒体类型取 Blob 的权威事实。 */
  @GetMapping("/download")
  public ResponseEntity<byte[]> download(
      @PathVariable String sessionId,
      @RequestParam String ownerKind,
      @RequestParam String ownerId,
      @RequestParam String path) {
    try {
      SessionFileDownload download =
          queryService.download(owner(ownerKind, ownerId), id(sessionId), path);
      return ResponseEntity.ok()
          .contentType(MediaType.parseMediaType(download.mediaType()))
          .header(
              HttpHeaders.CONTENT_DISPOSITION,
              ContentDisposition.attachment()
                  .filename(download.fileName(), StandardCharsets.UTF_8)
                  .build()
                  .toString())
          .body(download.bytes());
    } catch (SessionFileNotFoundException | SessionFileAccessException notFound) {
      throw notFound(notFound);
    }
  }

  private static <T> ApiResponse<T> translate(Supplier<T> action) {
    try {
      return ApiResponse.ok(action.get());
    } catch (SessionFileNotFoundException | SessionFileAccessException notFound) {
      throw notFound(notFound);
    } catch (SessionFileContentException unreadable) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT, unreadable.getMessage(), unreadable);
    }
  }

  private static ResponseStatusException notFound(RuntimeException error) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage(), error);
  }

  /** owner 参数格式非法（空白等）与越权不可区分：统一返回 404 而不是 500。 */
  private static SessionOwner owner(String ownerKind, String ownerId) {
    try {
      return new SessionOwner(ownerKind, ownerId);
    } catch (IllegalArgumentException malformedOwner) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "session is not owned by the given owner", malformedOwner);
    }
  }

  private static UUID id(String value) {
    return Uuids.parseCanonical(value, "sessionId");
  }
}
