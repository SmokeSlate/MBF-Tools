import re
import runpy
import unittest
from pathlib import Path
from unittest.mock import Mock

SCRIPT = Path(__file__).resolve().parents[1] / "logs_lookup.py"


class LogsLookupTest(unittest.TestCase):
    def run_lookup(self, responses, asynchronous=False):
        request = Mock(side_effect=responses)
        reply, react, remove = Mock(), Mock(), Mock()

        async def async_request(*args, **kwargs):
            return request(*args, **kwargs)

        runpy.run_path(str(SCRIPT), init_globals={
            "match": re.match(r"!s (\w+)", "!s ABC12"),
            "http_request": async_request if asynchronous else request,
            "reply": reply,
            "react": react,
            "remove_reaction": remove,
        })
        remove.assert_called_once_with("🔎")
        return request, reply, react

    def test_current_worker_message(self):
        request, reply, react = self.run_lookup([
            {"status": 200, "body": "Current Worker log"},
        ])
        request.assert_called_once_with("https://logs.mbf.tools/message/abc12", timeout=15)
        reply.assert_any_call("Current Worker log")
        react.assert_any_call("✅")

    def test_summary_fallback_uses_worker_plain_text(self):
        request, reply, _ = self.run_lookup([
            {"status": 500, "body": "Unavailable"},
            {"status": 200, "body": "Current Worker summary"},
        ])
        self.assertEqual(request.call_args_list[1].args[0],
                         "https://logs.mbf.tools/summary/abc12?format=text")
        self.assertIn("Current Worker summary", reply.call_args.args[0])

    def test_missing_record(self):
        _, reply, react = self.run_lookup([
            {"status": 404, "body": "No shared log was found"},
            {"status": 404, "body": '{"ok":false}'},
        ])
        self.assertIn("Could not find shared logs", reply.call_args.args[0])
        react.assert_any_call("❌")

    def test_async_http_helper(self):
        request, reply, _ = self.run_lookup([
            {"status": 200, "body": "Async Worker log"},
        ], asynchronous=True)
        self.assertEqual(request.call_count, 1)
        reply.assert_any_call("Async Worker log")


if __name__ == "__main__":
    unittest.main()
