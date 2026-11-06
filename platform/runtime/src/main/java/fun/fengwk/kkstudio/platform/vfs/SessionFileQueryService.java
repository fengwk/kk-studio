package fun.fengwk.kkstudio.platform.vfs;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionFileContentDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionFileEntryDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionFileListDTO;
import fun.fengwk.kkstudio.platform.harness.read.PlatformReadException;
import fun.fengwk.kkstudio.platform.harness.read.TextReadWindow;
import fun.fengwk.kkstudio.platform.storage.ReadDeadline;
import fun.fengwk.kkstudio.platform.storage.model.SessionFile;
import fun.fengwk.kkstudio.platform.storage.service.SessionFileStore;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 会话文件面板的只读查询：列目录、读取文本窗口与下载原始字节。
 *
 * <p>先验证 Session 存在。路径使用 {@code vfs:/sessions/<sessionId>/...} 坐标，且必须属于请求的 Session，绝不跨会话读取。
 */
@Service
public class SessionFileQueryService {

  /** 面板读取的扫描截止：只约束平台侧文本窗口与字节读取，不改变文件内容。 */
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

  /** 内容不可作为文本预览的稳定失败原因：面板据此提示保留下载，而不是显示通用预览失败。 */
  public static final String NOT_TEXT_REASON = "SESSION_FILE_NOT_TEXT";

  private final ObjectProvider<HarnessRuntime> runtimes;
  private final SessionFileStore fileStore;
  private final StorageBlobManager blobManager;

  public SessionFileQueryService(
      ObjectProvider<HarnessRuntime> runtimes,
      SessionFileStore fileStore,
      StorageBlobManager blobManager) {
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    this.fileStore = Objects.requireNonNull(fileStore, "fileStore");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
  }

  /** 列出会话目录的直属条目；{@code directory} 为空表示会话根目录。 */
  public HarnessSessionFileListDTO list(UUID sessionId, String directory) {
    requireSession(sessionId);
    String relative = relativeDirectory(sessionId, directory);
    String prefix = relative.isEmpty() ? "" : relative + "/";
    List<SessionFile> files = fileStore.list(sessionId, prefix);
    List<HarnessSessionFileEntryDTO> entries = new ArrayList<>();
    List<String> seenDirectories = new ArrayList<>();
    for (SessionFile file : files) {
      String remainder = file.path().substring(prefix.length());
      int slash = remainder.indexOf('/');
      if (slash < 0) {
        entries.add(fileEntry(file, remainder, blobManager.getBlob(file.blobId())));
        continue;
      }
      String name = remainder.substring(0, slash);
      if (!seenDirectories.contains(name)) {
        seenDirectories.add(name);
        entries.add(directoryEntry(name, prefix + name + "/"));
      }
    }
    entries.sort((left, right) -> left.getName().compareTo(right.getName()));
    HarnessSessionFileListDTO dto = new HarnessSessionFileListDTO();
    dto.setSessionId(sessionId.toString());
    dto.setPath(sessionPath(sessionId, relative));
    dto.setEntries(entries);
    return dto;
  }

  /**
   * 读取会话文件的文本窗口；二进制内容以确定性错误拒绝，绝不返回伪文本。
   *
   * <p>不可作为文本的内容以 validation + {@value #NOT_TEXT_REASON} 拒绝，前端因此只停用预览、保留下载入口；非法窗口参数是普通
   * validation，读取超时不伪装成请求校验失败。
   */
  public HarnessSessionFileContentDTO readText(
      UUID sessionId, String path, Integer offset, Integer limit, Integer columnOffset) {
    requireSession(sessionId);
    String relative = relativeFile(sessionId, path);
    SessionFile file =
        fileStore
            .find(sessionId, relative)
            .orElseThrow(() -> fileNotFound(sessionPath(sessionId, relative)));
    byte[] bytes =
        fileStore
            .read(sessionId, relative)
            .orElseThrow(() -> fileNotFound(sessionPath(sessionId, relative)));
    String text;
    try {
      text =
          TextReadWindow.format(
              bytes, offset, limit, columnOffset, sessionPath(sessionId, relative), deadline());
    } catch (PlatformReadException failure) {
      throw readFailure(failure);
    }
    HarnessSessionFileContentDTO dto = new HarnessSessionFileContentDTO();
    dto.setSessionId(sessionId.toString());
    dto.setPath(sessionPath(sessionId, relative));
    dto.setSizeBytes(bytes.length);
    dto.setRevision(file.revision());
    dto.setUpdatedAt(file.updatedAt());
    dto.setText(text);
    return dto;
  }

  /** 读取会话文件的原始字节与权威媒体类型，供下载端点直接返回。 */
  public SessionFileDownload download(UUID sessionId, String path) {
    requireSession(sessionId);
    String relative = relativeFile(sessionId, path);
    SessionFile file =
        fileStore
            .find(sessionId, relative)
            .orElseThrow(() -> fileNotFound(sessionPath(sessionId, relative)));
    byte[] bytes =
        fileStore
            .read(sessionId, relative)
            .orElseThrow(() -> fileNotFound(sessionPath(sessionId, relative)));
    StorageBlob blob = blobManager.getBlob(file.blobId());
    String mediaType =
        blob == null || blob.getMediaType() == null || blob.getMediaType().isBlank()
            ? "application/octet-stream"
            : blob.getMediaType();
    return new SessionFileDownload(fileName(relative), mediaType, bytes);
  }

  /** 下载结果：文件名取会话文件末段，字节为完整内容。 */
  public record SessionFileDownload(String fileName, String mediaType, byte[] bytes) {}

  /** 不存在的 Session 不能被当作空目录。 */
  private void requireSession(UUID sessionId) {
    Objects.requireNonNull(sessionId, "sessionId");
    HarnessRuntime runtime = runtimes.getIfAvailable();
    if (runtime == null) {
      throw new IllegalStateException("harness runtime is not available in this deployment");
    }
    if (!runtime.sessionExists(sessionId)) {
      throw accessDenied("session does not exist");
    }
  }

  /** 目录参数：空表示会话根；其余必须是本会话的目录坐标。 */
  private static String relativeDirectory(UUID sessionId, String directory) {
    if (directory == null || directory.isBlank()) {
      return "";
    }
    return relativePath(sessionId, directory);
  }

  /** 文件参数：必须是本会话下的文件路径。 */
  private static String relativeFile(UUID sessionId, String path) {
    if (path == null || path.isBlank()) {
      throw accessDenied("file path is required");
    }
    String relative = relativePath(sessionId, path);
    if (relative.isEmpty()) {
      throw accessDenied("file path must point to a file");
    }
    return relative;
  }

  private static String relativePath(UUID sessionId, String path) {
    VfsPath target;
    try {
      target = VfsPath.parse(path);
    } catch (IllegalArgumentException invalid) {
      throw accessDenied(invalid.getMessage());
    }
    if (target.kind() != VfsPath.Kind.SESSION_DIR && target.kind() != VfsPath.Kind.SESSION_FILE) {
      throw accessDenied("path must be inside the session directory: " + path);
    }
    if (!sessionId.equals(target.sessionId())) {
      throw accessDenied("path does not belong to the requested session");
    }
    return target.sessionPath();
  }

  private static HarnessSessionFileEntryDTO fileEntry(
      SessionFile file, String name, StorageBlob blob) {
    HarnessSessionFileEntryDTO entry = new HarnessSessionFileEntryDTO();
    entry.setName(name);
    entry.setPath(file.path());
    entry.setDirectory(false);
    entry.setSizeBytes(blob == null ? null : blob.getSizeBytes());
    entry.setRevision(file.revision());
    entry.setUpdatedAt(file.updatedAt());
    return entry;
  }

  private static HarnessSessionFileEntryDTO directoryEntry(String name, String path) {
    HarnessSessionFileEntryDTO entry = new HarnessSessionFileEntryDTO();
    entry.setName(name + "/");
    entry.setPath(path);
    entry.setDirectory(true);
    return entry;
  }

  private static String sessionPath(UUID sessionId, String relative) {
    String base = "vfs:/sessions/" + sessionId + "/";
    return relative == null || relative.isEmpty() ? base : base + relative;
  }

  private static String fileName(String relative) {
    int slash = relative.lastIndexOf('/');
    return slash < 0 ? relative : relative.substring(slash + 1);
  }

  private static ReadDeadline deadline() {
    return ReadDeadline.after(READ_TIMEOUT);
  }

  private static DomainException accessDenied(String message) {
    return DomainException.notFound("session_file", message);
  }

  /** 文本窗口失败只在非文本内容时携带稳定 reason；参数错误是普通 validation，超时保持不可用语义。 */
  private static RuntimeException readFailure(PlatformReadException failure) {
    return switch (failure.kind()) {
      case NOT_TEXT -> DomainException.validationReason(NOT_TEXT_REASON, failure.getMessage());
      case INVALID_ARGUMENT -> DomainException.validation("session_file", failure.getMessage());
      case TIMEOUT -> DomainException.unavailable(
          "SESSION_FILE_READ_TIMEOUT", failure.getMessage(), failure);
      case READ_FAILURE -> new IllegalStateException("session file cannot be read", failure);
    };
  }

  private static DomainException fileNotFound(String path) {
    return DomainException.notFound("session_file", "No such file: " + path);
  }
}
