package fun.fengwk.kkstudio.share.ai.skill;

import lombok.Data;

/** 技能包当前版本归档中的单个文件条目。 */
@Data
public class SkillPackageFileDTO {

  /** 归档内的相对路径，按路径升序返回。 */
  private String path;

  /** 文件解压后的字节大小。 */
  private long size;
}
