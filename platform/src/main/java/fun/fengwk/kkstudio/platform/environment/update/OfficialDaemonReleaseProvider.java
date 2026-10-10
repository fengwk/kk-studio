package fun.fengwk.kkstudio.platform.environment.update;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateArtifact;
import fun.fengwk.kkstudio.harness.environment.daemon.OfficialReleaseHttp;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生产用官方发布解析器：目标版本取运行中 Platform 的打包版本，只接受官方 GitHub tag Release，且必须是完整、非草稿的发布。
 *
 * <p>完整性来自官方 tag 元数据（{@code api.github.com/repos/fengwk/kk-studio/releases/tags/<tag>}）：草稿、tag
 * 不匹配或五件套资产 缺一都判定为不可用。制品 URL 与 SHA256 只取自该元数据与官方校验文件，绝不接受任意版本、任意 URL 或 "latest"。
 *
 * <p>没有可用打包版本时同样判定为不可用；Platform 升级时只需重新打包，无需在代码里重复维护版本号。
 */
public class OfficialDaemonReleaseProvider implements DaemonReleaseProvider {

  private static final Pattern DIGEST_IN_TEXT = Pattern.compile("[0-9a-fA-F]{64}");

  /** 官方 Release 必须完整包含的固定资产名（与 {@code scripts/daemon/prepare-release.sh} 一致）。 */
  private static final List<String> FIXED_ASSETS = List.of("LICENSE", "THIRD_PARTY_NOTICES");

  private final OfficialReleaseFeed feed;

  private final String targetVersion;

  private final ObjectMapper mapper = new ObjectMapper();

  public OfficialDaemonReleaseProvider(OfficialReleaseFeed feed) {
    this(feed, PlatformBuildInfo.version());
  }

  OfficialDaemonReleaseProvider(OfficialReleaseFeed feed, String targetVersion) {
    this.feed = feed;
    this.targetVersion = targetVersion == null ? "" : targetVersion.trim();
  }

  @Override
  public Optional<DaemonReleaseTarget> resolveTarget() {
    if (targetVersion.isEmpty()) {
      return Optional.empty();
    }
    String tag = DaemonUpdateArtifact.tag(targetVersion);
    Optional<String> metadata = feed.readTagRelease(tag);
    if (metadata.isEmpty()) {
      return Optional.empty();
    }
    JsonNode release = parse(metadata.get());
    if (release == null
        || release.path("draft").asBoolean(true)
        || !tag.equals(text(release, "tag_name"))) {
      return Optional.empty();
    }
    String artifactUrl = DaemonUpdateArtifact.artifactUrl(targetVersion);
    if (!requiredAssetsPresent(release, tag, targetVersion)
        || !artifactAssetMatches(release, artifactUrl)) {
      return Optional.empty();
    }
    Optional<String> checksumText = feed.readAsset(DaemonUpdateArtifact.checksumUrl(targetVersion));
    Optional<String> digest = checksumText.flatMap(OfficialDaemonReleaseProvider::parseDigest);
    if (digest.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new DaemonReleaseTarget(targetVersion, artifactUrl, digest.get()));
  }

  /** 五件套资产必须齐全：jar、其校验文件、确定性元数据、LICENSE 与 THIRD_PARTY_NOTICES。 */
  private static boolean requiredAssetsPresent(JsonNode release, String tag, String version) {
    Set<String> names = new HashSet<>();
    for (JsonNode asset : release.path("assets")) {
      names.add(asset.path("name").asText(""));
    }
    if (!names.containsAll(FIXED_ASSETS)) {
      return false;
    }
    names.removeAll(FIXED_ASSETS);
    String jar = DaemonUpdateArtifact.jarName(version);
    return names.contains(jar)
        && names.contains(jar + ".sha256")
        && names.contains("kk-studio-daemon-" + tag + ".json");
  }

  /** 制品资产的官方下载地址必须与本地构造的官方 URL 完全一致，防止元数据把下载指向别处。 */
  private static boolean artifactAssetMatches(JsonNode release, String expectedArtifactUrl) {
    for (JsonNode asset : release.path("assets")) {
      if (expectedArtifactUrl.endsWith("/" + asset.path("name").asText(""))) {
        return expectedArtifactUrl.equals(asset.path("browser_download_url").asText(""));
      }
    }
    return false;
  }

  private JsonNode parse(String json) {
    try {
      return mapper.readTree(json);
    } catch (RuntimeException | JsonProcessingException error) {
      return null;
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isTextual() ? value.textValue() : null;
  }

  /** 从官方校验文件文本中提取 SHA256（容忍 {@code "<digest> <file>"} 与纯摘要两种写法）。 */
  static Optional<String> parseDigest(String text) {
    Matcher matcher = DIGEST_IN_TEXT.matcher(text);
    if (!matcher.find()) {
      return Optional.empty();
    }
    return Optional.of(matcher.group().toLowerCase(Locale.ROOT));
  }

  /** 官方发布只读探测端口：读取 tag 元数据与资产正文。 */
  public interface OfficialReleaseFeed {
    /** 读取指定 tag 的官方 Release 元数据（有界 JSON 文本）。 */
    Optional<String> readTagRelease(String tag);

    /** 读取一个官方资产 URL 的正文（有界文本）。 */
    Optional<String> readAsset(String url);
  }

  /** 基于受限官方 HTTPS 传输的实现：只读、无凭据、有限重定向。 */
  public static final class HttpOfficialReleaseFeed implements OfficialReleaseFeed {

    private static final String TAG_RELEASE_URL =
        "https://api.github.com/repos/fengwk/kk-studio/releases/tags/";

    private final OfficialReleaseHttp http;

    public HttpOfficialReleaseFeed(Duration timeout) {
      this.http = new OfficialReleaseHttp(timeout);
    }

    @Override
    public Optional<String> readTagRelease(String tag) {
      return http.get(TAG_RELEASE_URL + tag, OfficialReleaseHttp.MAX_TEXT_BODY_BYTES)
          .map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    @Override
    public Optional<String> readAsset(String url) {
      return http.get(url, OfficialReleaseHttp.MAX_TEXT_BODY_BYTES)
          .map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }
  }
}
