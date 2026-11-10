package fun.fengwk.kkstudio.harness.model.limits;

import java.util.Objects;

/**
 * 已知限制的逐项最严值合并（PLAN 7.1 模型组约定）。
 *
 * <p>对每个数值字段取全部成员已知值中的最小值：任何成员的请求都要成立，因此以最严成员为准。全部成员对某项都未知时结果仍未知， 绝不从部分已知值外推。文本字段（{@code
 * sources}、{@code verifiedAt}）全部一致时保留，不一致时逐个列出。
 */
public final class RequestLimitsMerger {

  private RequestLimitsMerger() {}

  /** 返回全部成员逐项最严的合并快照；不接收 null 元素。 */
  public static RequestLimits merge(Iterable<RequestLimits> members) {
    Objects.requireNonNull(members, "members");
    Long bodyMaxBytes = null;
    ImageRequestLimits image = ImageRequestLimits.UNKNOWN;
    DocumentRequestLimits document = DocumentRequestLimits.UNKNOWN;
    AudioRequestLimits audio = AudioRequestLimits.UNKNOWN;
    VideoRequestLimits video = VideoRequestLimits.UNKNOWN;
    Long visualUnitsMax = null;
    String sources = null;
    String verifiedAt = null;
    boolean first = true;
    int count = 0;
    for (RequestLimits member : members) {
      Objects.requireNonNull(member, "member");
      count++;
      bodyMaxBytes = strictest(bodyMaxBytes, member.bodyMaxBytes());
      image = mergeImage(image, member.image());
      document = mergeDocument(document, member.document());
      audio = mergeAudio(audio, member.audio());
      video = mergeVideo(video, member.video());
      visualUnitsMax = strictest(visualUnitsMax, member.visualUnitsMax());
      sources = joinText(sources, member.sources(), first);
      verifiedAt = joinText(verifiedAt, member.verifiedAt(), first);
      first = false;
    }
    if (count == 0) {
      throw new IllegalArgumentException("at least one member is required");
    }
    return new RequestLimits(
        bodyMaxBytes, image, document, audio, video, visualUnitsMax, sources, verifiedAt);
  }

  private static Long strictest(Long current, Long candidate) {
    if (candidate == null) {
      return current;
    }
    return current == null ? candidate : Math.min(current, candidate);
  }

  private static Integer strictest(Integer current, Integer candidate) {
    if (candidate == null) {
      return current;
    }
    return current == null ? candidate : Math.min(current, candidate);
  }

  private static ImageRequestLimits mergeImage(
      ImageRequestLimits current, ImageRequestLimits next) {
    if (next == null || next.unknown()) {
      return current;
    }
    if (current.unknown()) {
      return next;
    }
    return new ImageRequestLimits(
        strictest(current.singleRawBytes(), next.singleRawBytes()),
        strictest(current.singleEncodedBytes(), next.singleEncodedBytes()),
        strictest(current.totalBytes(), next.totalBytes()),
        strictest(current.maxCount(), next.maxCount()),
        strictest(current.maxSide(), next.maxSide()),
        strictest(current.manyImageThreshold(), next.manyImageThreshold()),
        strictest(current.manyImageMaxSide(), next.manyImageMaxSide()));
  }

  private static DocumentRequestLimits mergeDocument(
      DocumentRequestLimits current, DocumentRequestLimits next) {
    if (next == null || next.unknown()) {
      return current;
    }
    if (current.unknown()) {
      return next;
    }
    return new DocumentRequestLimits(
        strictest(current.singleRawBytes(), next.singleRawBytes()),
        strictest(current.totalRawBytes(), next.totalRawBytes()),
        strictest(current.maxPages(), next.maxPages()));
  }

  private static AudioRequestLimits mergeAudio(
      AudioRequestLimits current, AudioRequestLimits next) {
    if (next == null || next.unknown()) {
      return current;
    }
    if (current.unknown()) {
      return next;
    }
    return new AudioRequestLimits(
        strictest(current.singleRawBytes(), next.singleRawBytes()),
        strictest(current.maxCount(), next.maxCount()),
        strictest(current.maxDurationSeconds(), next.maxDurationSeconds()));
  }

  private static VideoRequestLimits mergeVideo(
      VideoRequestLimits current, VideoRequestLimits next) {
    if (next == null || next.unknown()) {
      return current;
    }
    if (current.unknown()) {
      return next;
    }
    return new VideoRequestLimits(
        strictest(current.singleRawBytes(), next.singleRawBytes()),
        strictest(current.maxCount(), next.maxCount()),
        strictest(current.maxDurationSeconds(), next.maxDurationSeconds()));
  }

  private static String joinText(String current, String next, boolean first) {
    if (next == null) {
      return current;
    }
    if (first || current == null) {
      return next;
    }
    if (current.equals(next)) {
      return current;
    }
    return current + ", " + next;
  }
}
