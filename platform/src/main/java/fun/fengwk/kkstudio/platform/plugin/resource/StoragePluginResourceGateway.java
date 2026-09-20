package fun.fengwk.kkstudio.platform.plugin.resource;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.plugin.PluginProperties;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadReserveRequestDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadState;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Platform 的 {@link PluginResourceGateway} 生产实现：Session Resource 授权下载 + 远端媒体真实暂存。
 *
 * <p>两条路径都不信任调用方给出的字符串或响应头：
 *
 * <ul>
 *   <li><b>输入</b>：URI 必须是规范的 {@code kkstudio:/resources/<blobId>}；<b>调用方显式传入 threadId</b>，实现从
 *       durable Thread 行解析 Session，只有该 Session 的 {@code session_blob_ref} 确实引用该 blob 时才签发短期预签名
 *       GET。没有 threadId 或 thread 无法解析时确定性失败，绝不退化成「未鉴权下载」。
 *   <li><b>输出</b>：远端地址必须通过地址准入（HTTPS、无 userinfo/fragment、解析出的每个地址都是公网地址），下载禁止自动重定向、受整体期限与字节上限约束，
 *       内容边流式写临时文件边算 SHA-256（不把媒体读进内存），媒体类型由 magic 特征判定并复核调用方声明的族；暂存走 reserve → 预签名 PUT（原样回传 signed
 *       headers）→ complete，只有真正 READY 才返回引用。
 * </ul>
 *
 * <p>失败语义：任何一步失败都收敛为 {@link PluginResourceUnavailableException}，并且在已 reserve 的情况下 best-effort
 * 删除上传，避免留下孤儿对象； 临时文件在 finally 中删除。日志只记录 id、大小与类型，绝不记录预签名 URL 或响应头。
 */
@Slf4j
public final class StoragePluginResourceGateway implements PluginResourceGateway {

  /** magic 嗅探需要的头部字节数。 */
  private static final int SNIFF_BYTES = 64;

  /** 流式复制缓冲区；媒体大小不受堆内存限制。 */
  private static final int COPY_BUFFER_BYTES = 64 * 1024;

  /** 文件名与扩展名之间的固定分隔符。 */
  private static final char EXTENSION_SEPARATOR = '.';

  private static final String SHA256 = "SHA-256";

  private final HarnessStore harnessStore;
  private final SessionBlobRefManager sessionBlobRefManager;
  private final StorageBlobManager storageBlobManager;
  private final StorageUploadService storageUploadService;
  private final PluginProperties properties;
  private final PublicAddressPolicy addressPolicy;
  private final RemoteMediaTransport transport;
  private final Path tempDirectory;

  public StoragePluginResourceGateway(
      HarnessStore harnessStore,
      SessionBlobRefManager sessionBlobRefManager,
      StorageBlobManager storageBlobManager,
      StorageUploadService storageUploadService,
      PluginProperties properties) {
    this(
        harnessStore,
        sessionBlobRefManager,
        storageBlobManager,
        storageUploadService,
        properties,
        new PublicAddressPolicy(HostResolver.system()),
        new JdkRemoteMediaTransport(properties.getResource().getConnectTimeout()));
  }

  /** 测试用装配：地址解析与传输都可替换，使安全规则无需真实网络即可验证。 */
  StoragePluginResourceGateway(
      HarnessStore harnessStore,
      SessionBlobRefManager sessionBlobRefManager,
      StorageBlobManager storageBlobManager,
      StorageUploadService storageUploadService,
      PluginProperties properties,
      PublicAddressPolicy addressPolicy,
      RemoteMediaTransport transport) {
    this.harnessStore = Objects.requireNonNull(harnessStore, "harnessStore");
    this.sessionBlobRefManager =
        Objects.requireNonNull(sessionBlobRefManager, "sessionBlobRefManager");
    this.storageBlobManager = Objects.requireNonNull(storageBlobManager, "storageBlobManager");
    this.storageUploadService =
        Objects.requireNonNull(storageUploadService, "storageUploadService");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.addressPolicy = Objects.requireNonNull(addressPolicy, "addressPolicy");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.tempDirectory = resolveTempDirectory(properties.getResource().getTempDirectory());
  }

  @Override
  public URI resolveSessionResource(UUID threadId, String resourceUri) {
    UUID blobId =
        SessionResourceUri.parse(resourceUri)
            .orElseThrow(
                () ->
                    new PluginResourceUnavailableException(
                        "session resource uri must be kkstudio:/resources/<blobId>"));
    if (threadId == null) {
      throw new PluginResourceUnavailableException(
          "session resource access requires an invocation thread id");
    }
    UUID sessionId = resolveSessionId(threadId);
    boolean authorized;
    try {
      authorized = sessionBlobRefManager.contains(sessionId, blobId);
    } catch (RuntimeException error) {
      throw new PluginResourceUnavailableException(
          "cannot verify session resource ownership", error);
    }
    if (!authorized) {
      throw new PluginResourceUnavailableException(
          "the invocation session does not reference this resource");
    }
    StoragePresignedUrlDTO presigned;
    try {
      presigned = storageBlobManager.presignOriginalUrl(blobId);
    } catch (StorageResourceNotFoundException error) {
      throw new PluginResourceUnavailableException(
          "session resource is no longer available", error);
    } catch (RuntimeException error) {
      throw new PluginResourceUnavailableException("cannot sign this session resource", error);
    }
    return toHttpsUri(presigned == null ? null : presigned.getUrl());
  }

  @Override
  public ResourceRef stageRemoteMedia(URI remoteUri, PluginMediaFamily family, String name) {
    Objects.requireNonNull(family, "family");
    URI uri = requireDownloadable(remoteUri);
    addressPolicy.assertPublicHost(uri.getHost());
    StagedMedia media = download(uri, family);
    try {
      return upload(media, family, sanitizeName(name));
    } finally {
      deleteQuietly(media.path());
    }
  }

  /** 校验远端地址并流式落盘：返回权威 size、SHA-256、媒体类型与临时文件。 */
  private StagedMedia download(URI uri, PluginMediaFamily family) {
    Duration timeout = properties.getResource().getRequestTimeout();
    long maxBytes = properties.getResource().getMaxBytes();
    long deadlineNanos = System.nanoTime() + timeout.toNanos();
    RemoteMediaTransport.MediaResponse response = transport.get(uri, timeout);
    if (response.status() != 200) {
      closeQuietly(response.body());
      throw new PluginResourceUnavailableException(
          "remote media responded with status " + response.status());
    }
    long declaredLength = contentLength(response);
    if (declaredLength > maxBytes) {
      closeQuietly(response.body());
      throw new PluginResourceUnavailableException(
          "remote media declares more bytes than this deployment can stage");
    }
    Path temp = createTempFile();
    StreamingCopy copy = new StreamingCopy(response.body(), temp, maxBytes, deadlineNanos);
    copy.awaitCompletion();
    PluginResourceUnavailableException failure = copy.failure();
    if (failure != null) {
      deleteQuietly(temp);
      throw failure;
    }
    if (declaredLength >= 0L && declaredLength != copy.total()) {
      deleteQuietly(temp);
      throw new PluginResourceUnavailableException(
          "remote media content-length does not match the received body");
    }
    String mediaType =
        MediaTypeSniffer.sniff(copy.head())
            .or(() -> MediaTypeSniffer.normalizeHeader(firstHeader(response, "content-type")))
            .orElse(null);
    if (mediaType == null) {
      deleteQuietly(temp);
      throw new PluginResourceUnavailableException("cannot determine remote media type");
    }
    if (!family.accepts(mediaType)) {
      deleteQuietly(temp);
      throw new PluginResourceUnavailableException(
          "remote media type is not the declared " + family.name().toLowerCase(Locale.ROOT));
    }
    return new StagedMedia(temp, copy.total(), hex(copy.digest()), mediaType);
  }

  /** reserve → （PENDING 时预签名 PUT）→ complete，任何失败都清理上传。 */
  private ResourceRef upload(StagedMedia media, PluginMediaFamily family, String baseName) {
    String filename = filename(baseName, media.mediaType());
    StorageUploadDTO reserved;
    try {
      reserved = storageUploadService.reserve(reserveRequest(filename, media));
    } catch (RuntimeException error) {
      throw new PluginResourceUnavailableException("cannot reserve a staging upload", error);
    }
    UUID uploadId = parseUploadId(reserved);
    try {
      StorageUploadDTO upload = reserved;
      if (reserved.getState() == StorageUploadState.PENDING) {
        putObject(reserved.getPresignedPut(), media.path());
        upload = storageUploadService.complete(uploadId);
      }
      if (upload == null
          || upload.getState() != StorageUploadState.READY
          || upload.getBlobId() == null) {
        throw new PluginResourceUnavailableException("staged upload did not become READY");
      }
      log.debug(
          "Staged plugin media upload {} ({} bytes, {}, family {})",
          uploadId,
          media.sizeBytes(),
          media.mediaType(),
          family);
      return new ResourceRef(
          ResourceRef.blobUploadUri(uploadId),
          media.mediaType(),
          filename,
          media.sizeBytes(),
          media.sha256());
    } catch (RuntimeException error) {
      deleteQuietly(uploadId);
      throw error instanceof PluginResourceUnavailableException unavailable
          ? unavailable
          : new PluginResourceUnavailableException("cannot stage remote media", error);
    }
  }

  private void putObject(StoragePresignedUrlDTO presignedPut, Path file) {
    if (presignedPut == null || presignedPut.getUrl() == null || presignedPut.getUrl().isBlank()) {
      throw new PluginResourceUnavailableException("staged upload has no presigned target");
    }
    if (!"PUT".equalsIgnoreCase(presignedPut.getMethod())) {
      throw new PluginResourceUnavailableException("staged upload requires an unexpected method");
    }
    URI target = parseAbsoluteUri(presignedPut.getUrl());
    if (target.getUserInfo() != null) {
      throw new PluginResourceUnavailableException("staged upload target must not carry userinfo");
    }
    Map<String, String> headers = new LinkedHashMap<>();
    if (presignedPut.getHeaders() != null) {
      presignedPut
          .getHeaders()
          .forEach(
              (name, value) -> {
                if (name != null
                    && value != null
                    && !RemoteMediaTransport.isRestrictedHeader(name)) {
                  headers.put(name, value);
                }
              });
    }
    int status = transport.put(target, headers, file, properties.getResource().getUploadTimeout());
    if (status / 100 != 2) {
      throw new PluginResourceUnavailableException(
          "presigned media upload responded with status " + status);
    }
  }

  private StorageUploadReserveRequestDTO reserveRequest(String filename, StagedMedia media) {
    StorageUploadReserveRequestDTO request = new StorageUploadReserveRequestDTO();
    request.setFilename(filename);
    request.setMediaType(media.mediaType());
    request.setSizeBytes(media.sizeBytes());
    request.setSha256(media.sha256());
    return request;
  }

  private UUID parseUploadId(StorageUploadDTO upload) {
    if (upload == null || upload.getId() == null) {
      throw new PluginResourceUnavailableException("staging upload has no id");
    }
    try {
      return UUID.fromString(upload.getId());
    } catch (IllegalArgumentException error) {
      throw new PluginResourceUnavailableException(
          "staging upload id is not a canonical uuid", error);
    }
  }

  private UUID resolveSessionId(UUID threadId) {
    Optional<ThreadState> thread;
    try {
      thread = harnessStore.transaction(tx -> tx.findThread(threadId));
    } catch (RuntimeException error) {
      throw new PluginResourceUnavailableException("cannot read the invocation thread", error);
    }
    return thread
        .map(ThreadState::sessionId)
        .orElseThrow(
            () ->
                new PluginResourceUnavailableException(
                    "the invocation thread is no longer available"));
  }

  /** 下载地址必须是没有敏感成分的绝对 HTTPS URI。 */
  private static URI requireDownloadable(URI remoteUri) {
    if (remoteUri == null || !remoteUri.isAbsolute()) {
      throw new PluginResourceUnavailableException("remote media uri must be absolute");
    }
    if (!"https".equalsIgnoreCase(remoteUri.getScheme())) {
      throw new PluginResourceUnavailableException("remote media uri must use https");
    }
    if (remoteUri.getHost() == null
        || remoteUri.getFragment() != null
        || remoteUri.getUserInfo() != null) {
      throw new PluginResourceUnavailableException(
          "remote media uri must not carry userinfo, fragment or an empty host");
    }
    return remoteUri;
  }

  private static URI toHttpsUri(String value) {
    URI uri = parseAbsoluteUri(value);
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null) {
      throw new PluginResourceUnavailableException("signed session resource url is not usable");
    }
    return uri;
  }

  private static URI parseAbsoluteUri(String value) {
    if (value == null || value.isBlank()) {
      throw new PluginResourceUnavailableException("required url is missing");
    }
    try {
      URI uri = new URI(value);
      if (!uri.isAbsolute()) {
        throw new PluginResourceUnavailableException("required url must be absolute");
      }
      return uri;
    } catch (URISyntaxException error) {
      throw new PluginResourceUnavailableException("required url is malformed", error);
    }
  }

  private static long contentLength(RemoteMediaTransport.MediaResponse response) {
    String value = firstHeader(response, "content-length");
    if (value == null) {
      return -1L;
    }
    try {
      long parsed = Long.parseLong(value.strip());
      return parsed < 0L ? -1L : parsed;
    } catch (NumberFormatException error) {
      throw new PluginResourceUnavailableException("remote media content-length is not a number");
    }
  }

  private static String firstHeader(RemoteMediaTransport.MediaResponse response, String name) {
    Map<String, List<String>> headers = response.headers();
    if (headers == null) {
      return null;
    }
    for (Map.Entry<String, List<String>> header : headers.entrySet()) {
      if (header.getKey() != null
          && header.getKey().equalsIgnoreCase(name)
          && header.getValue() != null
          && !header.getValue().isEmpty()) {
        return header.getValue().get(0);
      }
    }
    return null;
  }

  private static int copyHead(byte[] buffer, byte[] head, int headLength, int read) {
    if (headLength >= head.length) {
      return headLength;
    }
    int copy = Math.min(read, head.length - headLength);
    System.arraycopy(buffer, 0, head, headLength, copy);
    return headLength + copy;
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance(SHA256);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 must be available", error);
    }
  }

  private static String hex(byte[] digest) {
    StringBuilder builder = new StringBuilder(digest.length * 2);
    for (byte value : digest) {
      builder.append(Character.forDigit((value >> 4) & 0xF, 16));
      builder.append(Character.forDigit(value & 0xF, 16));
    }
    return builder.toString();
  }

  private Path createTempFile() {
    try {
      Files.createDirectories(tempDirectory);
      return Files.createTempFile(tempDirectory, "plugin-media-", ".part");
    } catch (IOException error) {
      throw new PluginResourceUnavailableException("cannot create a staging temp file", error);
    }
  }

  private static Path resolveTempDirectory(String configured) {
    Path directory =
        configured == null || configured.isBlank()
            ? Path.of(System.getProperty("java.io.tmpdir"))
            : Path.of(configured);
    return directory.toAbsolutePath().normalize();
  }

  /** 规范文件名：去掉路径成分与控制字符、限制长度，并按嗅探结果补出扩展名。 */
  private static String sanitizeName(String name) {
    String candidate = name == null ? "" : name.strip();
    int separator = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\'));
    if (separator >= 0) {
      candidate = candidate.substring(separator + 1);
    }
    StringBuilder builder = new StringBuilder(candidate.length());
    for (int index = 0; index < candidate.length() && builder.length() < 120; index++) {
      char value = candidate.charAt(index);
      builder.append(Character.isISOControl(value) ? '_' : value);
    }
    String sanitized = builder.toString().strip();
    return sanitized.isEmpty() ? "media" : sanitized;
  }

  private static String filename(String baseName, String mediaType) {
    if (baseName.indexOf(EXTENSION_SEPARATOR) >= 0) {
      return baseName;
    }
    String extension = extensionOf(mediaType);
    return extension.isEmpty() ? baseName : baseName + EXTENSION_SEPARATOR + extension;
  }

  private static String extensionOf(String mediaType) {
    int separator = mediaType.indexOf('/');
    String subtype = separator < 0 ? mediaType : mediaType.substring(separator + 1);
    String normalized = subtype.replaceAll("[^a-z0-9]", "");
    return switch (normalized) {
      case "jpeg" -> "jpg";
      case "mp4" -> "mp4";
      case "quicktime" -> "mov";
      case "xmsvideo" -> "avi";
      case "xmatroska" -> "mkv";
      case "plain" -> "txt";
      case "octetstream" -> "bin";
      case "zip" -> "zip";
      default -> normalized.length() > 8 ? "" : normalized;
    };
  }

  private static void closeQuietly(InputStream body) {
    if (body == null) {
      return;
    }
    try {
      body.close();
    } catch (IOException ignored) {
      // 关闭失败不影响确定性失败结果。
    }
  }

  private static void deleteQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException error) {
      log.warn("Cannot delete plugin media temp file {}", path.getFileName());
    }
  }

  private void deleteQuietly(UUID uploadId) {
    try {
      storageUploadService.delete(uploadId);
    } catch (RuntimeException error) {
      log.warn("Cannot clean up plugin staging upload {}", uploadId);
    }
  }

  /** 一次下载的权威结果。 */
  private record StagedMedia(Path path, long sizeBytes, String sha256, String mediaType) {}

  /**
   * 一次下载的流式复制任务：在独立线程内边读边写临时文件并计算摘要，同时由调用线程施加**整体**期限。
   *
   * <p>只靠 HTTP 请求期限不足以约束响应体：服务端可以在读完响应头后一直不发送正文，让阻塞的 {@code read} 永久挂住调用线程。因此这里把复制放到 daemon
   * 线程，超时后关闭响应体让读线程尽快退出，并在调用线程上立刻给出确定性失败。字节预算仍在读循环内逐块判定。
   */
  private static final class StreamingCopy implements Runnable {

    private final InputStream source;
    private final Path target;
    private final long maxBytes;
    private final long deadlineNanos;
    private final MessageDigest digest = sha256Digest();
    private final byte[] head = new byte[SNIFF_BYTES];

    private PluginResourceUnavailableException failure;
    private long total;
    private int headLength;

    StreamingCopy(InputStream source, Path target, long maxBytes, long deadlineNanos) {
      this.source = source;
      this.target = target;
      this.maxBytes = maxBytes;
      this.deadlineNanos = deadlineNanos;
    }

    /** 在调用线程上等待复制完成；超时或中断时收敛为确定性失败并关闭响应体。 */
    void awaitCompletion() {
      Thread worker = new Thread(this, "plugin-media-staging");
      worker.setDaemon(true);
      worker.start();
      long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
      if (remainingMillis <= 0L) {
        fail(
            new PluginResourceUnavailableException(
                "remote media download exceeded the response deadline"));
        closeQuietly(source);
        return;
      }
      try {
        worker.join(remainingMillis);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        closeQuietly(source);
        fail(
            new PluginResourceUnavailableException("remote media download was interrupted", error));
        return;
      }
      if (worker.isAlive()) {
        fail(
            new PluginResourceUnavailableException(
                "remote media download exceeded the response deadline"));
        closeQuietly(source);
      }
    }

    @Override
    public void run() {
      try (InputStream body = source;
          OutputStream out = Files.newOutputStream(target)) {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        int read = body.read(buffer);
        while (read != -1) {
          total += read;
          if (total > maxBytes) {
            fail(
                new PluginResourceUnavailableException(
                    "remote media exceeds the configured staging byte budget"));
            return;
          }
          digest.update(buffer, 0, read);
          out.write(buffer, 0, read);
          headLength = copyHead(buffer, head, headLength, read);
          read = body.read(buffer);
        }
      } catch (IOException error) {
        fail(new PluginResourceUnavailableException("cannot stage remote media", error));
      }
    }

    private synchronized void fail(PluginResourceUnavailableException error) {
      if (failure == null) {
        failure = error;
      }
    }

    synchronized PluginResourceUnavailableException failure() {
      return failure;
    }

    long total() {
      return total;
    }

    byte[] head() {
      return head;
    }

    byte[] digest() {
      return digest.digest();
    }
  }
}
