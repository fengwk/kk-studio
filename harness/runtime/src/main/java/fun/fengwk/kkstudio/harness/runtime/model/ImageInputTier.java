package fun.fengwk.kkstudio.harness.runtime.model;

/**
 * 用户为图片输入选择的档位：{@code 720P} / {@code 1080P} 在 provider attempt 按图片方向等比缩小到档位框内， {@code ORIGINAL}
 * 始终使用上传时的原字节。
 *
 * <p>档位只对图片媒体有意义，并且只描述「允许的最大显示尺寸」，不描述任何 Provider 协议参数：物化时命中档位框的图片保留原字节（含 EXIF 元数据），
 * 超出才重新编码；音频、视频、文档等非图片资源不携带档位。durable 与 wire 都使用 {@link #wireName()}，绝不使用枚举名。
 */
public enum ImageInputTier {

  /** 横向最长边 1280、纵向最长边 720；正方形由较短的 720 界定。 */
  P720("720P", 1280, 720),

  /** 横向最长边 1920、纵向最长边 1080；正方形由较短的 1080 界定。 */
  P1080("1080P", 1920, 1080),

  /** 原字节，永不缩放。 */
  ORIGINAL("ORIGINAL", Integer.MAX_VALUE, Integer.MAX_VALUE);

  private final String wireName;
  private final int maxLongEdge;
  private final int maxShortEdge;

  ImageInputTier(String wireName, int maxLongEdge, int maxShortEdge) {
    this.wireName = wireName;
    this.maxLongEdge = maxLongEdge;
    this.maxShortEdge = maxShortEdge;
  }

  /** wire 与 durable JSON 中的规范名称。 */
  public String wireName() {
    return wireName;
  }

  /** 解析规范名称；未知名称显式失败，绝不回退到任何默认档位。 */
  public static ImageInputTier fromWireName(String wireName) {
    for (ImageInputTier tier : values()) {
      if (tier.wireName.equals(wireName)) {
        return tier;
      }
    }
    throw new IllegalArgumentException("unknown image input tier: " + wireName);
  }

  /** 档位框的长边：横向图片的宽度上限与纵向图片的高度上限。 */
  public int maxLongEdge() {
    return maxLongEdge;
  }

  /** 档位框的短边：横向图片的高度上限、纵向图片的宽度上限，以及正方形的边长。 */
  public int maxShortEdge() {
    return maxShortEdge;
  }

  /** 是否保留原字节；只有 {@link #ORIGINAL} 为真。 */
  public boolean isOriginal() {
    return this == ORIGINAL;
  }
}
