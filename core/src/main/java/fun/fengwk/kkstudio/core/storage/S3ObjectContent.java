package fun.fengwk.kkstudio.core.storage;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 从固定 S3 bucket 读取的对象内容与元数据。
 *
 * @author fengwk
 */
@AllArgsConstructor
@Getter
public class S3ObjectContent {

  private final byte[] bytes;
  private final String contentType;
}
