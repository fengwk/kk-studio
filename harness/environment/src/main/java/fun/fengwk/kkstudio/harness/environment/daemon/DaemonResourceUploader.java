package fun.fengwk.kkstudio.harness.environment.daemon;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;

import java.io.IOException;

/**
 * Daemon 侧把内存字节直传对象存储的窄端口。
 *
 * <p>实现负责完整的控制面交互：申请上传票据、按票据把字节 PUT 到对象存储、提交并等待 READY，最终返回可写入终态 payload 的瞬时 canonical {@code
 * blob-upload:<uploadId>} 引用。实现必须保证同一 transferId 的重复请求在 Daemon 侧不产生额外上传，且失败时以确定异常收敛。
 */
public interface DaemonResourceUploader {

  /**
   * 上传一个 resource 字节。
   *
   * @param invocationId 本次调用 id（transfer 绑定到该调用）
   * @param mediaType canonical 媒体类型
   * @param name 可空展示名
   * @param bytes 待上传字节
   * @return 携带非空 size/sha 的瞬时 {@code blob-upload:} 引用
   * @throws IOException 上传、提交或等待 READY 失败
   */
  ResourceRef upload(String invocationId, String mediaType, String name, byte[] bytes)
      throws IOException;
}
