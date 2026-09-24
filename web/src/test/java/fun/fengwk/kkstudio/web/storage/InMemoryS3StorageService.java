package fun.fengwk.kkstudio.web.storage;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import fun.fengwk.kkstudio.platform.storage.ReadDeadline;
import fun.fengwk.kkstudio.platform.storage.S3ObjectContent;
import fun.fengwk.kkstudio.platform.storage.S3ObjectMetadata;
import fun.fengwk.kkstudio.platform.storage.S3ObjectStream;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用内存 S3 实现：对象按 key 存字节与 content type，HEAD（含 checksum mode）如实报告存储内容的大小与 SHA-256， 使 complete
 * 的校验路径与真实对象存储行为一致。
 *
 * @author fengwk
 */
public class InMemoryS3StorageService implements S3StorageService {

  private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
  private final Map<String, String> contentTypes = new ConcurrentHashMap<>();

  /** 清空全部对象（每个测试前调用）。 */
  public void clear() {
    objects.clear();
    contentTypes.clear();
  }

  public boolean hasObject(String key) {
    return objects.containsKey(key);
  }

  public byte[] objectBytes(String key) {
    byte[] bytes = objects.get(key);
    return bytes == null ? null : bytes.clone();
  }

  /** 模拟浏览器直传：直接写入 uploads/{uploadId}/original 对象。 */
  public void putDirect(String key, byte[] content, String contentType) {
    objects.put(key, content.clone());
    contentTypes.put(key, contentType);
  }

  @Override
  public PutObjectResponse putObject(
      String key, InputStream content, long contentLength, String contentType) {
    try {
      objects.put(key, content.readAllBytes());
      contentTypes.put(key, contentType);
      return PutObjectResponse.builder().eTag("fake-etag").build();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public boolean exists(String key) {
    return objects.containsKey(key);
  }

  @Override
  public S3ObjectMetadata headObject(String key) {
    return metadata(key, false);
  }

  @Override
  public S3ObjectMetadata headObjectWithChecksum(String key) {
    return metadata(key, true);
  }

  private S3ObjectMetadata metadata(String key, boolean withChecksum) {
    byte[] bytes = objects.get(key);
    if (bytes == null) {
      throw NoSuchKeyException.builder().message("missing: " + key).build();
    }
    return new S3ObjectMetadata(
        bytes.length,
        contentTypes.get(key),
        "fake-etag",
        withChecksum ? Base64.getEncoder().encodeToString(sha256(bytes)) : null);
  }

  @Override
  public S3ObjectStream readObject(String key) {
    byte[] bytes = objects.get(key);
    if (bytes == null) {
      throw NoSuchKeyException.builder().message("missing: " + key).build();
    }
    GetObjectResponse response =
        GetObjectResponse.builder()
            .contentLength((long) bytes.length)
            .contentType(contentTypes.get(key))
            .build();
    return new S3ObjectStream(
        new ResponseInputStream<>(
            response, AbortableInputStream.create(new ByteArrayInputStream(bytes.clone()))),
        metadata(key, false));
  }

  @Override
  public S3ObjectStream readObject(String key, ReadDeadline deadline) {
    // 内存实现没有 S3 网络握手，直接复用无截止读取；响应体消费的绝对截止由读取边界看门狗负责。
    return readObject(key);
  }

  @Override
  public void deleteObject(String key) {
    objects.remove(key);
    contentTypes.remove(key);
  }

  @Override
  public void copyObject(String sourceKey, String targetKey) {
    byte[] bytes = objects.get(sourceKey);
    if (bytes == null) {
      throw NoSuchKeyException.builder().message("missing source: " + sourceKey).build();
    }
    objects.put(targetKey, bytes.clone());
    contentTypes.put(targetKey, contentTypes.get(sourceKey));
  }

  @Override
  public byte[] download(String key) {
    byte[] bytes = objects.get(key);
    if (bytes == null) {
      throw NoSuchKeyException.builder().message("missing: " + key).build();
    }
    return bytes.clone();
  }

  @Override
  public S3ObjectContent download(String key, long maxSizeBytes) {
    byte[] bytes = download(key);
    if (bytes.length > maxSizeBytes) {
      throw new IllegalArgumentException("S3 object exceeds max input file size");
    }
    return new S3ObjectContent(bytes, contentTypes.get(key));
  }

  static byte[] sha256(byte[] content) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(content);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
