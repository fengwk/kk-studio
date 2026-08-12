package fun.fengwk.kkstudio.share.storage;

import lombok.Data;

/**
 * 全局 Blob 存储上传预约请求体。
 *
 * <p>{@code filename}、{@code mediaType}、{@code sizeBytes}、{@code sha256} 全部必填： 服务端以此确定去重命中、预签名 PUT
 * 的 {@code x-amz-checksum-sha256} 与 {@code Content-Type}，并在 complete 时校验对象真实大小与校验和。
 *
 * @author fengwk
 */
@Data
public class StorageUploadReserveRequestDTO {

  /** 必填原始文件名：非空白且不超过 512 字符。 */
  private String filename;

  /** 必填声明的媒体类型：合法 media type（≤255 字符、不含控制字符），将作为预签名 PUT 的 signed header。 */
  private String mediaType;

  /** 必填声明的字节大小：非负。 */
  private Long sizeBytes;

  /** 必填声明的 SHA-256：64 位十六进制摘要（大小写均可，服务端统一小写）。 */
  private String sha256;
}
