package fun.fengwk.kkstudio.core.ai.image;

import fun.fengwk.convention4j.common.json.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.ai.image.configuration.GptImage2Properties;
import fun.fengwk.kkstudio.core.ai.image.model.GptImage2Request;
import fun.fengwk.kkstudio.core.ai.image.model.GptImage2Response;
import fun.fengwk.kkstudio.core.ai.image.model.GptImage2Size;
import fun.fengwk.kkstudio.core.ai.image.model.ImageData;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GPT Image 2 图片生成服务.
 *
 * @author fengwk
 */
@Slf4j
@Service
public class GptImage2Service {

  private static final int MAX_LOG_BASE64_LENGTH = 64;
  private static final Pattern BASE64_PATTERN =
      Pattern.compile("(\\\"base64\\\"\\s*:\\s*\\\")([^\\\"]+)(\\\")");

  private GptImage2Properties properties;
  private final HttpClient httpClient;

  public GptImage2Service(GptImage2Properties properties, HttpClient httpClient) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
  }

  /**
   * 使用 gpt image 2 模型生成.
   *
   * @param prompt 提示词
   * @param size 图片尺寸，传 null 或 AUTO 则按自动处理
   * @param images 参考图片列表，留空则文生图，上传图片则按编辑图处理
   * @return 生成结果
   */
  public GptImage2Response generate(String prompt, GptImage2Size size, List<ImageData> images)
      throws IOException, InterruptedException {
    Objects.requireNonNull(prompt, "prompt must not be null");

    // 构建请求体
    GptImage2Request request = new GptImage2Request();
    request.setPrompt(prompt);
    if (size != null) {
      request.setSize(size);
    }
    request.setImages(images);
    String requestBody = JsonUtils.toJson(request);

    // 构建 HTTP 请求
    HttpRequest httpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(properties.getUrl()))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + properties.getApiKey())
            .timeout(Duration.ofMillis(properties.getTimeoutMs()))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

    // 发送请求
    HttpResponse<String> response =
        httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    log.info(
        "GPT Image 2 response, statusCode: {}, body: {}",
        response.statusCode(),
        truncateBase64(response.body()));

    // 处理响应
    if (response.statusCode() >= 200 && response.statusCode() < 300) {
      return JsonUtils.fromJson(response.body(), GptImage2Response.class);
    } else {
      throw new IOException(
          "GPT Image 2 request failed with status "
              + response.statusCode()
              + ": "
              + response.body());
    }
  }

  private String truncateBase64(String body) {
    if (body == null || body.isEmpty()) {
      return body;
    }
    Matcher matcher = BASE64_PATTERN.matcher(body);
    StringBuffer sb = new StringBuffer();
    while (matcher.find()) {
      String base64 = matcher.group(2);
      String truncatedBase64 =
          base64.length() > MAX_LOG_BASE64_LENGTH
              ? base64.substring(0, MAX_LOG_BASE64_LENGTH) + "...<truncated>"
              : base64;
      matcher.appendReplacement(
          sb, Matcher.quoteReplacement(matcher.group(1) + truncatedBase64 + matcher.group(3)));
    }
    matcher.appendTail(sb);
    return sb.toString();
  }
}
