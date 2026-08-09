package fun.fengwk.kkstudio.core.studio.repo.impl;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasUploadMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasUploadDO;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.CanvasUpload;
import fun.fengwk.kkstudio.studio.canvas.CanvasUploadRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
public class PostgresqlCanvasUploadRepository implements CanvasUploadRepository {

  private final CanvasUploadMapper uploadMapper;

  public PostgresqlCanvasUploadRepository(CanvasUploadMapper uploadMapper) {
    this.uploadMapper = uploadMapper;
  }

  @Override
  public void add(CanvasUpload upload) {
    CanvasUploadDO data = new CanvasUploadDO();
    data.setId(upload.id());
    data.setCanvasId(upload.canvasId());
    data.setKind(upload.kind().name());
    data.setFilename(upload.filename());
    data.setDeclaredMediaType(upload.declaredMediaType());
    data.setDeclaredSize(upload.declaredSize());
    data.setExpiresAt(OffsetDateTime.ofInstant(upload.expiresAt(), ZoneOffset.UTC));
    data.setCreatedAt(OffsetDateTime.ofInstant(upload.createdAt(), ZoneOffset.UTC));
    uploadMapper.insert(data);
  }

  @Override
  public Optional<CanvasUpload> findById(long canvasId, long uploadId) {
    return Optional.ofNullable(uploadMapper.getById(canvasId, uploadId))
        .map(PostgresqlCanvasUploadRepository::toDomain);
  }

  @Override
  public Optional<CanvasUpload> findByIdForUpdate(long canvasId, long uploadId) {
    return Optional.ofNullable(uploadMapper.getByIdForUpdate(canvasId, uploadId))
        .map(PostgresqlCanvasUploadRepository::toDomain);
  }

  @Override
  public boolean delete(long canvasId, long uploadId) {
    return uploadMapper.delete(canvasId, uploadId) == 1;
  }

  private static CanvasUpload toDomain(CanvasUploadDO upload) {
    return new CanvasUpload(
        upload.getId(),
        upload.getCanvasId(),
        CanvasResourceKind.valueOf(upload.getKind()),
        upload.getFilename(),
        upload.getDeclaredMediaType(),
        upload.getDeclaredSize(),
        upload.getExpiresAt().toInstant(),
        upload.getCreatedAt().toInstant());
  }
}
