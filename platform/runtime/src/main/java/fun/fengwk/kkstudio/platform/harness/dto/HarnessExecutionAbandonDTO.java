package fun.fengwk.kkstudio.platform.harness.dto;

import lombok.Data;

/** 显式放弃一个具体执行代次退出确认的请求。 */
@Data
public class HarnessExecutionAbandonDTO {

  /** 必填 exact version CAS 游标：strict non-negative integer，读取自最新 Thread 快照。 */
  private Long expectedVersion;
}
