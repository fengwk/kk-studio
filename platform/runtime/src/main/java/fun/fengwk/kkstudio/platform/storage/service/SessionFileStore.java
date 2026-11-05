package fun.fengwk.kkstudio.platform.storage.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.storage.error.SessionFileConflictException;
import fun.fengwk.kkstudio.platform.storage.model.SessionFile;
import fun.fengwk.kkstudio.platform.storage.persistence.SessionFileRepository;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Session 虚拟文件系统（VFS）的底座平台存储服务。
 *
 * <p>提供基于 revision 的乐观并发写入、Session 级文件配额限制，以及 Blob 生命周期维护：
 *
 * <ul>
 *   <li>S3 对象 I/O 完全发生在数据库事务外部（先经 {@link StorageUploadService#stage} 暂存写入 S3）；
 *   <li>随后在短数据库事务内执行 {@code lockReady -> retainRef -> CAS/insert -> uploadService.delete} 原子转移；
 *   <li>更新旧文件时，仅当该 Session 内无其它 {@code session_file} 引用原 Blob 且原 Blob 与新 Blob 不同时，才 release 原 Blob
 *       引用。
 * </ul>
 */
@Slf4j
public class SessionFileStore {

  public static final int MAX_FILES_PER_SESSION = SessionFileLimits.DEFAULT.maxFilesPerSession();
  public static final long MAX_SINGLE_FILE_BYTES = SessionFileLimits.DEFAULT.maxSingleFileBytes();
  public static final long MAX_TOTAL_BYTES_PER_SESSION =
      SessionFileLimits.DEFAULT.maxTotalBytesPerSession();

  private static final Pattern RELATIVE_SEGMENT_PATTERN = Pattern.compile("(^|/)\\.\\.?(?:/|$)");

  private final SessionFileRepository sessionFileRepository;
  private final StorageBlobRepository blobRepository;
  private final StorageUploadService uploadService;
  private final StorageBlobContentService blobContentService;
  private final SessionBlobRefManager refManager;
  private final Clock clock;
  private final SessionFileLimits limits;
  private final TransactionTemplate transactionTemplate;

  public SessionFileStore(
      SessionFileRepository sessionFileRepository,
      StorageBlobRepository blobRepository,
      StorageUploadService uploadService,
      StorageBlobContentService blobContentService,
      SessionBlobRefManager refManager,
      Clock clock,
      PlatformTransactionManager transactionManager,
      SessionFileLimits limits) {
    this.sessionFileRepository =
        Objects.requireNonNull(sessionFileRepository, "sessionFileRepository");
    this.blobRepository = Objects.requireNonNull(blobRepository, "blobRepository");
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    this.blobContentService = Objects.requireNonNull(blobContentService, "blobContentService");
    this.refManager = Objects.requireNonNull(refManager, "refManager");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.limits = Objects.requireNonNull(limits, "limits");
    this.transactionTemplate =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
  }

  /** 查询 Session 内指定路径的文件元数据。 */
  public Optional<SessionFile> find(UUID sessionId, String path) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    validatePath(path);
    return sessionFileRepository.find(sessionId, path);
  }

  /** 列出 Session 内匹配指定路径前缀的文件元数据列表。若前缀为空则列出全部文件。 */
  public List<SessionFile> list(UUID sessionId, String pathPrefix) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    if (pathPrefix == null || pathPrefix.isEmpty()) {
      return sessionFileRepository.listBySession(sessionId);
    }
    return sessionFileRepository.listByPrefix(sessionId, pathPrefix);
  }

  /**
   * 写入 Session 文件内容。
   *
   * @param sessionId 目标 Session UUID
   * @param path Session 内相对路径
   * @param content 文件完整字节内容
   * @param expectedRevision 期望的当前版本号。{@code null} 表示创建新文件（必须不存在），非空表示覆盖已有文件
   * @return 写入或更新后的最新 {@link SessionFile}
   * @throws IllegalArgumentException 路径不合法或单文件超出大小限制
   * @throws IllegalStateException 超出 Session 文件总数限制或总大小限制
   * @throws SessionFileConflictException 乐观锁版本不匹配或并发创建重名文件
   */
  public SessionFile write(UUID sessionId, String path, byte[] content, Integer expectedRevision) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(content, "content must not be null");
    validatePath(path);

    if (content.length > limits.maxSingleFileBytes()) {
      throw new IllegalArgumentException(
          "session file too large: "
              + content.length
              + " bytes (limit "
              + limits.maxSingleFileBytes()
              + ")");
    }

    if (expectedRevision != null && expectedRevision <= 0) {
      throw new IllegalArgumentException("expectedRevision must be positive: " + expectedRevision);
    }

    // 1. 事务外快速预检（非权威，仅避免不必要的 S3 I/O）；权威判断在事务内持锁完成
    if (expectedRevision == null) {
      if (sessionFileRepository.countBySession(sessionId) >= limits.maxFilesPerSession()) {
        throw new IllegalStateException(
            "session file limit reached: " + limits.maxFilesPerSession() + " files");
      }
    }

    // 2. 事务外经统一 StorageUploadService 暂存并落盘 S3 对象
    StorageUploadService.StagedUpload staged =
        uploadService.stage(
            path,
            "application/octet-stream",
            new ByteArrayInputStream(content),
            limits.maxSingleFileBytes());

    // 3. 短数据库事务内执行权威状态校验、锁行、引用转移与乐观锁写库
    try {
      return transactionTemplate.execute(
          status -> {
            // 事务内第一步：按 Session 取 advisory lock，使「读配额 -> 写 session_file」对该 Session 串行化
            sessionFileRepository.lockSessionQuota(sessionId);
            Optional<SessionFile> existingOpt = sessionFileRepository.find(sessionId, path);

            if (expectedRevision == null) {
              if (existingOpt.isPresent()) {
                throw new SessionFileConflictException("session file already exists: " + path);
              }
              long fileCount = sessionFileRepository.countBySession(sessionId);
              if (fileCount >= limits.maxFilesPerSession()) {
                throw new IllegalStateException(
                    "session file limit reached: " + limits.maxFilesPerSession() + " files");
              }
              long currentTotal = sessionFileRepository.sumSizeBySession(sessionId);
              if (currentTotal + content.length > limits.maxTotalBytesPerSession()) {
                throw new IllegalStateException("session total size limit reached");
              }
            } else {
              if (existingOpt.isEmpty()) {
                throw new SessionFileConflictException("session file not found: " + path);
              }
              SessionFile existing = existingOpt.get();
              if (existing.revision() != expectedRevision) {
                throw new SessionFileConflictException(
                    "session file revision mismatch for "
                        + path
                        + ": expected "
                        + expectedRevision
                        + ", actual "
                        + existing.revision());
              }
              StorageBlob oldBlob = blobRepository.getById(existing.blobId());
              long oldSize = oldBlob != null ? oldBlob.getSizeBytes() : 0L;
              long currentTotal = sessionFileRepository.sumSizeBySession(sessionId);
              long newTotal = currentTotal - oldSize + content.length;
              if (newTotal > limits.maxTotalBytesPerSession()) {
                throw new IllegalStateException("session total size limit reached");
              }
            }

            // 锁定 READY 上传并原子转移为 Session blob 引用，释放暂存 upload 句柄
            uploadService.lockReady(staged.uploadId());
            UUID newBlobId = staged.blobId();
            refManager.retainRef(sessionId, newBlobId);
            uploadService.delete(staged.uploadId());

            if (expectedRevision == null) {
              SessionFile newFile = new SessionFile(sessionId, path, newBlobId, 1, clock.instant());
              try {
                if (!sessionFileRepository.insert(newFile)) {
                  throw new SessionFileConflictException("insert session file failed: " + path);
                }
              } catch (DuplicateKeyException e) {
                throw new SessionFileConflictException(
                    "concurrent creation conflict for session file: " + path, e);
              }
            } else {
              SessionFile existing = existingOpt.get();
              int updated =
                  sessionFileRepository.updateBlobCas(sessionId, path, newBlobId, expectedRevision);
              if (updated != 1) {
                throw new SessionFileConflictException(
                    "session file revision conflict for "
                        + path
                        + ": expected "
                        + expectedRevision);
              }

              // 当原 blob 与新 blob 不同且 Session 内无其它文件再引用原 blob 时，释放 Session 级引用
              UUID oldBlobId = existing.blobId();
              if (!oldBlobId.equals(newBlobId)) {
                if (!sessionFileRepository.existsBlobReference(sessionId, oldBlobId)) {
                  refManager.releaseRef(sessionId, oldBlobId);
                }
              }
            }

            return sessionFileRepository
                .find(sessionId, path)
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "session file disappeared immediately after write: " + path));
          });
    } catch (RuntimeException failure) {
      discardQuietly(staged.uploadId());
      throw failure;
    }
  }

  /** 获取指定 Session 内文件占用的总内容字节数。 */
  public long totalBytes(UUID sessionId) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    return sessionFileRepository.sumSizeBySession(sessionId);
  }

  /** 读取文件原始内容字节（若文件不存在返回空 Optional）。 */
  public Optional<byte[]> read(UUID sessionId, String path) {
    return find(sessionId, path)
        .map(file -> blobContentService.readBlobContent(file.blobId(), -1).getBytes());
  }

  /**
   * 删除指定文件。
   *
   * @param sessionId Session UUID
   * @param path 文件相对路径
   * @param expectedRevision 期望的版本号（传 {@code null} 表示无条件删除）
   * @return 是否成功删除
   */
  public boolean delete(UUID sessionId, String path, Integer expectedRevision) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    validatePath(path);

    return Boolean.TRUE.equals(
        transactionTemplate.execute(
            status -> {
              Optional<SessionFile> existingOpt = sessionFileRepository.find(sessionId, path);
              if (existingOpt.isEmpty()) {
                if (expectedRevision != null) {
                  throw new SessionFileConflictException(
                      "session file not found for deletion: " + path);
                }
                return false;
              }
              SessionFile existing = existingOpt.get();
              if (expectedRevision != null && existing.revision() != expectedRevision) {
                throw new SessionFileConflictException(
                    "session file revision conflict for deletion of "
                        + path
                        + ": expected "
                        + expectedRevision
                        + ", actual "
                        + existing.revision());
              }
              boolean deleted = sessionFileRepository.delete(sessionId, path);
              if (deleted) {
                UUID oldBlobId = existing.blobId();
                if (!sessionFileRepository.existsBlobReference(sessionId, oldBlobId)) {
                  refManager.releaseRef(sessionId, oldBlobId);
                }
              }
              return deleted;
            }));
  }

  /** 删除 Session 内全部文件记录（仅删除 session_file 行，引用的 Blob 由 Session 整体深删除通过 session_blob_ref 统一释放）。 */
  public List<UUID> deleteBySession(UUID sessionId) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    return sessionFileRepository.deleteBySession(sessionId);
  }

  private void discardQuietly(UUID uploadId) {
    try {
      uploadService.delete(uploadId);
    } catch (RuntimeException error) {
      log.warn("failed to discard staged upload {} after write failure", uploadId, error);
    }
  }

  private static void validatePath(String path) {
    if (path == null || path.isEmpty()) {
      throw new IllegalArgumentException("session file path must not be blank");
    }
    if (!path.equals(path.strip())) {
      throw new IllegalArgumentException(
          "session file path must not have leading or trailing whitespace: " + path);
    }
    if (path.startsWith("/")) {
      throw new IllegalArgumentException("session file path must not start with slash: " + path);
    }
    if (RELATIVE_SEGMENT_PATTERN.matcher(path).find()) {
      throw new IllegalArgumentException(
          "session file path must not contain relative dot segments: " + path);
    }
    if (containsControlCharacter(path)) {
      throw new IllegalArgumentException(
          "session file path must not contain control characters: " + path);
    }
  }

  /** C0、DEL 与 C1 控制字符不可见，写入前拒绝；数据库 CHECK 同步约束同一规则。 */
  private static boolean containsControlCharacter(String value) {
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < 0x20 || character == 0x7F || (character >= 0x80 && character <= 0x9F)) {
        return true;
      }
    }
    return false;
  }
}
