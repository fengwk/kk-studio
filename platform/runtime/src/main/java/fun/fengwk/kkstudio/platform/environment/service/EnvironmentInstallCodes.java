package fun.fengwk.kkstudio.platform.environment.service;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallCodeDTO;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** 用当前凭证摘要签发五分钟安装 code；不存储、不缓存，token 轮换后旧签名自然失效。 */
public final class EnvironmentInstallCodes {
  static final Duration LIFETIME = Duration.ofMinutes(5);
  private static final String PURPOSE = "kk-studio-environment-install";
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private EnvironmentInstallCodes() {}

  public static EnvironmentInstallCodeDTO issue(
      UUID environmentId, String registrationTokenHash, Instant now) {
    long expires = now.plus(LIFETIME).getEpochSecond();
    EnvironmentInstallCodeDTO dto = new EnvironmentInstallCodeDTO();
    dto.setCode(expires + "." + sign(environmentId, registrationTokenHash, expires));
    dto.setExpiresAt(Instant.ofEpochSecond(expires));
    return dto;
  }

  public static void verify(
      UUID environmentId, String registrationTokenHash, String code, Instant now) {
    int split = code == null ? -1 : code.indexOf('.');
    if (split <= 0 || split != code.lastIndexOf('.')) {
      throw invalid();
    }
    long expires;
    byte[] signature;
    try {
      expires = Long.parseLong(code.substring(0, split));
      signature = DECODER.decode(code.substring(split + 1));
    } catch (IllegalArgumentException error) {
      throw invalid();
    }
    byte[] expected = signBytes(environmentId, registrationTokenHash, expires);
    if (!MessageDigest.isEqual(signature, expected) || now.getEpochSecond() >= expires) {
      throw invalid();
    }
  }

  private static String sign(UUID environmentId, String registrationTokenHash, long expires) {
    return ENCODER.encodeToString(signBytes(environmentId, registrationTokenHash, expires));
  }

  private static byte[] signBytes(UUID environmentId, String registrationTokenHash, long expires) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(registrationTokenHash.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(payload(environmentId, expires).getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException error) {
      throw new IllegalStateException("cannot sign install code", error);
    }
  }

  private static String payload(UUID environmentId, long expires) {
    return PURPOSE + "\n" + environmentId + "\n" + expires;
  }

  private static AiValidationException invalid() {
    return new AiValidationException("code", "install code is missing, expired or invalid");
  }
}
