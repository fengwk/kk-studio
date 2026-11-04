package fun.fengwk.kkstudio.platform.vfs;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.platform.harness.read.PlatformReadException;
import fun.fengwk.kkstudio.platform.harness.read.TextReadWindow;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwner;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwnership;
import fun.fengwk.kkstudio.platform.storage.ReadDeadline;
import fun.fengwk.kkstudio.platform.storage.model.SessionFile;
import fun.fengwk.kkstudio.platform.storage.service.SessionFileStore;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionFileContentDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionFileEntryDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionFileListDTO;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 会话文件面板的只读查询：列目录、读取文本窗口与下载原始字节。
 *
 * <p>授权按会话归属进行：调用方给出产品 owner（kind + id），只有该 owner 实际拥有该 Session 时才返回内容；否则与不存在一样失败。 路径使用 {@code
 * vfs:/sessions/<sessionId>/...} 坐标，且必须属于请求的 Session，绝不跨会话读取。
 */
@Service
public class SessionFileQueryService {

  /** 面板读取的扫描截止：只约束平台侧文本窗口与字节读取，不改变文件内容。 */
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

  private final List<SessionOwnership> ownerships;
  private final SessionFileStore fileStore;
  private final StorageBlobManager blobManager;

  public SessionFileQueryService(
      List<SessionOwnership> ownerships,
      SessionFileStore fileStore,
      StorageBlobManager blobManager) {
    this.ownerships = List.copyOf(Objects.requireNonNull(ownerships, "ownerships"));
    this.fileStore = Objects.requireNonNull(fileStore, "fileStore");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
  }

  /** 列出会话目录的直属条目；{@code directory} 为空表示会话根目录。 */
  public HarnessSessionFileListDTO list(SessionOwner owner, UUID sessionId, String directory) {
    requireOwned(owner, sessionId);
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

  /** 读取会话文件的文本窗口；二进制内容以确定性错误拒绝，绝不返回伪文本。 */
  public HarnessSessionFileContentDTO readText(
      SessionOwner owner,
      UUID sessionId,
      String path,
      Integer offset,
      Integer limit,
      Integer columnOffset) {
    requireOwned(owner, sessionId);
    String relative = relativeFile(sessionId, path);
    SessionFile file =
        fileStore
            .find(sessionId, relative)
            .orElseThrow(() -> new SessionFileNotFoundException(sessionPath(sessionId, relative)));
    byte[] bytes =
        fileStore
            .read(sessionId, relative)
            .orElseThrow(() -> new SessionFileNotFoundException(sessionPath(sessionId, relative)));
    String text;
    try {
      text =
          TextReadWindow.format(
              bytes, offset, limit, columnOffset, sessionPath(sessionId, relative), deadline());
    } catch (PlatformReadException invalid) {
      throw new SessionFileContentException(invalid.getMessage());
    }
    HarnessSessionFileContentDTO dto = new HarnessSessionFileContentDTO();
    dto.setSessionId(sessionId.toString());
    dto.setPath(sessionPath(sessionId, relative));
    dto.setSizeBytes(bytes.length);
    dto.setRevision(file.revision());
    dto.setUpdatedAt(file.updatedAt().toEpochMilli());
    dto.setText(text);
    return dto;
  }

  /** 读取会话文件的原始字节与权威媒体类型，供下载端点直接返回。 */
  public SessionFileDownload download(SessionOwner owner, UUID sessionId, String path) {
    requireOwned(owner, sessionId);
    String relative = relativeFile(sessionId, path);
    SessionFile file =
        fileStore
            .find(sessionId, relative)
            .orElseThrow(() -> new SessionFileNotFoundException(sessionPath(sessionId, relative)));
    byte[] bytes =
        fileStore
            .read(sessionId, relative)
            .orElseThrow(() -> new SessionFileNotFoundException(sessionPath(sessionId, relative)));
    StorageBlob blob = blobManager.getBlob(file.blobId());
    String mediaType =
        blob == null || blob.getMediaType() == null || blob.getMediaType().isBlank()
            ? "application/octet-stream"
            : blob.getMediaType();
    return new SessionFileDownload(fileName(relative), mediaType, bytes);
  }

  /** 下载结果：文件名取会话文件末段，字节为完整内容。 */
  public record SessionFileDownload(String fileName, String mediaType, byte[] bytes) {}

  /** 校验产品 owner 实际拥有该 Session；未知 owner kind、越权与不存在给出同一种失败。 */
  private void requireOwned(SessionOwner owner, UUID sessionId) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(sessionId, "sessionId");
    SessionOwnership ownership =
        ownerships.stream()
            .filter(candidate -> owner.kind().equals(candidate.kind()))
            .findFirst()
            .orElseThrow(
                () -> new SessionFileAccessException("unknown session owner: " + owner.kind()));
    if (!ownership.ownsSession(owner, sessionId)) {
      throw new SessionFileAccessException("session is not owned by the given owner");
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
      throw new SessionFileAccessException("file path is required");
    }
    String relative = relativePath(sessionId, path);
    if (relative.isEmpty()) {
      throw new SessionFileAccessException("file path must point to a file");
    }
    return relative;
  }

  private static String relativePath(UUID sessionId, String path) {
    VfsPath target;
    try {
      target = VfsPath.parse(path);
    } catch (IllegalArgumentException invalid) {
      throw new SessionFileAccessException(invalid.getMessage());
    }
    if (target.kind() != VfsPath.Kind.SESSION_DIR && target.kind() != VfsPath.Kind.SESSION_FILE) {
      throw new SessionFileAccessException("path must be inside the session directory: " + path);
    }
    if (!sessionId.equals(target.sessionId())) {
      throw new SessionFileAccessException("path does not belong to the requested session");
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
    entry.setUpdatedAt(file.updatedAt().toEpochMilli());
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

  /** 会话文件面板请求被确定性拒绝：越权、未知 owner 或路径不属于该会话。 */
  public static final class SessionFileAccessException extends RuntimeException {

    public SessionFileAccessException(String message) {
      super(message);
    }
  }

  /** 会话文件不存在。 */
  public static final class SessionFileNotFoundException extends RuntimeException {

    public SessionFileNotFoundException(String path) {
      super("No such file: " + path);
    }
  }

  /** 会话文件内容无法作为文本返回（例如二进制或超预算）。 */
  public static final class SessionFileContentException extends RuntimeException {

    public SessionFileContentException(String message) {
      super(message);
    }
  }
}
