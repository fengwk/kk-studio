package fun.fengwk.kkstudio.share.api;

import lombok.Data;

import java.util.List;

/** Opaque-cursor page shared by global and scoped list APIs. */
@Data
public class CursorPageDTO<T> {

  private List<T> items;
  private String nextCursor;
}
