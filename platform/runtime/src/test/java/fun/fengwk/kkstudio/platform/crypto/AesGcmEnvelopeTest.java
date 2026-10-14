package fun.fengwk.kkstudio.platform.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/** 共享 AES-256-GCM envelope 原语的格式、AAD 绑定与失败关闭契约。 */
class AesGcmEnvelopeTest {

  private static final int FORMAT = 7;
  private static final byte[] AAD = "aad-context".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PLAINTEXT = "{\"secret\":\"value\"}".getBytes(StandardCharsets.UTF_8);

  private static SecretKey key(byte fill) {
    byte[] raw = new byte[AesGcmEnvelope.KEY_BYTES];
    Arrays.fill(raw, fill);
    return new SecretKeySpec(raw, "AES");
  }

  @Test
  void roundTripsWithExplicitFormatAndAad() throws GeneralSecurityException {
    byte[] envelope = AesGcmEnvelope.encrypt(key((byte) 1), FORMAT, AAD, PLAINTEXT);

    assertArrayEquals(PLAINTEXT, AesGcmEnvelope.decrypt(key((byte) 1), FORMAT, AAD, envelope));
    assertTrue(envelope.length > AesGcmEnvelope.NONCE_BYTES);
  }

  @Test
  void usesFreshNoncePerEncryption() {
    byte[] first = AesGcmEnvelope.encrypt(key((byte) 1), FORMAT, AAD, PLAINTEXT);
    byte[] second = AesGcmEnvelope.encrypt(key((byte) 1), FORMAT, AAD, PLAINTEXT);

    assertFalse(Arrays.equals(first, second));
  }

  @Test
  void rejectsWrongFormatVersionAadAndTampering() {
    byte[] envelope = AesGcmEnvelope.encrypt(key((byte) 1), FORMAT, AAD, PLAINTEXT);

    assertThrows(
        GeneralSecurityException.class,
        () -> AesGcmEnvelope.decrypt(key((byte) 1), FORMAT + 1, AAD, envelope));
    assertThrows(
        GeneralSecurityException.class,
        () ->
            AesGcmEnvelope.decrypt(
                key((byte) 1), FORMAT, "other-aad".getBytes(StandardCharsets.UTF_8), envelope));

    byte[] tampered = envelope.clone();
    tampered[tampered.length - 1] ^= 0x01;
    assertThrows(
        GeneralSecurityException.class,
        () -> AesGcmEnvelope.decrypt(key((byte) 1), FORMAT, AAD, tampered));
  }

  @Test
  void rejectsTruncatedOrNullEnvelope() {
    assertThrows(
        GeneralSecurityException.class,
        () -> AesGcmEnvelope.decrypt(key((byte) 1), FORMAT, AAD, null));
    assertThrows(
        GeneralSecurityException.class,
        () -> AesGcmEnvelope.decrypt(key((byte) 1), FORMAT, AAD, new byte[0]));
    assertThrows(
        GeneralSecurityException.class,
        () -> AesGcmEnvelope.decrypt(key((byte) 1), FORMAT, AAD, new byte[28]));
  }

  @Test
  void rejectsKeyMaterialThatIsNotAes256() {
    SecretKey shortKey = new SecretKeySpec(new byte[16], "AES");
    SecretKey wrongAlgorithm = new SecretKeySpec(new byte[AesGcmEnvelope.KEY_BYTES], "HmacSHA256");

    assertThrows(
        IllegalArgumentException.class,
        () -> AesGcmEnvelope.encrypt(shortKey, FORMAT, AAD, PLAINTEXT));
    assertThrows(
        IllegalArgumentException.class,
        () -> AesGcmEnvelope.encrypt(wrongAlgorithm, FORMAT, AAD, PLAINTEXT));
    assertThrows(
        IllegalArgumentException.class, () -> AesGcmEnvelope.encrypt(null, FORMAT, AAD, PLAINTEXT));
    assertThrows(
        IllegalArgumentException.class,
        () -> AesGcmEnvelope.decrypt(shortKey, FORMAT, AAD, new byte[64]));
  }

  @Test
  void rejectsFormatVersionOutsideUnsignedByteAndMissingArguments() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AesGcmEnvelope.encrypt(key((byte) 1), 256, AAD, PLAINTEXT));
    assertThrows(
        IllegalArgumentException.class,
        () -> AesGcmEnvelope.encrypt(key((byte) 1), -1, AAD, PLAINTEXT));
    assertThrows(
        NullPointerException.class,
        () -> AesGcmEnvelope.encrypt(key((byte) 1), FORMAT, null, PLAINTEXT));
    assertThrows(
        NullPointerException.class, () -> AesGcmEnvelope.encrypt(key((byte) 1), FORMAT, AAD, null));
  }
}
