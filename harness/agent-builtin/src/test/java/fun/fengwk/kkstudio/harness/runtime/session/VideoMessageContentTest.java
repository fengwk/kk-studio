package fun.fengwk.kkstudio.harness.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** VideoMessageContent 与现有图片、音频内容共享 non-blank 持久化边界。 */
class VideoMessageContentTest {

  @Test
  void preservesMediaTypeAndSource() {
    VideoMessageContent content =
        new VideoMessageContent("video/mp4", "https://example.test/video.mp4");

    assertEquals("video/mp4", content.mediaType());
    assertEquals("https://example.test/video.mp4", content.source());
  }

  @Test
  void rejectsBlankMediaTypeOrSource() {
    assertThrows(
        IllegalArgumentException.class, () -> new VideoMessageContent(null, "video-source"));
    assertThrows(
        IllegalArgumentException.class, () -> new VideoMessageContent(" ", "video-source"));
    assertThrows(IllegalArgumentException.class, () -> new VideoMessageContent("video/mp4", null));
    assertThrows(IllegalArgumentException.class, () -> new VideoMessageContent("video/mp4", " "));
  }
}
