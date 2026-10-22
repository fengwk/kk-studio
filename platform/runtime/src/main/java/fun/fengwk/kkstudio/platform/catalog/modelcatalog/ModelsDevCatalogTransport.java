package fun.fengwk.kkstudio.platform.catalog.modelcatalog;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.share.ai.catalog.ModelCatalogSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * 仅访问两个固定的 models.dev HTTPS 端点；20 秒超时、响应上限 32 MiB、禁止重定向。
 *
 * <p>Content-Type 必须是 JSON，HTML 或任意错误页不会被当作目录解析。下载结果只保留内存字符串，不落库。
 */
@Component
public class ModelsDevCatalogTransport implements ModelCatalogTransport {

  static final int MAX_BYTES = 32 * 1024 * 1024;
  static final Duration TIMEOUT = Duration.ofSeconds(20);

  private static final Map<ModelCatalogSource, URI> ENDPOINTS = endpoints();

  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .connectTimeout(TIMEOUT)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  @Override
  public String fetch(ModelCatalogSource source) {
    URI endpoint = ENDPOINTS.get(source);
    HttpRequest request =
        HttpRequest.newBuilder(endpoint)
            .timeout(TIMEOUT)
            .header("Accept", "application/json")
            .GET()
            .build();
    try {
      HttpResponse<InputStream> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        if (response.statusCode() != 200) {
          throw new ModelCatalogFetchException(
              "catalog source " + source.value() + " returned HTTP " + response.statusCode());
        }
        String contentType = response.headers().firstValue("content-type").orElse("");
        if (!contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
          throw new ModelCatalogFetchException(
              "catalog source " + source.value() + " did not return JSON");
        }
        return readBounded(body);
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new ModelCatalogFetchException("catalog refresh was interrupted");
    } catch (IOException error) {
      throw new ModelCatalogFetchException("catalog source " + source.value() + " is unavailable");
    }
  }

  @PreDestroy
  public void close() {
    httpClient.close();
  }

  static String readBounded(InputStream body) throws IOException {
    byte[] buffer = new byte[8192];
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    int read;
    while ((read = body.read(buffer)) != -1) {
      if (output.size() + read > MAX_BYTES) {
        throw new ModelCatalogFetchException("catalog response exceeds 32 MiB");
      }
      output.write(buffer, 0, read);
    }
    return output.toString(StandardCharsets.UTF_8);
  }

  static Map<ModelCatalogSource, URI> endpoints() {
    Map<ModelCatalogSource, URI> endpoints = new EnumMap<>(ModelCatalogSource.class);
    endpoints.put(ModelCatalogSource.MODELS_DEV_V1, URI.create("https://models.dev/api.json"));
    endpoints.put(
        ModelCatalogSource.MODELS_DEV_V2,
        URI.create("https://models.dev/experimental/v2.0/api.json"));
    return Map.copyOf(endpoints);
  }
}
