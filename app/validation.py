"""URL normalization and machine-readable native decoder output."""
import base64
from urllib.parse import quote, urlsplit

import idna

from .errors import AppError


def normalize_url(value: str) -> str:
    try:
        if not value or any(character.isspace() or ord(character) < 32 or ord(character) == 127
                            for character in value):
            raise ValueError("网址不能为空或包含空白、控制字符")
        parts = urlsplit(value)
        if parts.scheme not in ("http", "https") or not parts.hostname:
            raise ValueError("请输入完整的 http:// 或 https:// 网址")
        if parts.username is not None or parts.password is not None:
            raise ValueError("不接受带用户名或密码的网址")
        _ = parts.port
        authority = parts.netloc
        if not parts.hostname.isascii():
            host = idna.encode(parts.hostname, uts46=True, transitional=False,
                               std3_rules=True).decode("ascii")
            suffix = ":" + authority.rsplit(":", 1)[1] if ":" in authority else ""
            authority = host + suffix
        start = value.index("://") + 3
        rebuilt = value[:start] + authority + value[start + len(parts.netloc):]
        # Do not use urlunsplit: it loses meaningful empty '?' / '#' delimiters.
        normalized = quote(rebuilt, safe=":/?:#[]@!$&'()*+,;=-._~%")
        if len(normalized.encode("ascii")) > 512:
            raise ValueError("链接过长，请缩短链接；葵花码实际容量取决于内容")
        return normalized
    except (ValueError, UnicodeError) as error:
        raise AppError("INVALID_URL", str(error), 400) from error


def decode_payload(log: str) -> bytes:
    values: dict[str, str] = {}
    # Only LF is a record separator. Unicode line separators are payload data.
    for line in log.split("\n"):
        if line.startswith(("DECODED_TYPE=", "PAYLOAD_BASE64=")):
            key, value = line.split("=", 1)
            if key in values:
                raise AppError("RUNTIME_ERROR", "原生解码结果包含重复字段", 503)
            values[key] = value
    if values.get("DECODED_TYPE") != "WX_CODE" or "PAYLOAD_BASE64" not in values:
        raise AppError("RUNTIME_ERROR", "未能回读有效的 WX_CODE 数据", 503)
    try:
        return base64.b64decode(values["PAYLOAD_BASE64"], validate=True)
    except ValueError as error:
        raise AppError("RUNTIME_ERROR", "原生解码数据无效", 503) from error
