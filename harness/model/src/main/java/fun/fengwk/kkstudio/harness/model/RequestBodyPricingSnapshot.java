package fun.fengwk.kkstudio.harness.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 一次成功请求的 request-body 条件快照：只包含条件指纹到匹配布尔值的不可变映射。
 *
 * <p>快照绝不包含请求 body、字段原值或期望值。指纹由 {@link ModelRequestBodyPricing#fingerprint} 计算，是 64 位小写 SHA-256
 * 十六进制文本；快照最多记录 {@value #MAX_CONDITIONS} 个条件，未知结果不写入映射。
 *
 * <p>本类型是可选的历史元数据：旧记录没有快照，读取方必须把它当作未知事实，绝不能用今天的配置反推匹配结果。
 */
public record RequestBodyPricingSnapshot(Map<String, Boolean> matches) {

  public static final int MAX_CONDITIONS = 128;

  private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

  public static final RequestBodyPricingSnapshot EMPTY = new RequestBodyPricingSnapshot(Map.of());

  public RequestBodyPricingSnapshot {
    Objects.requireNonNull(matches, "matches");
    if (matches.size() > MAX_CONDITIONS) {
      throw new IllegalArgumentException(
          "request body pricing snapshot must not exceed " + MAX_CONDITIONS + " conditions");
    }
    TreeMap<String, Boolean> sorted = new TreeMap<>();
    for (Map.Entry<String, Boolean> entry : matches.entrySet()) {
      String fingerprint = entry.getKey();
      if (fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()) {
        throw new IllegalArgumentException(
            "request body pricing fingerprint must be 64 lowercase hex characters");
      }
      if (entry.getValue() == null) {
        throw new IllegalArgumentException("request body pricing match must not be null");
      }
      sorted.put(fingerprint, entry.getValue());
    }
    matches = Collections.unmodifiableMap(sorted);
  }

  /** 未记录任何条件结果；仍需与「没有快照」区分时由调用方保留 null。 */
  public boolean isEmpty() {
    return matches.isEmpty();
  }
}
