package com.ozo.contentforge.service;

import com.ozo.contentforge.model.entity.GeneratedAssetEntity;
import com.ozo.contentforge.model.enums.AssetType;
import com.ozo.contentforge.repository.GeneratedAssetRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    @Value("${aws.s3.bucket:contentforge-media}")
    private String s3Bucket;

    @Value("${aws.s3.region:ap-south-1}")
    private String s3Region;

    @Value("${aws.s3.access-key-id:YOUR_AWS_ACCESS_KEY_ID_HERE}")
    private String accessKeyId;

    @Value("${aws.s3.secret-access-key:YOUR_AWS_SECRET_ACCESS_KEY_HERE}")
    private String secretAccessKey;

    @Value("${aws.s3.endpoint:}")
    private String endpointOverride;

    @Value("${aws.s3.public-base-url:}")
    private String publicBaseUrl;

    private final GeneratedAssetRepository assetRepository;
    private S3Client s3Client;
    private S3Presigner s3Presigner;

    public StorageService(GeneratedAssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    @PostConstruct
    public void init() {
        log.info("Configuring AWS S3 Media Storage: Bucket '{}', Region '{}'", s3Bucket, s3Region);
        try {
            S3ClientBuilder builder = S3Client.builder()
                    .region(Region.of(s3Region));

            if (endpointOverride != null && !endpointOverride.isBlank()) {
                builder.endpointOverride(URI.create(endpointOverride));
            }

            if (isConfigured(accessKeyId) && isConfigured(secretAccessKey)) {
                AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);
                builder.credentialsProvider(StaticCredentialsProvider.create(credentials));
                this.s3Presigner = S3Presigner.builder()
                        .region(Region.of(s3Region))
                        .credentialsProvider(StaticCredentialsProvider.create(credentials))
                        .build();
                log.info("Initialized AWS S3 Client & S3Presigner with provided credentials.");
            } else {
                builder.credentialsProvider(DefaultCredentialsProvider.create());
                this.s3Presigner = S3Presigner.builder()
                        .region(Region.of(s3Region))
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .build();
                log.info("Initialized AWS S3 Client & S3Presigner with DefaultCredentialsProvider.");
            }

            this.s3Client = builder.build();
            log.info("AWS S3 Client successfully configured. No local storage is used.");
        } catch (Exception e) {
            log.warn("S3 client default credential initialization: {}. Falling back to resilient S3 client.", e.getMessage());
            this.s3Client = S3Client.builder()
                    .region(Region.of(s3Region))
                    .credentialsProvider(AnonymousCredentialsProvider.create())
                    .build();
        }
    }

    private boolean isConfigured(String val) {
        return val != null && !val.isBlank() && !val.startsWith("YOUR_");
    }

    /**
     * Build the canonical media URL to store in the database.
     * Always returns a clean, permanent, non-expiring URL (well under 100 characters).
     * NEVER stores presigned URLs in the database.
     */
    public String buildS3Url(String s3Key) {
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            if (publicBaseUrl.contains("key=")) {
                return publicBaseUrl.endsWith("key=") ? publicBaseUrl + s3Key : publicBaseUrl + s3Key;
            }
            return publicBaseUrl.endsWith("/") ? publicBaseUrl + s3Key : publicBaseUrl + "/" + s3Key;
        }

        return "/api/v1/media/s3?key=" + s3Key;
    }

    /**
     * Generate an authentic, signed AWS S3 HTTPS URL on demand.
     * Called at the exact moment an external service (e.g. HeyGen, Meta Instagram API)
     * or the client's direct 302 streaming redirect needs direct, authenticated S3 access.
     * NEVER stored in the database.
     */
    public String getPresignedUrl(String s3KeyOrUrl, Duration duration) {
        if (s3KeyOrUrl == null || s3KeyOrUrl.isBlank()) {
            return s3KeyOrUrl;
        }

        // External public URLs (Unsplash, HeyGen CDN, Runway CDN, etc.) are already directly accessible
        if (s3KeyOrUrl.startsWith("http://") || s3KeyOrUrl.startsWith("https://")) {
            boolean isOurS3 = s3KeyOrUrl.contains(".amazonaws.com") || (s3Bucket != null && s3KeyOrUrl.contains(s3Bucket));
            boolean isProxy = s3KeyOrUrl.contains("/api/v1/media/s3") || s3KeyOrUrl.contains("key=");
            if (!isOurS3 && !isProxy) {
                return s3KeyOrUrl;
            }
        }

        String s3Key = extractS3Key(s3KeyOrUrl);

        if (s3Presigner != null && isConfigured(accessKeyId) && isConfigured(secretAccessKey)) {
            try {
                GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                        .bucket(s3Bucket)
                        .key(s3Key)
                        .build();

                GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                        .signatureDuration(duration != null ? duration : Duration.ofHours(2))
                        .getObjectRequest(getObjectRequest)
                        .build();

                PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignRequest);
                return presigned.url().toString();
            } catch (Exception e) {
                log.warn("On-demand presigned S3 URL generation failed for key '{}': {}", s3Key, e.getMessage());
            }
        }

        return s3KeyOrUrl;
    }

    /**
     * Extract clean S3 key from any input format (raw key, s3:// URI, proxy URL, or legacy AWS URL).
     */
    public String extractS3Key(String input) {
        if (input == null || input.isBlank()) return input;

        try {
            input = java.net.URLDecoder.decode(input, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {}

        // If it's an external public URL not belonging to our S3 or proxy, do not treat as S3 key
        if (input.startsWith("http://") || input.startsWith("https://")) {
            boolean isOurS3 = input.contains(".amazonaws.com") || (s3Bucket != null && input.contains(s3Bucket));
            boolean isProxy = input.contains("/api/v1/media/s3") || input.contains("key=");
            if (!isOurS3 && !isProxy) {
                return input;
            }
        }

        // If it's a proxy URL like http://localhost:8080/api/v1/media/s3?key=media/audio/abc.mp3
        if (input.contains("key=")) {
            int idx = input.indexOf("key=");
            String key = input.substring(idx + 4);
            int ampIdx = key.indexOf("&");
            key = ampIdx != -1 ? key.substring(0, ampIdx) : key;
            return cleanLeadingSlash(key);
        }

        // If it's an s3:// URL like s3://bucket-name/media/audio/abc.mp3
        if (input.startsWith("s3://")) {
            String pathWithoutScheme = input.substring(5);
            int slashIdx = pathWithoutScheme.indexOf("/");
            String key = slashIdx != -1 ? pathWithoutScheme.substring(slashIdx + 1) : pathWithoutScheme;
            return cleanLeadingSlash(key);
        }

        // If it's an S3 HTTPS URL like https://bucket.s3.region.amazonaws.com/media/...
        if (input.contains(".amazonaws.com/")) {
            int slashIdx = input.indexOf(".amazonaws.com/");
            String keyWithQuery = input.substring(slashIdx + ".amazonaws.com/".length());
            int qIdx = keyWithQuery.indexOf("?");
            String key = qIdx != -1 ? keyWithQuery.substring(0, qIdx) : keyWithQuery;
            return cleanLeadingSlash(key);
        }

        return cleanLeadingSlash(input);
    }

    private String cleanLeadingSlash(String key) {
        if (key == null) return null;
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        return key;
    }

    /**
     * Upload an object directly to AWS S3, with ephemeral CLI fallback
     */
    public void putS3Object(String s3Key, byte[] data, String contentType) {
        try {
            PutObjectRequest putReq = PutObjectRequest.builder()
                    .bucket(s3Bucket)
                    .key(s3Key)
                    .contentType(contentType)
                    .build();
            s3Client.putObject(putReq, RequestBody.fromBytes(data));
            log.info("Uploaded media to AWS S3: s3://{}/{}", s3Bucket, s3Key);
        } catch (Exception e) {
            log.warn("Direct upload to AWS S3 encountered error (s3://{}/{}): {}. Executing AWS CLI sync...", s3Bucket, s3Key, e.getMessage());
            tryUploadViaCli(s3Key, contentType, data);
        }
    }

    private void tryUploadViaCli(String s3Key, String contentType, byte[] data) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("s3up_", ".tmp");
            Files.write(tempFile, data);
            ProcessBuilder pb = new ProcessBuilder(
                    "aws", "s3", "cp", tempFile.toAbsolutePath().toString(),
                    "s3://" + s3Bucket + "/" + s3Key,
                    "--content-type", contentType
            );
            pb.environment().put("AWS_ACCESS_KEY_ID", accessKeyId);
            pb.environment().put("AWS_SECRET_ACCESS_KEY", secretAccessKey);
            pb.environment().put("AWS_DEFAULT_REGION", s3Region);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean done = proc.waitFor(90, TimeUnit.SECONDS);
            if (done && proc.exitValue() == 0) {
                log.info("Successfully uploaded object to AWS S3 via AWS CLI fallback: s3://{}/{}", s3Bucket, s3Key);
            } else {
                log.warn("AWS CLI upload exited with code {}", done ? proc.exitValue() : -1);
            }
        } catch (Exception e) {
            log.debug("AWS CLI upload skipped or unavailable: {}", e.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {}
            }
        }
    }

    private byte[] tryDownloadViaCli(String s3Key) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("s3down_", ".tmp");
            ProcessBuilder pb = new ProcessBuilder(
                    "aws", "s3", "cp",
                    "s3://" + s3Bucket + "/" + s3Key,
                    tempFile.toAbsolutePath().toString()
            );
            pb.environment().put("AWS_ACCESS_KEY_ID", accessKeyId);
            pb.environment().put("AWS_SECRET_ACCESS_KEY", secretAccessKey);
            pb.environment().put("AWS_DEFAULT_REGION", s3Region);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean done = proc.waitFor(60, TimeUnit.SECONDS);
            if (done && proc.exitValue() == 0 && Files.exists(tempFile) && Files.size(tempFile) > 0) {
                return Files.readAllBytes(tempFile);
            }
            return null;
        } catch (Exception e) {
            log.debug("AWS CLI download skipped: {}", e.getMessage());
            return null;
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Retrieve media bytes directly from AWS S3
     */
    public byte[] getS3Object(String s3Key) throws IOException {
        try {
            GetObjectRequest getReq = GetObjectRequest.builder()
                    .bucket(s3Bucket)
                    .key(s3Key)
                    .build();
            ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(getReq);
            return objectBytes.asByteArray();
        } catch (Exception e) {
            byte[] cliBytes = tryDownloadViaCli(s3Key);
            if (cliBytes != null && cliBytes.length > 0) {
                return cliBytes;
            }
            log.error("Failed to retrieve media from AWS S3 (s3://{}/{}): {}", s3Bucket, s3Key, e.getMessage());
            throw new IOException("Failed to retrieve AWS S3 object: " + e.getMessage(), e);
        }
    }

    /**
     * Store an uploaded multipart file directly to AWS S3
     */
    public GeneratedAssetEntity storeUploadedFile(MultipartFile file, AssetType assetType, Long postId, Long sceneId) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        } else {
            extension = switch (assetType) {
                case IMAGE, LOGO, THUMBNAIL -> ".png";
                case AUDIO -> ".mp3";
                case VIDEO -> ".mp4";
                case SUBTITLE -> ".vtt";
                case DOCUMENT -> ".pdf";
            };
        }

        String folder = switch (assetType) {
            case LOGO -> "brand-logos";
            case IMAGE, THUMBNAIL -> "photos";
            case AUDIO -> "voices";
            case VIDEO -> "renders";
            case SUBTITLE -> "subtitles";
            case DOCUMENT -> "documents";
        };

        String s3Key = String.format("media/%s/%s%s", folder, UUID.randomUUID(), extension);
        byte[] bytes = file.getBytes();
        String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";

        putS3Object(s3Key, bytes, contentType);

        String fileUrl = buildS3Url(s3Key);

        GeneratedAssetEntity asset = GeneratedAssetEntity.builder()
                .postId(postId)
                .sceneId(sceneId)
                .assetType(assetType)
                .storageUrl(fileUrl)
                .storagePath("s3://" + s3Bucket + "/" + s3Key)
                .s3Key(s3Key)
                .mimeType(contentType)
                .fileSizeBytes(file.getSize())
                .createdAt(LocalDateTime.now())
                .metadataJson(String.format("{\"originalName\": \"%s\", \"s3Bucket\": \"%s\", \"s3Key\": \"%s\"}",
                        originalFilename, s3Bucket, s3Key))
                .build();

        return assetRepository.save(asset);
    }

    /**
     * Save generated synthetic/rendered content directly to AWS S3
     */
    public GeneratedAssetEntity saveGeneratedMedia(byte[] data, String extension, String mimeType, AssetType assetType, Long postId, Long sceneId, Double duration, String metadataJson) {
        String folder = switch (assetType) {
            case LOGO -> "brand-logos";
            case IMAGE, THUMBNAIL -> "graphics";
            case AUDIO -> "audio";
            case VIDEO -> "videos";
            case SUBTITLE -> "subtitles";
            case DOCUMENT -> "documents";
        };

        String s3Key = String.format("media/%s/%s%s", folder, UUID.randomUUID(), extension);
        putS3Object(s3Key, data, mimeType);

        String fileUrl = buildS3Url(s3Key);

        GeneratedAssetEntity asset = GeneratedAssetEntity.builder()
                .postId(postId)
                .sceneId(sceneId)
                .assetType(assetType)
                .storageUrl(fileUrl)
                .storagePath("s3://" + s3Bucket + "/" + s3Key)
                .s3Key(s3Key)
                .mimeType(mimeType)
                .fileSizeBytes((long) data.length)
                .durationSeconds(duration)
                .metadataJson(metadataJson)
                .createdAt(LocalDateTime.now())
                .build();

        return assetRepository.save(asset);
    }

    public String getS3Bucket() {
        return s3Bucket;
    }

    public String getS3Region() {
        return s3Region;
    }
}
