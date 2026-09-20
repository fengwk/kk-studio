package fun.fengwk.kkstudio.platform.plugin.resource;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 媒体类型的权威判定：先看字节的 magic 特征，只有特征无法判定时才回退到响应的 {@code Content-Type}。
 *
 * <p>远端响应头是攻击者可影响的值，因此它永远不是第一判定来源；同时返回值被规范化为小写 {@code type/subtype}（去掉参数、拒绝非法字符）， 使写入 Blob 元数据的类型与
 * {@code ResourceRef} 的 wire 约束一致。
 */
final class MediaTypeSniffer {

  /** 规范 {@code type/subtype} 形态，与 {@code ResourceRef} 的 wire 约束一致。 */
  private static final Pattern CANONICAL = Pattern.compile("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+");

  private static final String APPLICATION_PDF = "application/pdf";
  private static final String APPLICATION_ZIP = "application/zip";
  private static final String VIDEO_MP4 = "video/mp4";
  private static final String AUDIO_MP4 = "audio/mp4";
  private static final String APPLICATION_OGG = "application/ogg";

  private MediaTypeSniffer() {}

  /** 用 magic 特征判定媒体类型；无法判定时返回空。 */
  static Optional<String> sniff(byte[] head) {
    if (head == null || head.length < 4) {
      return Optional.empty();
    }
    if (startsWith(head, 0xFF, 0xD8, 0xFF)) {
      return Optional.of("image/jpeg");
    }
    if (startsWith(head, 0x89, 0x50, 0x4E, 0x47)) {
      return Optional.of("image/png");
    }
    if (startsWithAscii(head, "GIF87a") || startsWithAscii(head, "GIF89a")) {
      return Optional.of("image/gif");
    }
    if (startsWithAscii(head, "BM") && head.length >= 14) {
      return Optional.of("image/bmp");
    }
    if (startsWith(head, 0x49, 0x49, 0x2A, 0x00) || startsWith(head, 0x4D, 0x4D, 0x00, 0x2A)) {
      return Optional.of("image/tiff");
    }
    if (startsWithAscii(head, "RIFF") && head.length >= 12) {
      String form = ascii(head, 8, 4);
      if ("WEBP".equals(form)) {
        return Optional.of("image/webp");
      }
      if ("WAVE".equals(form)) {
        return Optional.of("audio/wav");
      }
      if ("AVI ".equals(form)) {
        return Optional.of("video/x-msvideo");
      }
      return Optional.empty();
    }
    if (startsWithAscii(head, "fLaC")) {
      return Optional.of("audio/flac");
    }
    if (startsWithAscii(head, "OggS")) {
      return Optional.of(APPLICATION_OGG);
    }
    if (startsWithAscii(head, "ID3")) {
      return Optional.of("audio/mpeg");
    }
    if (startsWithAscii(head, "%PDF")) {
      return Optional.of(APPLICATION_PDF);
    }
    if (startsWith(head, 0x1A, 0x45, 0xDF, 0xA3)) {
      return Optional.of("video/x-matroska");
    }
    if (startsWith(head, 0x50, 0x4B, 0x03, 0x04) || startsWith(head, 0x50, 0x4B, 0x05, 0x06)) {
      return Optional.of(APPLICATION_ZIP);
    }
    if (head.length >= 12 && "ftyp".equals(ascii(head, 4, 4))) {
      return Optional.of(isoBaseMediaType(ascii(head, 8, 4)));
    }
    if (head.length >= 2
        && (head[0] & 0xFF) == 0xFF
        && ((head[1] & 0xFF) == 0xFB || (head[1] & 0xFF) == 0xF3 || (head[1] & 0xFF) == 0xF2)) {
      return Optional.of("audio/mpeg");
    }
    if (head.length >= 2 && (head[0] & 0xFF) == 0xFF && ((head[1] & 0xF6) == 0xF0)) {
      return Optional.of("audio/aac");
    }
    return Optional.empty();
  }

  /** 规范化响应头里的 Content-Type；非法或缺失时返回空。 */
  static Optional<String> normalizeHeader(String contentType) {
    if (contentType == null) {
      return Optional.empty();
    }
    String value = contentType.strip();
    int separator = value.indexOf(';');
    if (separator >= 0) {
      value = value.substring(0, separator);
    }
    value = value.strip().toLowerCase(Locale.ROOT);
    Matcher matcher = CANONICAL.matcher(value);
    return matcher.matches() ? Optional.of(value) : Optional.empty();
  }

  /** ISO base media（mp4/m4a/heic/avif）由 brand 决定家族。 */
  private static String isoBaseMediaType(String brand) {
    String normalized = brand.toLowerCase(Locale.ROOT);
    if (normalized.startsWith("hei")
        || normalized.startsWith("hev")
        || normalized.startsWith("mif")) {
      return "image/heic";
    }
    if (normalized.startsWith("avi")) {
      return "image/avif";
    }
    if (normalized.startsWith("m4a") || normalized.startsWith("mp4a")) {
      return AUDIO_MP4;
    }
    if (normalized.startsWith("qt")) {
      return "video/quicktime";
    }
    return VIDEO_MP4;
  }

  private static boolean startsWith(byte[] head, int... prefix) {
    if (head.length < prefix.length) {
      return false;
    }
    for (int index = 0; index < prefix.length; index++) {
      if ((head[index] & 0xFF) != prefix[index]) {
        return false;
      }
    }
    return true;
  }

  private static boolean startsWithAscii(byte[] head, String prefix) {
    return prefix.length() <= head.length && prefix.equals(ascii(head, 0, prefix.length()));
  }

  private static String ascii(byte[] head, int offset, int length) {
    if (offset + length > head.length) {
      return "";
    }
    return new String(head, offset, length, StandardCharsets.US_ASCII);
  }
}
