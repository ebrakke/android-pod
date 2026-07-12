"""Tests for Reclaimed Player media helpers."""

import importlib.util
from pathlib import Path
import unittest


MODULE_PATH = (
    Path(__file__).parents[1]
    / "custom_components"
    / "reclaimed_player"
    / "media.py"
)
SPEC = importlib.util.spec_from_file_location("reclaimed_player_media", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
MEDIA = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MEDIA)


class JellyfinStreamUrlTest(unittest.TestCase):
    def test_builds_static_stream_url_with_encoded_id(self) -> None:
        url = MEDIA.jellyfin_stream_url(
            "http://jellyfin.local:8096",
            "track/id",
            "mp3",
        )

        self.assertEqual(
            url,
            "http://jellyfin.local:8096/Audio/track%2Fid/stream.mp3?static=true",
        )


if __name__ == "__main__":
    unittest.main()
