package fun.fengwk.kkstudio.platform.plugin.resource;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 基于 JDK {@link HttpClient} 的媒体传输实现。
 *
 * <p>三个安全相关的固定选择：禁用自动重定向（3xx 交回网关判定，避免一次跳转绕过地址准入）、连接建立超时与整体请求期限都由配置给出、 上传请求体直接从临时文件流式发出而不把内容读进内存。
 */
final class JdkRemoteMediaTransport implements RemoteMediaTransport {

  private final HttpClient client;

  JdkRemoteMediaTransport(Duration connectTimeout) {
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public MediaResponse get(URI uri, Duration timeout) {
    HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
    try {
      HttpResponse<InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      return new MediaResponse(
          response.statusCode(), flatten(response.headers().map()), response.body());
    } catch (IOException error) {
      throw new PluginResourceUnavailableException("remote media request failed", error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new PluginResourceUnavailableException("remote media request was interrupted", error);
    }
  }

  @Override
  public int put(URI uri, Map<String, String> headers, Path file, Duration timeout) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout);
    if (headers != null) {
      for (Map.Entry<String, String> header : headers.entrySet()) {
        if (header.getKey() == null
            || header.getValue() == null
            || RemoteMediaTransport.isRestrictedHeader(header.getKey())) {
          continue;
        }
        builder.header(header.getKey(), header.getValue());
      }
    }
    try {
      builder.PUT(HttpRequest.BodyPublishers.ofFile(file));
    } catch (FileNotFoundException error) {
      throw new PluginResourceUnavailableException("staged media temp file disappeared", error);
    }
    try {
      return client.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    } catch (IOException error) {
      throw new PluginResourceUnavailableException("presigned media upload failed", error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new PluginResourceUnavailableException("presigned media upload was interrupted", error);
    }
  }

  private static Map<String, List<String>> flatten(Map<String, List<String>> headers) {
    if (headers == null || headers.isEmpty()) {
      return Map.of();
    }
    return Map.copyOf(headers);
  }
}
