package fun.fengwk.kkstudio.core.storage;

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

  String getPublicUrl(String key);

  byte[] download(String key);

  /**
   * 从固定 bucket 读取对象并强制大小上限。实现应先 HEAD 校验声明长度，再下载并复核实际字节数；任一阶段超过上限都抛出 {@link
   * IllegalArgumentException}，避免把超大对象加载到 ComfyUI 等下游消费方。
   */
  S3ObjectContent download(String key, long maxSizeBytes);
}
