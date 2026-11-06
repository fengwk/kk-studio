package fun.fengwk.kkstudio.project.http;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.common.number.DecimalLongs;
import fun.fengwk.kkstudio.platform.http.RequestParameters;

import java.util.UUID;

/** 将 Project HTTP 参数解析失败转换为不回显输入值的领域校验错误。 */
final class ProjectHttpInputs {
  private ProjectHttpInputs() {}

  static UUID uuid(String value, String field) {
    try {
      return Uuids.parseCanonical(value, field);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(field, field + " must be a canonical UUID");
    }
  }

  static long nonNegative(String value, String field) {
    try {
      return DecimalLongs.parseNonNegative(value, field);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(field, field + " must be a canonical non-negative decimal");
    }
  }

  static int limit(String value, int defaultLimit, int maxLimit) {
    try {
      return RequestParameters.limit(value, defaultLimit, maxLimit);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation("limit", "limit must be within [1, " + maxLimit + "]");
    }
  }
}
