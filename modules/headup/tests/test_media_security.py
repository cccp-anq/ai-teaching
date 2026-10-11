"""受控下载的安全边界（契约 §3：拒绝任意内网/本机/重定向到未授权地址）。"""

from __future__ import annotations

import unittest

from headup.config import Config
from headup.media import DownloadResult, MediaError, check_host_addresses, validate_url, verify_hash

from support import LocalMediaServer, find_tools, make_test_media, sha256_of, temp_root


def cfg(**overrides) -> Config:
    base = dict(allow_loopback_http=False, host_allow_list=())
    base.update(overrides)
    return Config(**base)


def expect_refused(test: unittest.TestCase, url: str, configuration: Config,
                   code: str = "UNSUPPORTED_MEDIA") -> None:
    with test.assertRaises(MediaError, msg=url) as ctx:
        validate_url(url, configuration)
    test.assertEqual(ctx.exception.task_error.code, code, url)


class UrlPolicyTest(unittest.TestCase):
    def test_https_public_host_passes_static_checks(self) -> None:
        # 不依赖 DNS：scheme/主机名/白名单是策略决定的部分
        parsed = validate_url("https://media.example.com/a.mp4?sig=1", cfg(), resolve=False)
        self.assertEqual(parsed.hostname, "media.example.com")

    def test_plain_http_is_refused_by_default(self) -> None:
        expect_refused(self, "http://media.example.com/a.mp4", cfg())

    def test_plain_http_allowed_only_with_explicit_switch(self) -> None:
        parsed = validate_url("http://127.0.0.1:8080/a.mp4", cfg(allow_loopback_http=True))
        self.assertEqual(parsed.hostname, "127.0.0.1")

    def test_literal_loopback_and_private_addresses_are_refused(self) -> None:
        for url in ("https://127.0.0.1/a.mp4", "https://localhost/a.mp4",
                    "https://10.1.2.3/a.mp4", "https://192.168.0.9/a.mp4",
                    "https://169.254.169.254/latest/meta-data",
                    "https://100.64.1.1/a.mp4", "https://0.0.0.0/a.mp4"):
            expect_refused(self, url, cfg())

    def test_public_literal_address_is_accepted(self) -> None:
        check_host_addresses("1.1.1.1", 443, cfg())
        check_host_addresses("127.0.0.1", 80, cfg(allow_loopback_http=True))

    def test_loopback_flag_does_not_permit_other_private_networks(self) -> None:
        for host in ("10.0.0.1", "172.16.0.1", "192.168.1.1", "fd00::1"):
            with self.assertRaises(MediaError, msg=host):
                check_host_addresses(host, 80, cfg(allow_loopback_http=True))

    def test_host_allow_list_is_enforced(self) -> None:
        expect_refused(self, "https://media.example.com/a.mp4",
                       cfg(host_allow_list=("only.example.com",)))
        validate_url("https://only.example.com/a.mp4",
                     cfg(host_allow_list=("only.example.com",)), resolve=False)

    def test_unsupported_scheme_is_refused(self) -> None:
        for url in ("ftp://media.example.com/a.mp4", "file:///C:/a.mp4", "s3://bucket/a.mp4"):
            expect_refused(self, url, cfg())

    def test_missing_host_is_refused(self) -> None:
        expect_refused(self, "https:///a.mp4", cfg())


class HashVerificationTest(unittest.TestCase):
    def test_match_passes(self) -> None:
        verify_hash(DownloadResult(path=temp_root() / "x", sha256="a" * 64, bytes_written=1),
                    "a" * 64)

    def test_mismatch_is_terminal(self) -> None:
        with self.assertRaises(MediaError) as ctx:
            verify_hash(DownloadResult(path=temp_root() / "x", sha256="b" * 64, bytes_written=1),
                        "a" * 64)
        self.assertEqual(ctx.exception.task_error.code, "MEDIA_HASH_MISMATCH")
        self.assertFalse(ctx.exception.task_error.retryable)
        self.assertNotIn("http", ctx.exception.task_error.message)


class DownloadSizeLimitTest(unittest.TestCase):
    @unittest.skipUnless(find_tools() is not None, "缺少 ffmpeg")
    def test_download_reports_matching_sha256_and_size(self) -> None:
        from headup.media import download_media

        root = temp_root("dl-")
        media = make_test_media(root, duration_ms=5_000)
        server = LocalMediaServer(media)
        try:
            local = Config(allow_loopback_http=True, host_allow_list=("127.0.0.1",))
            result = download_media(server.url, local, root)
            try:
                self.assertEqual(result.sha256, sha256_of(media))
                self.assertEqual(result.bytes_written, media.stat().st_size)
                verify_hash(result, sha256_of(media))
            finally:
                result.path.unlink(missing_ok=True)
        finally:
            server.close()

    @unittest.skipUnless(find_tools() is not None, "缺少 ffmpeg")
    def test_too_large_media_is_refused(self) -> None:
        from headup.media import download_media

        root = temp_root("dl-limit")
        # 用较长媒体，确保超限判定发生在下载过程中（而不是读完整个响应之后）
        media = make_test_media(root, duration_ms=20_000)
        server = LocalMediaServer(media)
        try:
            local = Config(allow_loopback_http=True, host_allow_list=("127.0.0.1",),
                           max_media_bytes=1024)
            with self.assertRaises(MediaError) as ctx:
                download_media(server.url, local, root)
            self.assertEqual(ctx.exception.task_error.code, "PAYLOAD_TOO_LARGE")
            self.assertFalse(ctx.exception.task_error.retryable)
        finally:
            server.close()


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
