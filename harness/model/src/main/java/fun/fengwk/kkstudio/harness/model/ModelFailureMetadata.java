package fun.fengwk.kkstudio.harness.model;

/** 安全且有界的模型失败诊断元数据；不包含请求 URI、正文、凭据或任意响应标头。 */
public record ModelFailureMetadata(
    String provider,
    String model,
    Boolean retryable,
    Long retryAfterMillis,
    String requestId,
    String cfRay,
    TransportStage transportStage) {

  public ModelFailureMetadata {
    provider = bounded(provider, 128, "provider");
    model = bounded(model, 256, "model");
    requestId = bounded(requestId, 256, "requestId");
    cfRay = bounded(cfRay, 256, "cfRay");
    if (retryAfterMillis != null && (retryAfterMillis < 0 || retryAfterMillis > 3_600_000)) {
      throw new IllegalArgumentException("retryAfterMillis must be between 0 and one hour");
    }
  }

  public ModelFailureMetadata withIdentity(String providerName, String modelName) {
    return new ModelFailureMetadata(
        providerName, modelName, retryable, retryAfterMillis, requestId, cfRay, transportStage);
  }

  private static String bounded(String value, int maxLength, String name) {
    if (value == null) {
      return null;
    }
    String clean = value.trim();
    if (clean.isEmpty()) {
      return null;
    }
    if (clean.length() > maxLength || clean.indexOf('\r') >= 0 || clean.indexOf('\n') >= 0) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return clean;
  }

  /** 请求发送阶段，便于区分响应头之前的失败与流中终止。 */
  public enum TransportStage {
    BEFORE_HEADERS,
    STREAM
  }
}
