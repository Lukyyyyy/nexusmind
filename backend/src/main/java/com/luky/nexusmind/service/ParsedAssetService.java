package com.luky.nexusmind.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.minio.*;
import io.minio.messages.Item;
import io.minio.messages.DeleteObject;
import io.minio.messages.DeleteError;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/** 图片只通过文档鉴权接口读取；不将对象存储凭据或签名地址写入切片。 */
@Service
public class ParsedAssetService {
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final Pattern NAME = Pattern.compile("[a-f0-9]{64}\\.(png|jpg|webp|gif)");
    private final MinioClient minio;
    @Value("${minio.bucketName:uploads}")
    private String bucket;

    public ParsedAssetService(MinioClient minio) { this.minio = minio; }

    public String persist(String fileMd5, String markdown, JsonNode response) throws IOException {
        validateMd5(fileMd5);
        try { return persistImages(fileMd5, markdown, response); }
        catch (Exception e) { throw new IOException("保存解析图片失败", e); }
    }

    private String persistImages(String md5, String markdown, JsonNode node) throws Exception {
        if (node == null) return markdown;
        if (node.isObject() && node.path("images").isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.path("images").fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String name = entry.getKey();
                if (!name.matches("[A-Za-z0-9_.-]+") || !entry.getValue().isTextual()) continue;
                String source = "images/" + name;
                if (!markdown.contains(source)) continue;
                String data = entry.getValue().asText();
                int comma = data.indexOf(',');
                if (comma < 0 || comma > 64 || data.length() > MAX_IMAGE_BYTES * 4L / 3 + 128) {
                    throw new IOException("解析图片大小或格式无效");
                }
                String mime = data.substring(0, comma);
                String extension = switch (mime) {
                    case "data:image/png;base64" -> "png";
                    case "data:image/jpeg;base64" -> "jpg";
                    case "data:image/webp;base64" -> "webp";
                    case "data:image/gif;base64" -> "gif";
                    default -> throw new IOException("不支持的解析图片格式");
                };
                byte[] bytes = Base64.getDecoder().decode(data.substring(comma + 1));
                if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) throw new IOException("解析图片大小无效");
                String asset = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + "." + extension;
                FileTaskControl.write(() -> {
                    try {
                        minio.putObject(PutObjectArgs.builder().bucket(bucket).object(prefix(md5) + asset)
                                .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                                .contentType(mime.substring(5, mime.indexOf(';'))).build());
                    } catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
                });
                String url = "/api/v1/documents/" + md5 + "/assets/" + asset;
                // 只改写完整资源引用，避免短文件名误替换长文件名。
                markdown = markdown.replace("(" + source + ")", "(" + url + ")")
                        .replace("(./" + source + ")", "(" + url + ")")
                        .replace("\"" + source + "\"", "\"" + url + "\"")
                        .replace("'" + source + "'", "'" + url + "'");
            }
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) markdown = persistImages(md5, markdown, child);
        }
        return markdown;
    }

    public InputStream open(String md5, String name) throws Exception {
        validateMd5(md5);
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("无效图片名称");
        return minio.getObject(GetObjectArgs.builder().bucket(bucket).object(prefix(md5) + name).build());
    }

    public void delete(String md5) throws Exception {
        validateMd5(md5);
        List<DeleteObject> batch = new ArrayList<>(1000);
        for (Result<Item> item : minio.listObjects(ListObjectsArgs.builder().bucket(bucket).prefix(prefix(md5)).recursive(true).build())) {
            batch.add(new DeleteObject(item.get().objectName()));
            if (batch.size() == 1000) {
                deleteBatch(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) deleteBatch(batch);
    }

    private void deleteBatch(List<DeleteObject> batch) throws Exception {
        // MinIO 的批量删除是惰性执行，必须遍历结果，并检查 HTTP 成功响应中的逐对象错误。
        for (Result<DeleteError> result : minio.removeObjects(RemoveObjectsArgs.builder()
                .bucket(bucket).objects(batch).build())) {
            DeleteError error = result.get();
            throw new IOException("清理解析图片失败: " + error.code());
        }
    }

    private static String prefix(String md5) { return "parsed-assets/" + md5 + "/"; }
    private static void validateMd5(String md5) {
        if (md5 == null || !md5.matches("[a-fA-F0-9]{32}")) throw new IllegalArgumentException("无效文档标识");
    }
}
