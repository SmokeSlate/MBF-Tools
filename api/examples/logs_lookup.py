"""
Drop-in bot script for shared MBF log lookups.

Suggested /script set configuration:
- event: message
- regex: ^!s\s+([A-Za-z0-9]{4,16})$
- channels: your support channels

This script uses the helper environment documented by the bot platform:
- reply()
- react()
- remove_reaction()
- http_request()
- match
"""

LOOKUP_EMOJI = "🔎"
SUCCESS_EMOJI = "✅"
FAILURE_EMOJI = "❌"
MESSAGE_LIMIT = 1800
GAS_BASE_URL = "https://script.google.com/macros/s/AKfycbyS2gK65EMJxFi5_yzOZtBNpXRF-AOqfVIeo-aoMRNseZ62oSDuJkyBfWulY_dDoAs60Q/exec"


def _status(response):
    if isinstance(response, dict):
        return int(response.get("status") or response.get("status_code") or 0)
    return int(getattr(response, "status", getattr(response, "status_code", 0)) or 0)


def _body(response):
    if isinstance(response, dict):
        return (
            response.get("body")
            or response.get("text")
            or response.get("content")
            or ""
        )
    return (
        getattr(response, "body", None)
        or getattr(response, "text", None)
        or str(response)
    )


def _send_long_reply(text):
    remaining = (text or "").strip()
    if not remaining:
        return

    while remaining:
        chunk = remaining[:MESSAGE_LIMIT]
        if len(remaining) > MESSAGE_LIMIT:
            split_at = chunk.rfind("\n")
            if split_at > 400:
                chunk = chunk[:split_at]
        reply(chunk)
        remaining = remaining[len(chunk):].lstrip()


def _looks_missing(text):
    normalized = (text or "").strip().lower()
    if not normalized:
        return True
    return (
        "no shared log was found" in normalized
        or "\"ok\":false" in normalized
        or "\"ok\": false" in normalized
        or normalized == "null"
    )


def _request_succeeded(status, body):
    normalized_body = (body or "").strip()
    if not normalized_body:
        return False
    if _looks_missing(normalized_body):
        return False
    return status == 0 or 200 <= status < 300


async def _maybe_await(value):
    if hasattr(value, "__await__"):
        return await value
    return value


async def _main():
    code = (match.group(1) if match else "").lower().strip()
    if not code:
        reply("Usage: `!s <code>`")
        return

    react(LOOKUP_EMOJI)
    try:
        message_response = await _maybe_await(
            http_request(
                f"{GAS_BASE_URL}?action=message&code={code}",
                timeout=15,
            )
        )
        message_status = _status(message_response)
        message_body = _body(message_response).strip()

        if _request_succeeded(message_status, message_body):
            _send_long_reply(message_body)
            react(SUCCESS_EMOJI)
            return

        summary_response = await _maybe_await(
            http_request(
                f"{GAS_BASE_URL}?action=summary&code={code}&format=text",
                timeout=15,
            )
        )
        summary_status = _status(summary_response)
        summary_body = _body(summary_response).strip()

        if _request_succeeded(summary_status, summary_body):
            reply(
                f"Could not fetch the full message view for `{code}`.\n\n"
                f"{summary_body}\n\n"
                f"Viewer: https://logs.sm0ke.org/{code}"
            )
        else:
            reply(
                f"Could not find shared logs for `{code}`.\n\n"
                f"Viewer: https://logs.sm0ke.org/{code}"
            )
        react(FAILURE_EMOJI)
    finally:
        remove_reaction(LOOKUP_EMOJI)


try:
    asyncio.get_running_loop().create_task(_main())
except RuntimeError:
    asyncio.run(_main())
