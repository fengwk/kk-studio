package fun.fengwk.kkstudio.harness.provider.transport;

import java.util.List;
import java.util.Map;

/** HTTP 连接握手成功（2xx 且 text/event-stream）后的响应元数据。 */
public record HttpOpenMetadata(int statusCode, Map<String, List<String>> headers) {

  public HttpOpenMetadata {
    headers = HeaderSanitizer.sanitizeHeaders(headers);
  }
}
