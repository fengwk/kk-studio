package fun.fengwk.kkstudio.web.upgrade;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 一次转换的不可变小结果：源 profile、是否真正提交、逐表目标行数与退休行数。
 *
 * <p>只暴露对账所需的数量事实；不含任何秘密、字段原文、连接信息或 schema 名称以外的元数据。
 *
 * @param sourceProfile 识别出的旧 profile（{@code "dev"} 或 {@code "main"}）
 * @param applied 是否真正提交（dry run 恒为 false）
 * @param rowCounts 每个共有表在目标中的实际行数（不可变、按表名排序）
 * @param retiredClusterNodeRows 因旧 lease 已到期而未导入并记为退休的 {@code cluster_node} 行数
 * @param rewrittenCredentials 按当前 format 2 重封装的旧凭据行数
 * @param installedStaticCredentials 由显式静态材料新写入的静态密钥行数
 */
record LegacyDatabaseUpgradeResult(
    String sourceProfile,
    boolean applied,
    Map<String, Long> rowCounts,
    long retiredClusterNodeRows,
    long rewrittenCredentials,
    long installedStaticCredentials) {

  LegacyDatabaseUpgradeResult {
    Objects.requireNonNull(sourceProfile, "sourceProfile");
    Objects.requireNonNull(rowCounts, "rowCounts");
    rowCounts = Collections.unmodifiableMap(new TreeMap<>(rowCounts));
  }
}
