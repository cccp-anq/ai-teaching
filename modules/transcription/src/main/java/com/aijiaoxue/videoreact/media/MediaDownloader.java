package com.aijiaoxue.videoreact.media;

import com.aijiaoxue.videoreact.media.config.MediaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 受控媒体下载器。
 *
 * <p>按契约要求：<b>模块自己下载原媒体并核验 SHA-256</b>
 * （不把下载交给 ASR 提供方，否则无法核验内容一致性）。
 *
 * <p><b>安全约束</b>（契约 §3「可信下载源按部署白名单配置，拒绝任意内网/本机/重定向至未授权地址，
 * 限制下载量；不把 sourceUrl 输出到日志或结果」）：
 * <ul>
 *   <li>只允许 HTTPS（开发回环 HTTP 需显式开关）；</li>
 *   <li>解析目标主机并拒绝环回/私网/链路本地/组播地址；</li>
 *   <li>可选按主机白名单限定；</li>
 *   <li>只允许有限次重定向，且<b>每一次跳转都重新做地址校验</b>
 *       （否则一个公网 URL 可以 302 到内网）；</li>
 *   <li>边下边算 SHA-256，并同时强制最大字节数，避免超大文件打满磁盘；</li>
 *   <li>日志里只出现 host，不出现完整 sourceUrl 及其查询串。</li>
 * </ul>
 */
public class MediaDownloader {

    private static final Logger log = LoggerFactory.getLogger(MediaDownloader.class);

    /** 一次跳转都不跟时也要允许原始请求，所以上限含初始请求。 */
    private static final int MAX_REDIRECTS = 3;

    private final MediaProperties properties;

    public MediaDownloader(MediaProperties properties) {
        this.properties = properties;
    }

    /**
     * 下载结果。
     *
     * @param path      本地文件
     * @param sha256    小写 64 位十六进制
     * @param sizeBytes 字节数
     */
    public record DownloadedMedia(Path path, String sha256, long sizeBytes) {
    }

    /** 下载被拒绝（地址不合规、超限、过期等），属于<b>可解释的业务失败</b>。 */
    public static class MediaDownloadException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String code;
        private final boolean retryable;

        public MediaDownloadException(String code, String message, boolean retryable) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    /**
     * 下载并计算 SHA-256。
     *
     * @param sourceUrl 主项目给的短期受控下载地址
     * @param targetDir 落盘目录
     * @return 下载结果
     * @throws MediaDownloadException 地址不合规、过期、超限或下载失败
     */
    public DownloadedMedia download(String sourceUrl, Path targetDir) {
        URI uri = parseAndValidate(sourceUrl);
        Path target = targetDir.resolve("media-" + System.nanoTime() + extensionOf(uri));

        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            throw new MediaDownloadException("DECODE_FAILED", "无法创建媒体临时目录: " + e.getMessage(), true);
        }

        URI current = uri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            PinnedResponse response = send(current);
            int status = response.status();

            if (isRedirect(status)) {
                String location = response.location();
                if (location == null) {
                    closeQuietly(response);
                    throw new MediaDownloadException(
                            "DECODE_FAILED", "重定向响应缺少 Location", false);
                }
                closeQuietly(response);
                // 关键：每一跳都重新校验，防止 302 到内网
                current = parseAndValidate(current.resolve(location).toString());
                continue;
            }

            if (status == 403 || status == 401) {
                closeQuietly(response);
                // 签名过期/被拒：契约要求可重试，由主项目续期后用同键重提
                throw new MediaDownloadException("MEDIA_URL_EXPIRED",
                        "媒体下载被拒绝（HTTP " + status + "），下载地址可能已过期", true);
            }
            if (status == 404 || status == 410) {
                closeQuietly(response);
                throw new MediaDownloadException("MEDIA_URL_EXPIRED",
                        "媒体不存在或已过期（HTTP " + status + "）", true);
            }
            if (status < 200 || status >= 300) {
                closeQuietly(response);
                throw new MediaDownloadException("PROCESSING_FAILED",
                        "媒体下载失败（HTTP " + status + "）", true);
            }

            return writeAndHash(response, target);
        }
        throw new MediaDownloadException("PROCESSING_FAILED",
                "媒体下载重定向次数超过 " + MAX_REDIRECTS + " 次", false);
    }

    private PinnedResponse send(URI uri) {
        InetAddress[] pinned = resolveAndValidate(uri.getHost());
        DnsResolver resolver = new DnsResolver() {
            @Override public InetAddress[] resolve(String host) { return pinned.clone(); }
            @Override public String resolveCanonicalHostname(String host) { return host; }
        };
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver).build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(Math.max(5, properties.connectTimeoutSeconds())))
                .setResponseTimeout(Timeout.ofSeconds(Math.max(30, properties.downloadTimeoutSeconds())))
                .build();
        CloseableHttpClient client = HttpClients.custom().setConnectionManager(manager)
                .setDefaultRequestConfig(requestConfig).disableRedirectHandling().build();
        HttpGet request = new HttpGet(uri);
        request.setHeader("Accept", "*/*");
        try {
            CloseableHttpResponse response = client.execute(request);
            InputStream body = response.getEntity() == null ? InputStream.nullInputStream() : response.getEntity().getContent();
            long length = response.getEntity() == null ? 0 : response.getEntity().getContentLength();
            var location = response.getFirstHeader("Location");
            return new PinnedResponse(uri, response.getCode(), body, length,
                    location == null ? null : location.getValue(), response, client);
        } catch (IOException e) {
            try { client.close(); } catch (IOException ignored) { }
            throw new MediaDownloadException("PROCESSING_FAILED",
                    "媒体下载失败: " + e.getClass().getSimpleName(), true);
        }
    }

    private record PinnedResponse(URI uri, int status, InputStream body, long contentLength,
                                  String location, CloseableHttpResponse response,
                                  CloseableHttpClient client) { }

    /**
     * 边下边写边算 hash，并在超过上限时立即中止。
     *
     * <p>先看 Content-Length 可以省掉一次必然失败的下载；但有些响应没有它，
     * 所以流式写入时仍要强制上限。
     */
    private DownloadedMedia writeAndHash(PinnedResponse response, Path target) {
        long limit = properties.maxDownloadBytes();
        long declared = response.contentLength();
        if (declared > limit) {
            closeQuietly(response);
            throw new MediaDownloadException("PAYLOAD_TOO_LARGE",
                    "媒体大小 " + declared + " 字节超过本次部署上限 " + limit + " 字节", false);
        }

        MessageDigest digest = newSha256();
        long total = 0;
        try (InputStream in = response.body()) {
            byte[] buffer = new byte[64 * 1024];
            try (var out = Files.newOutputStream(target)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > limit) {
                        throw new MediaDownloadException("PAYLOAD_TOO_LARGE",
                                "媒体超过本次部署上限 " + limit + " 字节", false);
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
        } catch (MediaDownloadException e) {
            deleteQuietly(target);
            throw e;
        } catch (IOException e) {
            deleteQuietly(target);
            throw new MediaDownloadException("PROCESSING_FAILED",
                    "写入媒体文件失败: " + e.getClass().getSimpleName(), true);
        } finally {
            closeQuietly(response);
        }

        if (total == 0) {
            deleteQuietly(target);
            throw new MediaDownloadException("DECODE_FAILED", "媒体内容为空（0 字节）", false);
        }

        String sha256 = HexFormat.of().formatHex(digest.digest());
        log.info("媒体下载完成: host={}, size={} 字节, sha256={}…{}",
                safeHost(response.uri()), total, sha256.substring(0, 8), sha256.substring(56));
        return new DownloadedMedia(target, sha256, total);
    }

    /**
     * 解析并校验下载地址。
     *
     * <p>校验的是<b>解析后的 IP</b>而不是主机名字符串：
     * 用 DNS 指向 127.0.0.1 的域名同样必须拒绝。
     *
     * @param sourceUrl 原始地址
     * @return 解析后的 URI
     * @throws MediaDownloadException 不合规时抛出
     */
    URI parseAndValidate(String sourceUrl) {
        URI uri;
        try {
            uri = new URI(sourceUrl.trim());
        } catch (Exception e) {
            throw new MediaDownloadException("VALIDATION_FAILED", "媒体地址不是合法 URL", false);
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        boolean loopbackHttpAllowed = properties.allowLoopbackHttp();
        if ("https".equals(scheme)) {
            // 允许
        } else if ("http".equals(scheme) && loopbackHttpAllowed) {
            // 开发回环：显式开关才允许，且下面仍会做地址校验
        } else {
            throw new MediaDownloadException("VALIDATION_FAILED",
                    "媒体地址必须是 https（开发回环需显式开启允许 http）", false);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new MediaDownloadException("VALIDATION_FAILED", "媒体地址缺少主机名", false);
        }

        if (!properties.hostAllowList().isEmpty()
                && properties.hostAllowList().stream().noneMatch(host::equalsIgnoreCase)) {
            throw new MediaDownloadException("FORBIDDEN",
                    "媒体主机不在本部署白名单内: " + host, false);
        }

        resolveAndValidate(host);
        return uri;
    }

    private InetAddress[] resolveAndValidate(String host) {
        InetAddress[] addresses;
        try { addresses = InetAddress.getAllByName(host); }
        catch (Exception e) { throw new MediaDownloadException("PROCESSING_FAILED", "无法解析媒体主机: " + host, true); }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) throw new MediaDownloadException("FORBIDDEN", "媒体地址解析到非公网地址，已拒绝: " + host, false);
        }
        return addresses;
    }

    /**
     * 拒绝环回 / 私网 / 链路本地 / 通配 / 组播地址。
     *
     * @param address 已解析地址
     * @return true 表示必须拒绝
     */
    private boolean isBlockedAddress(InetAddress address) {
        // 开发回环开关打开时，明确放行环回（供本地联调），其余仍拒绝
        if (address.isLoopbackAddress() && properties.allowLoopbackHttp()) {
            return false;
        }
        return address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isUniqueLocalIpv6(address)
                // 100.64.0.0/10 运营商级 NAT：isSiteLocalAddress 不覆盖
                || isCarrierGradeNat(address);
    }

    private static boolean isCarrierGradeNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64;
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    private static String extensionOf(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return ".bin";
        }
        String ext = path.substring(dot);
        return ext.length() <= 6 && ext.matches("\\.[A-Za-z0-9]+") ? ext : ".bin";
    }

    /** 日志只暴露 host，不暴露完整 sourceUrl（含签名查询串）。 */
    private static String safeHost(URI uri) {
        return uri.getHost() == null ? "(unknown)" : uri.getHost();
    }

    private static void closeQuietly(PinnedResponse response) {
        try { response.body().close(); } catch (IOException ignored) { }
        try { response.response().close(); } catch (IOException ignored) { }
        try { response.client().close(); } catch (IOException ignored) { }
    }

    public static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件清理失败不改变业务结果
        }
    }

    /** 供 runner 移动/清理临时文件使用。 */
    public static void moveQuietly(Path from, Path to) {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // 忽略
        }
    }
}
