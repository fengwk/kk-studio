package fun.fengwk.kkstudio.platform.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * {@link S3ObjectKeyNormalizer} 共享对象键规则测试。
 *
 * @author fengwk
 */
public class S3ObjectKeyNormalizerTest {

  @Test
  public void shouldRejectBlankAndLeadingSlash() {
    // 绝对风格路径不能被静默改写，否则签名与调用方意图不一致。
    assertThrows(IllegalArgumentException.class, () -> S3ObjectKeyNormalizer.normalize(null));
    assertThrows(IllegalArgumentException.class, () -> S3ObjectKeyNormalizer.normalize(""));
    assertThrows(IllegalArgumentException.class, () -> S3ObjectKeyNormalizer.normalize("   "));
    assertThrows(IllegalArgumentException.class, () -> S3ObjectKeyNormalizer.normalize("/file"));
  }

  @Test
  public void shouldRejectDotSegmentsAndControls() {
    assertThrows(
        IllegalArgumentException.class, () -> S3ObjectKeyNormalizer.normalize("dir/./file"));
    assertThrows(
        IllegalArgumentException.class, () -> S3ObjectKeyNormalizer.normalize("dir/../file"));
    assertThrows(
        IllegalArgumentException.class, () -> S3ObjectKeyNormalizer.normalize("dir/\nfile"));
    assertThrows(
        IllegalArgumentException.class, () -> S3ObjectKeyNormalizer.normalize("dir/\u0085file"));
  }

  @Test
  public void shouldEnforceUtf8ByteLength() {
    String atLimit = "a".repeat(S3ObjectKeyNormalizer.MAX_KEY_LENGTH_BYTES);
    String multibyteOverLimit = "中".repeat(342);

    // ASCII 边界可用，多字节字符按 UTF-8 字节而不是 Java char 数量计算。
    assertEquals(atLimit, S3ObjectKeyNormalizer.normalize(atLimit));
    assertThrows(
        IllegalArgumentException.class, () -> S3ObjectKeyNormalizer.normalize(multibyteOverLimit));
  }

  @Test
  public void shouldPreserveValidObjectKeyExactly() {
    assertEquals(
        "目录/.hidden/file.v2.png", S3ObjectKeyNormalizer.normalize("目录/.hidden/file.v2.png"));
  }
}
