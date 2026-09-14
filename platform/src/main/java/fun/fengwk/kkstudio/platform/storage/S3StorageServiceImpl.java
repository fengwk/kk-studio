package fun.fengwk.kkstudio.platform.storage;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import fun.fengwk.kkstudio.platform.storage.configuration.S3StorageProperties;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * S3 存储服务实现.
 *
 * @author fengwk
 */
public class S3StorageServiceImpl implements S3StorageService {

  private final S3StorageProperties properties;
  private final S3Client s3Client;

  public S3StorageServiceImpl(S3StorageProperties properties, S3Client s3Client) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
  }

  @Override
  public PutObjectResponse putObject(
      String key, InputStream content, long contentLength, String contentType) {
    Objects.requireNonNull(content, "content must not be null");
    Assert.isTrue(contentLength >= 0L, "contentLength must be greater than or equal to 0");
    String normalizedKey = S3ObjectKeyNormalizer.normalize(key);
    PutObjectRequest.Builder builder =
        PutObjectRequest.builder().bucket(properties.getBucket()).key(normalizedKey);
    if (StringUtils.hasText(contentType)) {
      builder.contentType(contentType);
    }
    return s3Client.putObject(builder.build(), RequestBody.fromInputStream(content, contentLength));
  }

  @Override
  public boolean exists(String key) {
    String normalizedKey = S3ObjectKeyNormalizer.normalize(key);
    try {
      s3Client.headObject(
          HeadObjectRequest.builder().bucket(properties.getBucket()).key(normalizedKey).build());
      return true;
    } catch (NoSuchKeyException e) {
      return false;
    } catch (AwsServiceException e) {
      if (e.statusCode() == 404) {
        return false;
      }
      throw e;
    }
  }

  @Override
  public S3ObjectMetadata headObject(String key) {
    String normalizedKey = S3ObjectKeyNormalizer.normalize(key);
    HeadObjectResponse response =
        s3Client.headObject(
            HeadObjectRequest.builder().bucket(properties.getBucket()).key(normalizedKey).build());
    Long contentLength = response.contentLength();
    Assert.isTrue(
        contentLength != null && contentLength >= 0L,
        "S3 HEAD response must report a non-negative content length for key: " + normalizedKey);
    return new S3ObjectMetadata(contentLength, response.contentType(), response.eTag());
  }

  @Override
  public S3ObjectMetadata headObjectWithChecksum(String key) {
    String normalizedKey = S3ObjectKeyNormalizer.normalize(key);
    HeadObjectResponse response =
        s3Client.headObject(
            HeadObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(normalizedKey)
                .checksumMode(ChecksumMode.ENABLED)
                .build());
    Long contentLength = response.contentLength();
    Assert.isTrue(
        contentLength != null && contentLength >= 0L,
        "S3 HEAD response must report a non-negative content length for key: " + normalizedKey);
    return new S3ObjectMetadata(
        contentLength, response.contentType(), response.eTag(), response.checksumSHA256());
  }

  @Override
  public void copyObject(String sourceKey, String targetKey) {
    String normalizedSource = S3ObjectKeyNormalizer.normalize(sourceKey);
    String normalizedTarget = S3ObjectKeyNormalizer.normalize(targetKey);
    s3Client.copyObject(
        CopyObjectRequest.builder()
            .sourceBucket(properties.getBucket())
            .sourceKey(normalizedSource)
            .destinationBucket(properties.getBucket())
            .destinationKey(normalizedTarget)
            .build());
  }

  @Override
  public S3ObjectStream readObject(String key) {
    String normalizedKey = S3ObjectKeyNormalizer.normalize(key);
    ResponseInputStream<GetObjectResponse> response =
        s3Client.getObject(
            GetObjectRequest.builder().bucket(properties.getBucket()).key(normalizedKey).build());
    Long contentLength = response.response().contentLength();
    if (contentLength == null || contentLength < 0L) {
      try {
        response.close();
      } catch (IOException closeError) {
        throw new UncheckedIOException(closeError);
      }
      throw new IllegalArgumentException(
          "S3 GET response must report a non-negative content length for key: " + normalizedKey);
    }
    return new S3ObjectStream(
        response,
        new S3ObjectMetadata(
            contentLength, response.response().contentType(), response.response().eTag()));
  }

  @Override
  public void deleteObject(String key) {
    String normalizedKey = S3ObjectKeyNormalizer.normalize(key);
    s3Client.deleteObject(
        DeleteObjectRequest.builder().bucket(properties.getBucket()).key(normalizedKey).build());
  }

  @Override
  public byte[] download(String key) {
    try (S3ObjectStream object = readObject(key)) {
      return object.inputStream().readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read S3 object: " + key, e);
    }
  }

  @Override
  public S3ObjectContent download(String key, long maxSizeBytes) {
    Assert.isTrue(maxSizeBytes >= 0L, "maxSizeBytes must be greater than or equal to 0");
    S3ObjectMetadata head = headObject(key);
    Assert.isTrue(head.contentLength() <= maxSizeBytes, "S3 object exceeds max input file size");
    try (S3ObjectStream object = readObject(key);
        ByteArrayOutputStream output =
            new ByteArrayOutputStream((int) Math.min(head.contentLength(), 8192L))) {
      byte[] buffer = new byte[8192];
      long total = 0L;
      int read;
      while ((read = object.inputStream().read(buffer)) != -1) {
        total += read;
        Assert.isTrue(total <= maxSizeBytes, "S3 object exceeds max input file size");
        output.write(buffer, 0, read);
      }
      return new S3ObjectContent(output.toByteArray(), object.metadata().contentType());
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read S3 object: " + key, e);
    }
  }
}
