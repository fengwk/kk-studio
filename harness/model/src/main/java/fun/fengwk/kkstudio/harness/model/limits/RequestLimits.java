package fun.fengwk.kkstudio.harness.model.limits;

/**
 * 一次 Provider 请求的已计量限制快照（PLAN 11.1）。
 *
 * <p>全部字段可空：null 表示该端点的该项限制未知，未知即缺省，不补默认值；发送方只对已知项执行发送前检查， 未知项依赖上游拒绝后的分类。{@code bodyMaxBytes} 是
 * BODY 口径（最终 UTF-8 请求体）；{@code visualUnitsMax} 是协议自定义的视觉计量单位上限（如 Gemini 的 {@code mediaResolution}
 * 总量）；{@code sources} 与 {@code verifiedAt} 记录预设依据与核对时间，只作诊断展示，不参与发送检查。
 */
public record RequestLimits(
    Long bodyMaxBytes,
    ImageRequestLimits image,
    DocumentRequestLimits document,
    AudioRequestLimits audio,
    VideoRequestLimits video,
    Long visualUnitsMax,
    String sources,
    String verifiedAt) {

  public RequestLimits {
    requirePositive(bodyMaxBytes, "bodyMaxBytes");
    requirePositive(visualUnitsMax, "visualUnitsMax");
    image = image == null ? ImageRequestLimits.UNKNOWN : image;
    document = document == null ? DocumentRequestLimits.UNKNOWN : document;
    audio = audio == null ? AudioRequestLimits.UNKNOWN : audio;
    video = video == null ? VideoRequestLimits.UNKNOWN : video;
  }

  private static void requirePositive(Long value, String name) {
    if (value != null && value <= 0) {
      throw new IllegalArgumentException(name + " must be positive when present");
    }
  }
}
