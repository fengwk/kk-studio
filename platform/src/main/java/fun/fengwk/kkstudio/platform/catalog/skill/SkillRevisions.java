package fun.fengwk.kkstudio.platform.catalog.skill;

import fun.fengwk.kkstudio.harness.common.skill.SkillNames;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillRevision;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Skill catalog 的确定性 revision 计算。
 *
 * <p>{@code contentRevision} 是正文精确 UTF-8 字节的 SHA-256；{@code packageRevision} 是在 canonical package
 * name/version/description 与全部 Skill name/description/content 元组之上，按请求顺序、以长度前缀消歧框定后得到的确定性
 * SHA-256。两者都是纯函数，可跨节点复算并比对。
 */
public final class SkillRevisions {

  private static final String ALGORITHM = "SHA-256";

  private SkillRevisions() {}

  /** 计算正文的 content revision（小写 64 位十六进制 SHA-256）。 */
  public static String contentRevision(String content) {
    MessageDigest digest = newDigest();
    digest.update(SkillNames.canonicalContent(content).getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest.digest());
  }

  /**
   * 计算 package 的确定性聚合 revision。
   *
   * <p>每段文本都以十进制的 UTF-8 字节长度加 {@code ':'} 前缀消歧，因此任何字段的内容都不会与分隔符混淆，字段顺序与请求顺序 严格一致。
   */
  public static String packageRevision(SkillPackage packageModel, List<SkillRevision> revisions) {
    Objects.requireNonNull(packageModel, "packageModel");
    Objects.requireNonNull(revisions, "revisions");
    MessageDigest digest = newDigest();
    frame(digest, "skill-package");
    frame(digest, packageModel.getPackageName());
    frame(digest, packageModel.getPackageVersion());
    frame(digest, nullToEmpty(packageModel.getDescription()));
    for (SkillRevision revision : revisions) {
      Objects.requireNonNull(revision, "revisions[]");
      frame(digest, revision.getName());
      frame(digest, revision.getDescription());
      frame(digest, revision.getContent());
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void frame(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
    digest.update((byte) ':');
    digest.update(bytes);
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance(ALGORITHM);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is required by the JLS", error);
    }
  }
}
