package fun.fengwk.kkstudio.harness.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.type.LogicalType;

import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;

import java.util.Objects;

/**
 * 持久化到 {@code builtin_model_invocation.error} 的 terminal error snapshot。错误分类、消息、HTTP 状态和 Provider
 * code 保持原有 wire 字段；新增安全诊断通过可选 {@code metadata} 字段传递。
 *
 * <p>HTTP 状态与原始 Provider code 不从 message 推断，也不参与 metadata；重试资格只由结构化字段判定，实际重试由 HTTP 状态策略或共享预算决策。
 */
public record ModelInvocationError(
    ProviderErrorKind kind,
    String message,
    Integer httpStatus,
    String providerErrorCode,
    ModelFailureMetadata metadata) {

  private static final ObjectMapper STRICT_ERROR_FIELDS = strictErrorFields();

  /** 上游原始 code 的最大保留长度。 */
  public static final int MAX_PROVIDER_CODE_LENGTH = 128;

  public ModelInvocationError {
    kind = Objects.requireNonNull(kind, "kind");
    message = requireNonBlank(message, "message");
    if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
      throw new IllegalArgumentException("httpStatus must be an HTTP status between 100 and 599");
    }
    providerErrorCode = normalizeProviderCode(providerErrorCode);
  }

  /** 明确 non-HTTP 语义的便利构造：不携带 HTTP 状态与原始 code。 */
  public ModelInvocationError(ProviderErrorKind kind, String message) {
    this(kind, message, null, null, null);
  }

  /** 携带 HTTP 状态、不含原始 code 的便利构造。 */
  public ModelInvocationError(ProviderErrorKind kind, String message, Integer httpStatus) {
    this(kind, message, httpStatus, null, null);
  }

  public ModelInvocationError(
      ProviderErrorKind kind, String message, Integer httpStatus, String providerErrorCode) {
    this(kind, message, httpStatus, providerErrorCode, null);
  }

  /**
   * 该错误是否可能被重试的统一候选判定：明确 {@code retryable:false}、认证终态、HTTP 402、非 HTTP BILLING、{@link
   * ProviderErrorKind#CONTEXT_LIMIT}、{@link ProviderErrorKind#REQUEST_BODY_LIMIT}、{@link
   * ProviderErrorKind#MEDIA_LIMIT} 与 {@link ProviderErrorKind#CANCELLED} 永不普通重试；其它带 HTTP 状态、或
   * TRANSIENT / INVALID_RESPONSE 的失败是候选。
   *
   * <p>候选只表示「允许进入重试判定」，实际是否重试由模型 HTTP 状态策略（带 HTTP 状态时）或调用方共享的 {@code InvocationRetryPolicy}
   * 预算唯一决策。上游 {@code retryable:true} 不能覆盖本地终态或预算。
   */
  public boolean retryCandidate() {
    if ((metadata != null && Boolean.FALSE.equals(metadata.retryable()))
        || kind == ProviderErrorKind.AUTHENTICATION
        || (kind == ProviderErrorKind.BILLING && (httpStatus == null || httpStatus == 402))
        || kind == ProviderErrorKind.CONTEXT_LIMIT
        || kind == ProviderErrorKind.REQUEST_BODY_LIMIT
        || kind == ProviderErrorKind.MEDIA_LIMIT
        || kind == ProviderErrorKind.CANCELLED) {
      return false;
    }
    return httpStatus != null
        || kind == ProviderErrorKind.TRANSIENT
        || kind == ProviderErrorKind.INVALID_RESPONSE;
  }

  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  private static ModelInvocationError fromJson(JsonNode value) {
    if (!value.isObject()) {
      throw new IllegalArgumentException("modelInvocationError must be an object");
    }
    ObjectNode fields = (ObjectNode) value.deepCopy();
    if (!fields.has("metadata")) {
      fields.putNull("metadata");
    }
    ErrorFields error = STRICT_ERROR_FIELDS.treeToValue(fields, ErrorFields.class);
    return new ModelInvocationError(
        error.kind(),
        error.message(),
        error.httpStatus(),
        error.providerErrorCode(),
        error.metadata());
  }

  private static ObjectMapper strictErrorFields() {
    return JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .withCoercionConfig(
            LogicalType.Textual,
            config ->
                config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
        .build();
  }

  private record ErrorFields(
      ProviderErrorKind kind,
      String message,
      Integer httpStatus,
      String providerErrorCode,
      ModelFailureMetadata metadata) {}

  private static String normalizeProviderCode(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String trimmed = value.trim();
    if (!trimmed.matches("[A-Za-z0-9_.:-]+")) {
      return null;
    }
    return trimmed.length() > MAX_PROVIDER_CODE_LENGTH
        ? trimmed.substring(0, MAX_PROVIDER_CODE_LENGTH)
        : trimmed;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
