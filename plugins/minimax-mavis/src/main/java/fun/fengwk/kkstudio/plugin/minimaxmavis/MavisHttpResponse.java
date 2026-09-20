package fun.fengwk.kkstudio.plugin.minimaxmavis;

/**
 * 一次 Mavis HTTP 响应。
 *
 * <p>响应 body 可能包含新签发的 token，因此 {@link #toString()} 只输出状态码与 body 长度。
 */
public record MavisHttpResponse(int statusCode, String body) {

  public MavisHttpResponse {
    if (statusCode < 100 || statusCode > 599) {
      throw new MavisValidationException("HTTP status code is out of range: " + statusCode);
    }
    body = body == null ? "" : body;
  }

  @Override
  public String toString() {
    return "MavisHttpResponse[statusCode=" + statusCode + ", bodyLength=" + body.length() + "]";
  }
}
