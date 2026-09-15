package fun.fengwk.kkstudio.platform.harness.model;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobContent;

import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * Provider attempt 内联 Resource 的 Blob 内容读取：通过 {@link StorageBlobContentService} 有界读取原始字节并生成
 * attempt-only {@code data:<mime>;base64,...} 字符串。
 *
 * <p>职责只有三件事：
 *
 * <ol>
 *   <li>有界读取：单文件字节上限、data URI 字符上限与调用方给定的上限都在读取前校验，读取后再次校验权威 blobId/MIME/元数据大小与
 *       实际字节长度，任何不一致都显式失败（绝不回退成截断内容）；
 *   <li>并发闸门：同一时刻最多 {@link Limits#maxConcurrentDownloads()} 个下载，缓存命中不占用闸门，等待被中断时保留中断标志并失败，
 *       失败路径释放许可；
 *   <li>缓存：以不可变 Blob 事实（{@code blobId + mime + size}）为 key 缓存 data URI 字符串，让重复出现的同一 Resource 与并发
 *       miss 合并为一次读取；超过单条目记账上限的大对象不进入缓存。
 * </ol>
 *
 * <p>本类不判断 ACTIVE 状态、不判断模态与 Provider 能力，也不产生任何 URL：状态与能力判定在每次 attempt 的 {@link
 * ProviderResourceMaterializer} 中完成，本类只负责已确认可读的 Blob。缓存条目是源数据的纯函数，因此不需要失效通知，只受 TTL 与 权重上限约束。
 */
final class ProviderInlineBlobReader {

  private static final String DATA_URI_PREFIX = "data:";
  private static final String DATA_URI_SUFFIX = ";base64,";

  /** data URI header 内的 MIME：只接受规范小写 type/subtype，无参数、无控制字符，避免拼出非法或可注入的 URI。 */
  private static final Pattern SAFE_MEDIA_TYPE =
      Pattern.compile("[a-z0-9!#$&^_.+-]{1,127}/[a-z0-9!#$&^_.+-]{1,127}");

  /** 单条缓存记录的保守记账开销：record key、String 对象头、base64 编码中间量与哈希表槽位。 */
  private static final long ENTRY_WEIGHT_OVERHEAD_BYTES = 256L;

  private final StorageBlobContentService contentService;
  private final Limits limits;
  private final Cache<BlobKey, String> cache;
  private final Semaphore downloads;

  /** 测试注入入口：limits 与 ticker 决定上限、缓存预算与过期时间，便于确定性验证边界。 */
  ProviderInlineBlobReader(StorageBlobContentService contentService, Limits limits, Ticker ticker) {
    this.contentService = Objects.requireNonNull(contentService, "contentService");
    this.limits = Objects.requireNonNull(limits, "limits");
    Objects.requireNonNull(ticker, "ticker");
    this.cache =
        Caffeine.newBuilder()
            .maximumWeight(limits.maxCacheWeightBytes())
            .weigher(
                (BlobKey key, String value) ->
                    (int) Math.min(Integer.MAX_VALUE, accountedWeight(value.length())))
            .expireAfterWrite(limits.cacheTtl())
            .ticker(ticker)
            .build();
    this.downloads = new Semaphore(limits.maxConcurrentDownloads());
  }

  /**
   * 读取 blob 原始内容并生成内联 data URI。
   *
   * @param blobId 目标 blob（调用方已确认 ACTIVE）
   * @param mediaType 权威 MIME
   * @param sizeBytes 权威字节数
   * @param maxDataUriChars 本次允许生成的 data URI 字符上限，必须为正
   * @throws IllegalArgumentException 上限非正、MIME 或大小非法、声明大小超出上限时抛出
   * @throws IllegalStateException 权威内容与声明事实不一致、或读取被中断时抛出
   */
  String readDataUri(UUID blobId, String mediaType, long sizeBytes, long maxDataUriChars) {
    Objects.requireNonNull(blobId, "blobId");
    if (maxDataUriChars <= 0L) {
      throw new IllegalArgumentException("maxDataUriChars must be positive");
    }
    String safeMediaType = requireSafeMediaType(mediaType);
    if (sizeBytes < 0L || sizeBytes > limits.maxBlobBytes()) {
      throw new IllegalArgumentException(
          "blob size "
              + sizeBytes
              + " bytes is outside the allowed inline range 0.."
              + limits.maxBlobBytes()
              + " bytes");
    }
    long estimatedChars = estimatedDataUriChars(safeMediaType, sizeBytes);
    if (estimatedChars > maxDataUriChars) {
      throw new IllegalArgumentException(
          "inline data URI requires "
              + estimatedChars
              + " characters but only "
              + maxDataUriChars
              + " are allowed");
    }
    BlobKey key = new BlobKey(blobId, safeMediaType, sizeBytes);
    if (accountedWeight(estimatedChars) > limits.maxCacheEntryWeightBytes()) {
      // 大对象不进入缓存，避免单条目挤占缓存预算；仍走同一并发闸门与同一套校验。
      return load(key, maxDataUriChars);
    }
    // cache.get 让并发 miss 与重复引用合并为一次读取；loader 失败时 Caffeine 不写入任何条目。
    return cache.get(key, missedKey -> load(missedKey, maxDataUriChars));
  }

  /** 已缓存的 data URI 条目数（测试与诊断用；权重淘汰可能滞后于写入，使用前可先 {@link #cleanUp()}）。 */
  long cachedEntryCount() {
    return cache.estimatedSize();
  }

  /** 当前生效的读取上限；物化器在读取前据此给出显式的大小错误。 */
  Limits limits() {
    return limits;
  }

  /** 立即执行 Caffeine 的挂起维护（测试与诊断用），让权重淘汰结果可观测。 */
  void cleanUp() {
    cache.cleanUp();
  }

  private String load(BlobKey key, long maxDataUriChars) {
    acquireDownloadPermit();
    try {
      StorageBlobContent content =
          contentService.readBlobContent(key.blobId(), limits.maxBlobBytes());
      return toDataUri(key, content, maxDataUriChars);
    } finally {
      downloads.release();
    }
  }

  private void acquireDownloadPermit() {
    try {
      downloads.acquire();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for an inline blob read permit");
    }
  }

  /** 校验权威内容与声明事实一致后拼接 data URI；错误信息只包含 blobId 与大小，绝不回显内容或 URI。 */
  private static String toDataUri(BlobKey key, StorageBlobContent content, long maxDataUriChars) {
    if (!key.blobId().equals(content.getBlobId())) {
      throw new IllegalStateException("blob content identity mismatch for " + key.blobId());
    }
    if (content.getSizeBytes() != key.sizeBytes()) {
      throw new IllegalStateException(
          "blob metadata size mismatch for "
              + key.blobId()
              + ": expected "
              + key.sizeBytes()
              + " bytes but storage reports "
              + content.getSizeBytes());
    }
    if (!key.mediaType().equals(content.getMediaType())) {
      throw new IllegalStateException("blob media type mismatch for " + key.blobId());
    }
    byte[] bytes = content.getBytes();
    if (bytes.length != key.sizeBytes()) {
      throw new IllegalStateException(
          "blob content length mismatch for "
              + key.blobId()
              + ": expected "
              + key.sizeBytes()
              + " bytes but read "
              + bytes.length);
    }
    String dataUri =
        DATA_URI_PREFIX
            + key.mediaType()
            + DATA_URI_SUFFIX
            + Base64.getEncoder().encodeToString(bytes);
    if (dataUri.length() > maxDataUriChars) {
      throw new IllegalStateException(
          "blob content exceeds the inline size allowed for " + key.blobId());
    }
    return dataUri;
  }

  private static String requireSafeMediaType(String mediaType) {
    if (mediaType == null || !SAFE_MEDIA_TYPE.matcher(mediaType).matches()) {
      throw new IllegalArgumentException(
          "mediaType must be a canonical lowercase type/subtype without parameters");
    }
    return mediaType;
  }

  /** data URI 字符数：header 与 base64（每 3 字节 4 字符）之和，与 {@link Base64} 实际输出严格一致。 */
  static long estimatedDataUriChars(String mediaType, long sizeBytes) {
    if (sizeBytes < 0) {
      throw new IllegalArgumentException("inline blob size must not be negative");
    }
    long payloadChars = Math.multiplyExact(4L, Math.addExact(sizeBytes, 2L) / 3L);
    return Math.addExact(
        DATA_URI_PREFIX.length() + mediaType.length() + DATA_URI_SUFFIX.length(), payloadChars);
  }

  private static long accountedWeight(long valueChars) {
    return 2L * valueChars + ENTRY_WEIGHT_OVERHEAD_BYTES;
  }

  /** 不可变 Blob 事实：UUID + MIME + 大小，任一变化都对应新的 key。 */
  private record BlobKey(UUID blobId, String mediaType, long sizeBytes) {}

  /**
   * 可注入上限：默认值只是应用安全闸门，不代表任何 Provider 能力或供应商限制。
   *
   * @param maxBlobBytes 单文件原始字节上限，同时作为 {@link StorageBlobContentService} 的读取上限
   * @param maxCacheEntryWeightBytes 单条目记账上限，超过则不缓存
   * @param maxCacheWeightBytes 缓存总记账上限
   * @param cacheTtl 条目写入后的存活时间
   * @param maxConcurrentDownloads 同时在途的下载数（含不缓存的读取）
   */
  record Limits(
      long maxBlobBytes,
      long maxCacheEntryWeightBytes,
      long maxCacheWeightBytes,
      Duration cacheTtl,
      int maxConcurrentDownloads) {

    /** 单文件 100 MiB、单条目 8 MiB、总缓存 64 MiB、TTL 5 分钟、并发下载 2。 */
    static final Limits DEFAULT =
        new Limits(
            100L * 1024L * 1024L,
            8L * 1024L * 1024L,
            64L * 1024L * 1024L,
            Duration.ofMinutes(5),
            2);

    Limits {
      if (maxBlobBytes <= 0L) {
        throw new IllegalArgumentException("maxBlobBytes must be positive");
      }
      if (maxCacheEntryWeightBytes <= 0L) {
        throw new IllegalArgumentException("maxCacheEntryWeightBytes must be positive");
      }
      if (maxCacheWeightBytes <= 0L) {
        throw new IllegalArgumentException("maxCacheWeightBytes must be positive");
      }
      if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
        throw new IllegalArgumentException("cacheTtl must be positive");
      }
      if (maxConcurrentDownloads <= 0) {
        throw new IllegalArgumentException("maxConcurrentDownloads must be positive");
      }
    }
  }
}
