package fun.fengwk.kkstudio.platform.environment.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateArtifact;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 意图：官方发布解析必须只接受「非草稿、tag 匹配、五件套资产齐全、制品地址就是官方地址」的完整发布，并从官方校验文件取 SHA256；
 * 任何不完整或可疑的元数据都收敛为不可用，绝不回退到任意版本或任意 URL。
 */
class OfficialDaemonReleaseProviderTest {

  private static final String VERSION = "1.0.9";
  private static final String TAG = "v1.0.9";
  private static final String SHA = "a".repeat(64);
  private static final String JAR = "kk-studio-daemon-v1.0.9.jar";
  private static final String JAR_URL = DaemonUpdateArtifact.artifactUrl(VERSION);
  private static final String DOWNLOAD_BASE =
      "https://github.com/fengwk/kk-studio/releases/download/v1.0.9/";

  private static final List<String> COMPLETE_ASSETS =
      List.of(
          JAR, JAR + ".sha256", "kk-studio-daemon-v1.0.9.json", "LICENSE", "THIRD_PARTY_NOTICES");

  @Test
  void resolvesCompleteNonDraftRelease() {
    FakeFeed feed = new FakeFeed(release(false, TAG, COMPLETE_ASSETS, JAR_URL), SHA + "  " + JAR);

    Optional<DaemonReleaseTarget> target =
        new OfficialDaemonReleaseProvider(feed, VERSION).resolveTarget();

    assertTrue(target.isPresent());
    assertEquals(VERSION, target.get().targetVersion());
    assertEquals(JAR_URL, target.get().artifactUrl());
    assertEquals(SHA, target.get().artifactSha256());
    assertEquals(TAG, feed.requestedTag);
  }

  @Test
  void rejectsDraft() {
    FakeFeed feed = new FakeFeed(release(true, TAG, COMPLETE_ASSETS, JAR_URL), SHA);
    assertTrue(new OfficialDaemonReleaseProvider(feed, VERSION).resolveTarget().isEmpty());
  }

  @Test
  void rejectsTagMismatch() {
    FakeFeed feed = new FakeFeed(release(false, "v9.9.9", COMPLETE_ASSETS, JAR_URL), SHA);
    assertTrue(new OfficialDaemonReleaseProvider(feed, VERSION).resolveTarget().isEmpty());
  }

  @Test
  void rejectsIncompleteAssetSet() {
    List<String> missingNotices = new ArrayList<>(COMPLETE_ASSETS);
    missingNotices.remove("THIRD_PARTY_NOTICES");
    FakeFeed feed = new FakeFeed(release(false, TAG, missingNotices, JAR_URL), SHA);
    assertTrue(new OfficialDaemonReleaseProvider(feed, VERSION).resolveTarget().isEmpty());
  }

  @Test
  void rejectsArtifactUrlThatIsNotTheOfficialUrl() {
    FakeFeed feed =
        new FakeFeed(release(false, TAG, COMPLETE_ASSETS, "https://evil.example/daemon.jar"), SHA);
    assertTrue(new OfficialDaemonReleaseProvider(feed, VERSION).resolveTarget().isEmpty());
  }

  @Test
  void rejectsMalformedChecksum() {
    FakeFeed feed = new FakeFeed(release(false, TAG, COMPLETE_ASSETS, JAR_URL), "not-a-digest");
    assertTrue(new OfficialDaemonReleaseProvider(feed, VERSION).resolveTarget().isEmpty());
  }

  @Test
  void unavailableWhenPackagedVersionIsMissing() {
    FakeFeed feed = new FakeFeed(release(false, TAG, COMPLETE_ASSETS, JAR_URL), SHA);
    assertTrue(new OfficialDaemonReleaseProvider(feed, "").resolveTarget().isEmpty());
  }

  @Test
  void unavailableWhenMetadataIsMissing() {
    FakeFeed feed = new FakeFeed(null, SHA);
    assertTrue(new OfficialDaemonReleaseProvider(feed, VERSION).resolveTarget().isEmpty());
  }

  private static String release(
      boolean draft, String tag, List<String> assets, String jarDownloadUrl) {
    StringBuilder builder = new StringBuilder();
    builder.append("{\"draft\":").append(draft).append(",\"tag_name\":\"").append(tag);
    builder.append("\",\"assets\":[");
    for (int i = 0; i < assets.size(); i++) {
      String name = assets.get(i);
      String url = name.equals(JAR) ? jarDownloadUrl : DOWNLOAD_BASE + name;
      if (i > 0) {
        builder.append(',');
      }
      builder
          .append("{\"name\":\"")
          .append(name)
          .append("\",\"browser_download_url\":\"")
          .append(url)
          .append("\"}");
    }
    builder.append("]}");
    return builder.toString();
  }

  /** 只读 fake：按请求的 tag 返回元数据，按 URL 后缀返回校验文件。 */
  private static final class FakeFeed implements OfficialDaemonReleaseProvider.OfficialReleaseFeed {

    private final String metadata;
    private final String checksum;
    private String requestedTag;

    private FakeFeed(String metadata, String checksum) {
      this.metadata = metadata;
      this.checksum = checksum;
    }

    @Override
    public Optional<String> readTagRelease(String tag) {
      requestedTag = tag;
      return Optional.ofNullable(metadata);
    }

    @Override
    public Optional<String> readAsset(String url) {
      return url.endsWith(".sha256") ? Optional.of(checksum) : Optional.empty();
    }
  }
}
