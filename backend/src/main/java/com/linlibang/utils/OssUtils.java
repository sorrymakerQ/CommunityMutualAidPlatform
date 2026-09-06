package com.linlibang.utils;

import cn.hutool.core.util.IdUtil;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.PutObjectRequest;
import com.linlibang.config.OssConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 阿里云 OSS 工具类
 * 提供文件上传、删除等操作
 */
@Slf4j
@Component
public class OssUtils {

    @Resource
    private OssConfig ossConfig;

    private OSS ossClient;

    /**
     * 初始化 OSS 客户端
     * 密钥为空时跳过初始化（本地未配置 OSS 也能正常启动，仅上传不可用）
     */
    @PostConstruct
    public void init() {
        String ak = ossConfig.getAccessKeyId();
        String sk = ossConfig.getAccessKeySecret();
        if (ak == null || ak.trim().isEmpty() || sk == null || sk.trim().isEmpty()) {
            log.warn("OSS AccessKey 未配置，跳过 OSS 客户端初始化（上传功能不可用）。本地开发可启用 local profile 或设置环境变量。");
            return;
        }
        ossClient = new OSSClientBuilder().build(ossConfig.getEndpoint(), ak, sk);
    }

    /**
     * 销毁 OSS 客户端
     */
    @PreDestroy
    public void destroy() {
        if (ossClient != null) {
            ossClient.shutdown();
        }
    }

    /**
     * 上传文件到 OSS
     *
     * @param file   MultipartFile 文件对象
     * @param folder 存储目录，如 "avatar"、"help"
     * @return 文件的完整访问 URL
     */
    public String uploadFile(MultipartFile file, String folder) throws IOException {
        if (ossClient == null) {
            throw new IOException("OSS 未配置 AccessKey（本地开发请启用 local profile 或设置环境变量）");
        }
        // 1. 生成唯一文件名（防止重名覆盖）
        // 扩展名按 Content-Type 推导，丢弃用户原始扩展名，确保落 OSS 的扩展名恒为真实图片扩展名
        String extension = extensionFromContentType(file.getContentType());
        // 按日期分目录 + UUID 文件名，例如：avatar/2024/07/02/a1b2c3d4.jpg
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String fileName = folder + "/" + datePath + "/" + IdUtil.simpleUUID() + extension;

        // 2. 上传到 OSS（try-with-resources 保证流一定关闭，防止文件描述符泄漏）
        try (InputStream inputStream = file.getInputStream()) {
            PutObjectRequest putObjectRequest = new PutObjectRequest(
                    ossConfig.getBucketName(),
                    fileName,
                    inputStream
            );
            ossClient.putObject(putObjectRequest);
        }

        // 3. 返回完整的访问 URL
        return ossConfig.getBaseUrl() + "/" + fileName;
    }

    /**
     * 按 Content-Type 推导图片扩展名；未知/空类型抛 IOException 拒绝上传
     */
    private String extensionFromContentType(String contentType) throws IOException {
        if (contentType == null) {
            throw new IOException("文件类型为空");
        }
        switch (contentType.toLowerCase()) {
            case "image/jpeg": return ".jpg";
            case "image/png":  return ".png";
            case "image/gif":  return ".gif";
            case "image/webp": return ".webp";
            case "image/bmp":  return ".bmp";
            default: throw new IOException("不支持的文件类型: " + contentType);
        }
    }

    /**
     * 上传头像（固定存储到 avatar 目录）
     *
     * @param file 头像文件
     * @return 头像 URL
     */
    public String uploadAvatar(MultipartFile file) throws IOException {
        return uploadFile(file, "avatar");
    }

    /**
     * 上传求助图片（固定存储到 help 目录）
     *
     * @param file 图片文件
     * @return 图片 URL
     */
    public String uploadHelpImage(MultipartFile file) throws IOException {
        return uploadFile(file, "help");
    }

    /**
     * 检查文件大小是否超限
     *
     * @param file 上传的文件
     * @return true-超限，false-未超限
     */
    public boolean isFileSizeExceeded(MultipartFile file) {
        long maxSizeBytes = ossConfig.getMaxSize() * 1024L * 1024L;
        return file.getSize() > maxSizeBytes;
    }
}
