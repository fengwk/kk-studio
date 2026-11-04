package fun.fengwk.kkstudio.share.ai.skill;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** 预览结果：规范化归档已作为暂存上传对象保留，{@code stagingUploadId} 用于发布或取消。 */
@Data
public class SkillPackagePreviewDTO {

  /** 暂存的上传 id：发布时消费，取消时释放。 */
  private String stagingUploadId;

  /** 目标包名。 */
  private String packageName;

  /** 用户填写的版本号。 */
  private String packageVersion;

  /** 可空包描述。 */
  private @Nullable String description;

  /** 从规范化归档读出的 Skill 清单（按 name 升序）。 */
  private List<SkillManifestEntryDTO> skills;

  /** 根目录下未包含 SKILL.md、因此未被打包的目录。 */
  private List<String> ignoredDirectories;

  /** 规范化归档的小写十六进制 SHA-256。 */
  private String archiveSha256;

  /** 规范化归档的字节大小。 */
  private long archiveSizeBytes;

  /** 解压后内容总字节数。 */
  private long contentSizeBytes;

  /** 归档中的文件条目数。 */
  private int fileCount;
}
