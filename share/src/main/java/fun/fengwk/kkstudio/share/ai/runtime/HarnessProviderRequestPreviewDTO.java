package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

import java.time.Instant;

/**
 * 发送前请求预览：把一次尚未提交的用户草稿按正式发送路径物化为 Provider 协议将发送的最终请求体。
 *
 * <p>预览是「点击时」快照：它不消费任何 upload、不写任何 durable 状态、不触发 transport，因此随后真正发送时的历史、附件与 Provider
 * 配置都可能已经变化；{@link #snapshotNotice} 恒为 {@link #SNAPSHOT_NOTICE}，调用方必须把它当作能力声明的一部分而不是噪声。
 *
 * <p>{@link #bodyJson} 是最终请求体的原样 UTF-8 解码，绝不截断；endpoint、credential、对象存储地址与任何认证 Header 一律不进入本 DTO。
 */
@Data
public class HarnessProviderRequestPreviewDTO {

  /** 视图判别符：恒为 {@value #KIND}。 */
  public static final String KIND = "DRAFT_REQUEST_PREVIEW";

  /** 固定快照提示：预览只在点击时成立，绝不保证随后发送得到逐字节相同的请求。 */
  public static final String SNAPSHOT_NOTICE =
      "Preview is a click-time snapshot of the request that would be sent now. It does not "
          + "consume uploads or write anything, so a later send may observe different history, "
          + "attachments or provider configuration.";

  /** 视图判别符，恒为 {@value #KIND}。 */
  private String kind;

  /** 预览生成时间。 */
  private Instant generatedAt;

  /** 本次请求实际使用的 Provider 协议类型名。 */
  private String providerType;

  /** 本次请求的模型名（最终 wire 请求使用的模型标识）。 */
  private String modelName;

  /** 最终请求体的 UTF-8 字节数。 */
  private int bodyByteSize;

  /** 最终请求体 JSON 原文，未经截断。 */
  private String bodyJson;

  /** 预览所基于的 source head Entry id（canonical UUID 文本），与实际发送的 cursor 一致。 */
  private String sourceHeadEntryId;

  /** 固定提示：预览是点击时快照，不保证随后发送得到相同请求。 */
  private String snapshotNotice;
}
