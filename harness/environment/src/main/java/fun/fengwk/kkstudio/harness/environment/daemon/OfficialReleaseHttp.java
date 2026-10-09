package fun.fengwk.kkstudio.harness.environment.daemon;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * 官方发布只读传输：只允许 HTTPS 官方 GitHub 主机，手动跟随有限次官方重定向，并对正文大小设上界。
 *
 * <p>GitHub Release 资产下载会 302 到 {@code *.githubusercontent.com} 的 CDN；因此这里刻意不关闭重定向，而是显式约束每一次
 * 跳转：方案必须是 https、主机必须是 {@code github.com} / {@code api.github.com} / {@code
 * *.githubusercontent.com}，跳转次数有限。 任何越界跳转、非 200 响应或超限正文都收敛为失败，绝不回退到任意地址。
 *
 * <p>正文下载不依赖 {@code Content-Disposition}，而是把响应流写到目标旁边的临时文件后原子替换，避免同名冲突与半成品。
 */
public final class OfficialReleaseHttp {

  /** 允许的重定向跳数上界。 */
  public static final int MAX_REDIRECTS = 5;

  /** 校验文件/元数据这类小正文的上界。 */
  public static final int MAX_TEXT_BODY_BYTES = 512 * 1024;

  /** 制品下载上界；与 Daemon JAR 的量级一致，避免无界磁盘占用。 */
  public static final long MAX_ARTIFACT_BYTES = 256L * 1024 * 1024;

  private static final String USER_AGENT = "kk-studio-official-release";

  private final HttpClient client;
  private final Duration timeout;

  public OfficialReleaseHttp(Duration timeout) {
    this.timeout = timeout;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /** GET 一个受限官方 URL 的有界正文；越界、超限或非 200 都返回空。 */
  public Optional<byte[]> get(String url, int maxBytes) {
    try {
      HttpResponse<InputStream> response = follow(url, "GET");
      if (response == null || response.statusCode() != 200) {
        return Optional.empty();
      }
      try (InputStream body = response.body()) {
        byte[] content = readBounded(body, maxBytes);
        return content == null ? Optional.empty() : Optional.of(content);
      }
    } catch (IOException error) {
      return Optional.empty();
    }
  }

  /** 下载受限官方 URL 到目标文件；失败抛出且不留下目标文件。 */
  public void download(String url, Path target) throws IOException {
    HttpResponse<InputStream> response = follow(url, "GET");
    if (response == null || response.statusCode() != 200) {
      throw new IOException("official release download did not return 200");
    }
    Path temporary = target.resolveSibling(target.getFileName() + ".part");
    try (InputStream body = response.body();
        OutputStream out = Files.newOutputStream(temporary)) {
      boolean bounded = transferBounded(body, out, MAX_ARTIFACT_BYTES);
      if (!bounded) {
        throw new IOException("official release download exceeds the size bound");
      }
    } catch (IOException error) {
      Files.deleteIfExists(temporary);
      throw error;
    }
    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
  }

  /** 只接受官方主机的 HEAD 可达性探测。 */
  public boolean reachable(String url) {
    try {
      HttpResponse<InputStream> response = follow(url, "HEAD");
      return response != null && response.statusCode() == 200;
    } catch (IOException error) {
      return false;
    }
  }

  /** 手动跟随有限次官方重定向，返回最终响应；任何越界跳转都返回 null。 */
  private HttpResponse<InputStream> follow(String url, String method) throws IOException {
    URI uri;
    try {
      uri = parseAllowed(url);
    } catch (IllegalArgumentException error) {
      return null;
    }
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .timeout(timeout)
              .header("User-Agent", USER_AGENT)
              .method(method, HttpRequest.BodyPublishers.noBody())
              .build();
      HttpResponse<InputStream> response = send(request);
      if (response == null) {
        return null;
      }
      int status = response.statusCode();
      if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
        return response;
      }
      closeQuietly(response);
      Optional<String> location = response.headers().firstValue("Location");
      if (location.isEmpty()) {
        return null;
      }
      try {
        uri = parseAllowed(uri.resolve(location.get()).toString());
      } catch (IllegalArgumentException error) {
        return null;
      }
    }
    return null;
  }

  private HttpResponse<InputStream> send(HttpRequest request) throws IOException {
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IOException("official release request interrupted", error);
    }
  }

  /** 解析并校验一个官方 URL：https + 允许主机。 */
  static URI parseAllowed(String url) {
    URI uri = URI.create(url);
    if (!isAllowed(uri)) {
      throw new IllegalArgumentException("not an official release URL: " + url);
    }
    return uri;
  }

  static boolean isAllowed(URI uri) {
    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      return false;
    }
    String host = uri.getHost();
    if (host == null) {
      return false;
    }
    String normalized = host.toLowerCase(Locale.ROOT);
    return normalized.equals("github.com")
        || normalized.equals("api.github.com")
        || normalized.endsWith(".githubusercontent.com");
  }

  private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
    byte[] buffer = new byte[8192];
    ByteArrayOutputStream collected = new ByteArrayOutputStream();
    int total = 0;
    int read;
    while ((read = input.read(buffer)) != -1) {
      total += read;
      if (total > maxBytes) {
        return null;
      }
      collected.write(buffer, 0, read);
    }
    return collected.toByteArray();
  }

  private static boolean transferBounded(InputStream input, OutputStream output, long maxBytes)
      throws IOException {
    byte[] buffer = new byte[8192];
    long total = 0;
    int read;
    while ((read = input.read(buffer)) != -1) {
      total += read;
      if (total > maxBytes) {
        return false;
      }
      output.write(buffer, 0, read);
    }
    return true;
  }

  private static void closeQuietly(HttpResponse<InputStream> response) {
    try {
      response.body().close();
    } catch (IOException ignored) {
      // 已经决定跟随/放弃该响应，关闭失败不影响判定。
    }
  }
}
