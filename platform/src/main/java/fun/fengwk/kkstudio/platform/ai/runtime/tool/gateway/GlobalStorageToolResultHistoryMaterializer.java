package fun.fengwk.kkstudio.platform.ai.runtime.tool.gateway;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.ResourceRef;
import fun.fengwk.kkstudio.harness.tool.ResourceToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.configuration.S3StorageProperties;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobIngestService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 基于全局 Blob 存储的 {@link ToolResultHistoryMaterializer}：在 Tool outcome Entry 插入前、同一 store 事务内，把 瞬时
 * Resource 引用内容（data/file/http/https/s3）外部化为全局 Storage blob，并以 {@code
 * ResourceMessageContent(blobId, name, preview)} 返回 durable 内容；Text/Json 原样映射。
 *
 * <p>解析与摄入按内容顺序逐个完成（失败即异常 → 调用方事务整体回滚，绝不产生部分 history）。单资源字节预算与数据库
 * SystemSettings.Advanced.resourceMaxBytes 默认一致（16 MiB）。s3 URI 的 bucket 必须与全局存储 配置 bucket
 * 精确一致，否则确定性拒绝（服务端 S3 客户端是固定 bucket 契约）。本类由 S3 装配（{@code S3StorageConfiguration}）以 bean 形式提供；S3
 * 未启用时不存在该 bean，Runtime 对含 Resource 引用的 ToolResult 保持 fail-closed。
 */
public class GlobalStorageToolResultHistoryMaterializer implements ToolResultHistoryMaterializer {

  /** 单资源解析字节预算：与 SystemSettings.Advanced.resourceMaxBytes 默认值一致（独立于装配值的安全上界）。 */
  static final int MAX_RESOLVED_BYTES = 16 * 1024 * 1024;

  private final StorageBlobIngestService ingestService;
  private final S3StorageService s3StorageService;
  private final String storageBucket;
  private final HttpClient httpClient;

  public GlobalStorageToolResultHistoryMaterializer(
      StorageBlobIngestService ingestService,
      S3StorageService s3StorageService,
      S3StorageProperties s3StorageProperties) {
    this.ingestService = Objects.requireNonNull(ingestService, "ingestService");
    this.s3StorageService = Objects.requireNonNull(s3StorageService, "s3StorageService");
    this.storageBucket =
        Objects.requireNonNull(s3StorageProperties, "s3StorageProperties").getBucket();
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public List<AgentMessageContent> materialize(UUID sessionId, ToolResult result) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(result, "result");
    List<ToolContent> source = result.contents();
    List<AgentMessageContent> contents = new ArrayList<>(source.size());
    for (int index = 0; index < source.size(); index++) {
      ToolContent content = source.get(index);
      if (content instanceof TextToolContent text) {
        contents.add(new TextMessageContent(text.text()));
      } else if (content instanceof JsonToolContent json) {
        contents.add(new JsonMessageContent(json.json()));
      } else if (content instanceof BinaryToolContent binary) {
        // 外部化器在 Gateway 侧已把 transient Binary 转为 ResourceToolContent；此处防御性支持并保持确定性。
        UUID blobId = ingestService.ingest(sessionId, binary.content(), binary.mediaType());
        contents.add(new ResourceMessageContent(blobId, "content-" + (index + 1), null));
      } else if (content instanceof ResourceToolContent resource) {
        contents.add(ingestResource(sessionId, index + 1, resource));
      } else {
        throw new IllegalArgumentException(
            "unsupported tool content kind for history materialization: "
                + content.getClass().getSimpleName());
      }
    }
    return List.copyOf(contents);
  }

  private ResourceMessageContent ingestResource(
      UUID sessionId, int index, ResourceToolContent resource) {
    ResourceRef ref = resource.resource();
    byte[] bytes = resolveBytes(ref);
    UUID blobId = ingestService.ingest(sessionId, bytes, ref.mediaType());
    String name = ref.name() == null ? "resource-" + index : ref.name();
    return new ResourceMessageContent(blobId, name, resource.preview());
  }

  /** 按 URI scheme 解析资源字节；ref 已在构造时完成全部确定性校验，此处只做有界取数。 */
  private byte[] resolveBytes(ResourceRef ref) {
    String scheme = URI.create(ref.uri()).getScheme();
    return switch (scheme) {
      case "data" -> decodeDataUri(ref.uri());
      case "file" -> readFile(URI.create(ref.uri()));
      case "http", "https" -> fetchHttp(ref.uri());
      case "s3" -> readS3(ref.uri());
      default -> throw new IllegalArgumentException("unsupported uri scheme: " + scheme);
    };
  }

  /** data URI：ref 构造时已验证精确 header 与规范载荷；此处按同一规则解码（<mediaType>, 或 <mediaType>;base64,）。 */
  private static byte[] decodeDataUri(String uri) {
    String ssp = URI.create(uri).getRawSchemeSpecificPart();
    int comma = ssp.indexOf(',');
    String header = ssp.substring(0, comma);
    String payload = ssp.substring(comma + 1);
    if (header.endsWith(";base64")) {
      return Base64.getDecoder().decode(payload);
    }
    byte[] raw = payload.getBytes(StandardCharsets.US_ASCII);
    ByteArrayOutputStream decoded = new ByteArrayOutputStream(raw.length);
    for (int index = 0; index < raw.length; index++) {
      byte current = raw[index];
      if (current == '%') {
        int value =
            Character.digit(payload.charAt(index + 1), 16) * 16
                + Character.digit(payload.charAt(index + 2), 16);
        decoded.write(value);
        index += 2;
      } else {
        decoded.write(current);
      }
    }
    return decoded.toByteArray();
  }

  private static byte[] readFile(URI uri) {
    Path path = Path.of(uri);
    try {
      long size = Files.size(path);
      if (size > MAX_RESOLVED_BYTES) {
        throw new IllegalArgumentException(
            "file resource must not exceed " + MAX_RESOLVED_BYTES + " bytes");
      }
      return Files.readAllBytes(path);
    } catch (IOException error) {
      throw new IllegalArgumentException("cannot read file resource " + uri, error);
    }
  }

  private byte[] fetchHttp(String uri) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(30)).GET().build();
      HttpResponse<InputStream> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalArgumentException(
            "http resource returned status " + response.statusCode() + ": " + uri);
      }
      long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
      if (declared > MAX_RESOLVED_BYTES) {
        throw new IllegalArgumentException(
            "http resource must not exceed " + MAX_RESOLVED_BYTES + " bytes");
      }
      return readBounded(response.body());
    } catch (IOException | InterruptedException error) {
      if (error instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new IllegalArgumentException("cannot fetch http resource " + uri, error);
    }
  }

  private byte[] readS3(String uri) {
    URI parsed = URI.create(uri);
    if (!storageBucket.equals(parsed.getHost())) {
      throw new IllegalArgumentException(
          "s3 resource bucket must be the configured storage bucket: " + uri);
    }
    String key = parsed.getRawPath().substring(1);
    // s3 URI 的有界下载（HEAD 校验声明长度 + 复核实际字节数），超限确定性拒绝。
    return s3StorageService.download(key, MAX_RESOLVED_BYTES).getBytes();
  }

  private static byte[] readBounded(InputStream input) throws IOException {
    try (InputStream in = input) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      byte[] chunk = new byte[8192];
      int total = 0;
      int read;
      while ((read = in.read(chunk)) != -1) {
        total += read;
        if (total > MAX_RESOLVED_BYTES) {
          throw new IllegalArgumentException(
              "http resource must not exceed " + MAX_RESOLVED_BYTES + " bytes");
        }
        buffer.write(chunk, 0, read);
      }
      return buffer.toByteArray();
    }
  }
}
