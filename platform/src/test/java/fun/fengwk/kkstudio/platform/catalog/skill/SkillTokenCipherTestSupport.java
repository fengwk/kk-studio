package fun.fengwk.kkstudio.platform.catalog.skill;

import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialKeyLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** 单测用真实 {@link SkillTokenCipher}：以一次性 owner-only 32 字节主密钥驱动生产加解密路径，不做任何加密替身。 */
public final class SkillTokenCipherTestSupport {

  private SkillTokenCipherTestSupport() {}

  public static SkillTokenCipher newCipher() {
    try {
      Path keyFile = Files.createTempFile("kkstudio-skill-token", ".key");
      Files.write(keyFile, "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
      Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
      return new SkillTokenCipher(
          new PluginCredentialCodec(),
          new PluginCredentialKeyLoader(keyFile.toAbsolutePath().toString()));
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
