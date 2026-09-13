package fun.fengwk.kkstudio.share.cloudfs;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Cloud File 路径快照聚合公开表示。
 *
 * <p>包含核心元数据 {@code node}，以及根据节点类型互斥提供的三种快照体之一：
 *
 * <ul>
 *   <li>{@code DIRECTORY}：直接子节点列表 {@code children}；
 *   <li>{@code TEXT}：文本行窗口 {@code text}；
 *   <li>{@code BLOB}：Blob 元数据 {@code blob}。
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudFileSnapshotDTO {

  /** 目标路径的基础节点元数据。 */
  private CloudNodeDTO node;

  /** DIRECTORY 节点的直接子节点列表（按名称升序）；非目录时为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private List<CloudNodeDTO> children;

  /** TEXT 节点的文本行窗口；非文本时为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private CloudTextWindowDTO text;

  /** BLOB 节点的 Blob 元数据；非 BLOB 时为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private CloudBlobMetadataDTO blob;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown field: " + name);
  }
}
