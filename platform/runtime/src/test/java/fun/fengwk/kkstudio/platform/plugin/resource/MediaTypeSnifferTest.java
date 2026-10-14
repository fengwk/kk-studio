package fun.fengwk.kkstudio.platform.plugin.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** 媒体类型特征嗅探与请求头规范化测试。 */
class MediaTypeSnifferTest {

  /** 验证图片格式的魔数特征识别：JPEG, PNG, GIF, BMP, TIFF, WEBP, HEIC, AVIF。 */
  @Test
  void sniffsImageMagicSignatures() {
    // PNG
    byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    assertEquals(Optional.of("image/png"), MediaTypeSniffer.sniff(png));

    // JPEG
    byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
    assertEquals(Optional.of("image/jpeg"), MediaTypeSniffer.sniff(jpeg));

    // GIF87a & GIF89a
    assertEquals(
        Optional.of("image/gif"),
        MediaTypeSniffer.sniff("GIF87a-sample-data".getBytes(StandardCharsets.US_ASCII)));
    assertEquals(
        Optional.of("image/gif"),
        MediaTypeSniffer.sniff("GIF89a-sample-data".getBytes(StandardCharsets.US_ASCII)));

    // BMP (至少 14 字节)
    byte[] bmp = new byte[16];
    bmp[0] = 'B';
    bmp[1] = 'M';
    assertEquals(Optional.of("image/bmp"), MediaTypeSniffer.sniff(bmp));

    // TIFF (LE & BE)
    byte[] tiffLe = new byte[] {0x49, 0x49, 0x2A, 0x00, 0, 0};
    assertEquals(Optional.of("image/tiff"), MediaTypeSniffer.sniff(tiffLe));
    byte[] tiffBe = new byte[] {0x4D, 0x4D, 0x00, 0x2A, 0, 0};
    assertEquals(Optional.of("image/tiff"), MediaTypeSniffer.sniff(tiffBe));

    // WEBP (RIFF....WEBP)
    byte[] webp = createRiffHeader("WEBP");
    assertEquals(Optional.of("image/webp"), MediaTypeSniffer.sniff(webp));

    // ISO base media: HEIC & AVIF
    byte[] heic = createFtypHeader("heic");
    assertEquals(Optional.of("image/heic"), MediaTypeSniffer.sniff(heic));
    byte[] avif = createFtypHeader("avif");
    assertEquals(Optional.of("image/avif"), MediaTypeSniffer.sniff(avif));
  }

  /** 验证音频格式的魔数特征识别：WAV, FLAC, MP3(ID3), MP3(Sync), AAC, M4A。 */
  @Test
  void sniffsAudioMagicSignatures() {
    // WAV (RIFF....WAVE)
    byte[] wav = createRiffHeader("WAVE");
    assertEquals(Optional.of("audio/wav"), MediaTypeSniffer.sniff(wav));

    // FLAC
    byte[] flac = "fLaC-stream-header".getBytes(StandardCharsets.US_ASCII);
    assertEquals(Optional.of("audio/flac"), MediaTypeSniffer.sniff(flac));

    // MP3 (ID3 标签)
    byte[] id3 = "ID3-audio-content".getBytes(StandardCharsets.US_ASCII);
    assertEquals(Optional.of("audio/mpeg"), MediaTypeSniffer.sniff(id3));

    // MP3 (MPEG frame sync: FF FB, FF F3, FF F2)
    assertEquals(
        Optional.of("audio/mpeg"),
        MediaTypeSniffer.sniff(new byte[] {(byte) 0xFF, (byte) 0xFB, 0x10, 0x00}));
    assertEquals(
        Optional.of("audio/mpeg"),
        MediaTypeSniffer.sniff(new byte[] {(byte) 0xFF, (byte) 0xF3, 0x10, 0x00}));
    assertEquals(
        Optional.of("audio/mpeg"),
        MediaTypeSniffer.sniff(new byte[] {(byte) 0xFF, (byte) 0xF2, 0x10, 0x00}));

    // AAC (ADTS frame sync: FF F0)
    assertEquals(
        Optional.of("audio/aac"),
        MediaTypeSniffer.sniff(new byte[] {(byte) 0xFF, (byte) 0xF0, 0x10, 0x00}));

    // ISO base media: M4A
    byte[] m4a = createFtypHeader("m4a ");
    assertEquals(Optional.of("audio/mp4"), MediaTypeSniffer.sniff(m4a));
  }

  /** 验证视频与容器/文档格式魔数特征识别：MP4, QuickTime, AVI, WebM/Matroska, OGG, PDF, ZIP。 */
  @Test
  void sniffsVideoAndContainerSignatures() {
    // MP4 video
    byte[] mp4 = createFtypHeader("mp42");
    assertEquals(Optional.of("video/mp4"), MediaTypeSniffer.sniff(mp4));

    // QuickTime (MOV)
    byte[] qt = createFtypHeader("qt  ");
    assertEquals(Optional.of("video/quicktime"), MediaTypeSniffer.sniff(qt));

    // AVI (RIFF....AVI )
    byte[] avi = createRiffHeader("AVI ");
    assertEquals(Optional.of("video/x-msvideo"), MediaTypeSniffer.sniff(avi));

    // WebM / Matroska (EBML: 1A 45 DF A3)
    byte[] ebml = new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0};
    assertEquals(Optional.of("video/x-matroska"), MediaTypeSniffer.sniff(ebml));

    // OggS
    byte[] ogg = "OggS-payload-header".getBytes(StandardCharsets.US_ASCII);
    assertEquals(Optional.of("application/ogg"), MediaTypeSniffer.sniff(ogg));

    // PDF
    byte[] pdf = "%PDF-1.7-document".getBytes(StandardCharsets.US_ASCII);
    assertEquals(Optional.of("application/pdf"), MediaTypeSniffer.sniff(pdf));

    // ZIP (PK\003\004 和 PK\005\006)
    byte[] zip1 = new byte[] {0x50, 0x4B, 0x03, 0x04, 0, 0};
    assertEquals(Optional.of("application/zip"), MediaTypeSniffer.sniff(zip1));
    byte[] zip2 = new byte[] {0x50, 0x4B, 0x05, 0x06, 0, 0};
    assertEquals(Optional.of("application/zip"), MediaTypeSniffer.sniff(zip2));
  }

  /** 数据过短、无魔数特征或为 null 时必须返回 Optional.empty()。 */
  @Test
  void returnsEmptyOnUnrecognizedOrShortData() {
    assertTrue(MediaTypeSniffer.sniff(null).isEmpty());
    assertTrue(MediaTypeSniffer.sniff(new byte[0]).isEmpty());
    assertTrue(MediaTypeSniffer.sniff(new byte[] {0x01, 0x02, 0x03}).isEmpty());
    assertTrue(MediaTypeSniffer.sniff(new byte[] {0x00, 0x01, 0x02, 0x03, 0x04}).isEmpty());
  }

  /** 验证 Content-Type 请求头规范化：小写化、去除分号参数、去除首尾空白。 */
  @Test
  void normalizesContentTypeHeader() {
    assertEquals(
        Optional.of("image/png"), MediaTypeSniffer.normalizeHeader("image/png; charset=utf-8"));
    assertEquals(
        Optional.of("application/json"),
        MediaTypeSniffer.normalizeHeader("   APPLICATION/JSON ; indent=2   "));
    assertEquals(Optional.of("text/plain"), MediaTypeSniffer.normalizeHeader("text/plain"));
    assertEquals(Optional.of("audio/mpeg"), MediaTypeSniffer.normalizeHeader("Audio/MPEG; q=0.9"));
  }

  /** 格式非法的 Content-Type 请求头必须返回 Optional.empty()。 */
  @Test
  void rejectsMalformedContentTypeHeader() {
    assertTrue(MediaTypeSniffer.normalizeHeader(null).isEmpty());
    assertTrue(MediaTypeSniffer.normalizeHeader("").isEmpty());
    assertTrue(MediaTypeSniffer.normalizeHeader("   ").isEmpty());
    assertTrue(MediaTypeSniffer.normalizeHeader("invalid-no-slash").isEmpty());
    assertTrue(MediaTypeSniffer.normalizeHeader("image/").isEmpty());
    assertTrue(MediaTypeSniffer.normalizeHeader("/png").isEmpty());
    assertTrue(MediaTypeSniffer.normalizeHeader("image/png<script>").isEmpty());
  }

  /** 验证 PluginMediaFamily 族与 MIME 类型的匹配判定。 */
  @Test
  void pluginMediaFamilyAcceptsExpectedMimeTypes() {
    // IMAGE
    assertTrue(PluginMediaFamily.IMAGE.accepts("image/png"));
    assertTrue(PluginMediaFamily.IMAGE.accepts("image/jpeg"));
    assertTrue(PluginMediaFamily.IMAGE.accepts("image/webp"));
    assertFalse(PluginMediaFamily.IMAGE.accepts("audio/wav"));
    assertFalse(PluginMediaFamily.IMAGE.accepts("video/mp4"));
    assertFalse(PluginMediaFamily.IMAGE.accepts("application/pdf"));

    // AUDIO
    assertTrue(PluginMediaFamily.AUDIO.accepts("audio/mpeg"));
    assertTrue(PluginMediaFamily.AUDIO.accepts("audio/flac"));
    assertTrue(PluginMediaFamily.AUDIO.accepts("audio/wav"));
    assertFalse(PluginMediaFamily.AUDIO.accepts("image/png"));
    assertFalse(PluginMediaFamily.AUDIO.accepts("video/mp4"));

    // VIDEO
    assertTrue(PluginMediaFamily.VIDEO.accepts("video/mp4"));
    assertTrue(PluginMediaFamily.VIDEO.accepts("video/x-matroska"));
    assertTrue(PluginMediaFamily.VIDEO.accepts("video/quicktime"));
    assertFalse(PluginMediaFamily.VIDEO.accepts("audio/mp4"));
    assertFalse(PluginMediaFamily.VIDEO.accepts("image/png"));

    // DOCUMENT
    assertTrue(PluginMediaFamily.DOCUMENT.accepts("application/pdf"));
    assertTrue(PluginMediaFamily.DOCUMENT.accepts("application/zip"));
    assertTrue(PluginMediaFamily.DOCUMENT.accepts("text/plain"));
    assertFalse(PluginMediaFamily.DOCUMENT.accepts("plain-no-slash"));
    assertFalse(PluginMediaFamily.DOCUMENT.accepts(""));
    assertFalse(PluginMediaFamily.DOCUMENT.accepts(null));
  }

  private static byte[] createRiffHeader(String form) {
    byte[] bytes = new byte[12];
    bytes[0] = 'R';
    bytes[1] = 'I';
    bytes[2] = 'F';
    bytes[3] = 'F';
    // 4..7 为数据长度，留 0
    bytes[8] = (byte) form.charAt(0);
    bytes[9] = (byte) form.charAt(1);
    bytes[10] = (byte) form.charAt(2);
    bytes[11] = (byte) form.charAt(3);
    return bytes;
  }

  private static byte[] createFtypHeader(String brand) {
    byte[] bytes = new byte[12];
    // 0..3 为 box 长度
    bytes[4] = 'f';
    bytes[5] = 't';
    bytes[6] = 'y';
    bytes[7] = 'p';
    bytes[8] = (byte) brand.charAt(0);
    bytes[9] = (byte) brand.charAt(1);
    bytes[10] = (byte) brand.charAt(2);
    bytes[11] = (byte) brand.charAt(3);
    return bytes;
  }
}
