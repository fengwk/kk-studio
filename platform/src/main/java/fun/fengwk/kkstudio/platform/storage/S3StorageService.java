package fun.fengwk.kkstudio.platform.storage;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.InputStream;

/**
 * S3 存储服务.
 *
 * @author fengwk
 */
public interface S3StorageService {

  PutObjectResponse putObject(
      String key, InputStream content, long contentLength, String contentType);

  boolean exists(String key);

  S3ObjectMetadata headObject(String key);

  S3ObjectStream readObject(String key);

  void deleteObject(String key);

  /**
   * 幂等删除：对象不存在（404）时静默成功，其它 S3 错误照常抛出。
   *
   * <p>新存储基础依赖该语义清理临时对象与两阶段删除的 blob 对象； 未实现的实现会抛出 {@link UnsupportedOperationException}。
   */
  default void deleteObjectIfExists(String key) {
    try {
      deleteObject(key);
    } catch (NoSuchKeyException e) {
      // 对象已不存在，视为删除成功。
    } catch (AwsServiceException e) {
      if (e.statusCode() != 404) {
        throw e;
      }
    }
  }

  /**
   * 以 checksum mode（{@code x-amz-checksum-mode: ENABLED}）HEAD 对象，返回带 {@code checksumSha256}
   * 的元数据，用于服务端校验浏览器直传内容的完整性。
   *
   * <p>未实现的实现会抛出 {@link UnsupportedOperationException}。
   */
  default S3ObjectMetadata headObjectWithChecksum(String key) {
    throw new UnsupportedOperationException(
        "headObjectWithChecksum is not supported by this implementation");
  }

  /**
   * 在同一固定 bucket 内复制对象（保留源对象元数据）。目标 key 已存在时按内容幂等覆盖。
   *
   * <p>未实现的实现会抛出 {@link UnsupportedOperationException}。
   */
  default void copyObject(String sourceKey, String targetKey) {
    throw new UnsupportedOperationException("copyObject is not supported by this implementation");
  }

  String getPublicUrl(String key);

  byte[] download(String key);

  /**
   * 从固定 bucket 读取对象并强制大小上限。实现应先 HEAD 校验声明长度，再下载并复核实际字节数；任一阶段超过上限都抛出 {@link
   * IllegalArgumentException}，避免把超大对象加载到 ComfyUI 等下游消费方。
   */
  S3ObjectContent download(String key, long maxSizeBytes);
}
