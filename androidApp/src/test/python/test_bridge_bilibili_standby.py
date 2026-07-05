import sys
import unittest
from pathlib import Path
from types import SimpleNamespace


SRC_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(SRC_ROOT / "main" / "python"))

from fuo_mobile.bridge import duration_ms, standby_score, standby_searches  # noqa: E402


def song(source, title, artists=None, duration=None, duration_ms=None):
    artist_models = [SimpleNamespace(name=artist) for artist in (artists or [])]
    return SimpleNamespace(
        source=source,
        identifier=f"{source}:{title}",
        title=title,
        artists=artist_models,
        artists_name=" / ".join(artists or []),
        duration=duration,
        duration_ms=duration_ms,
    )


class BilibiliStandbyTest(unittest.TestCase):
    def test_bilibili_search_uses_title_and_artist_only(self):
        origin = song("netease", "晴天", ["周杰伦"])

        searches = standby_searches(origin, ["qqmusic", "bilibili"])

        self.assertEqual(
            [
                ("晴天 周杰伦", ["qqmusic"]),
                ("晴天", ["qqmusic"]),
                ("晴天 周杰伦", ["bilibili"]),
            ],
            searches,
        )

    def test_bilibili_score_uses_case_insensitive_title_keywords(self):
        origin = song("netease", "Night Song", ["Alice"])
        standby = song("bilibili", "ALICE - night song Hi-Res MV", [])

        self.assertAlmostEqual(0.70, standby_score(origin, standby))

    def test_bilibili_score_penalizes_cover_keywords(self):
        origin = song("netease", "晴天", ["周杰伦"])
        standby = song("bilibili", "周杰伦 晴天 Cover 翻唱", [])

        self.assertAlmostEqual(0.40, standby_score(origin, standby))

    def test_bilibili_score_keeps_original_keyword_versions(self):
        origin = song("netease", "晴天 Remix", ["周杰伦"])
        standby = song("bilibili", "周杰伦 晴天 REMIX", [])

        self.assertAlmostEqual(0.60, standby_score(origin, standby))

    def test_other_provider_keeps_existing_score_path(self):
        origin = song("netease", "晴天", ["周杰伦"])
        standby = song("qqmusic", "晴天", ["周杰伦"])

        self.assertAlmostEqual(0.80, standby_score(origin, standby))

    def test_other_provider_penalizes_duration_difference(self):
        origin = song("netease", "晴天", ["周杰伦"], duration=200_000)
        standby = song("qqmusic", "晴天", ["周杰伦"], duration=300_000)

        self.assertAlmostEqual(0.65, standby_score(origin, standby))

    def test_other_provider_parses_brief_duration_text_for_penalty(self):
        origin = song("netease", "晴天", ["周杰伦"], duration=210_000)
        standby = song("qqmusic", "晴天", ["周杰伦"], duration_ms="01:45")

        self.assertAlmostEqual(0.65, standby_score(origin, standby))

    def test_bilibili_score_penalizes_duration_difference(self):
        origin = song("netease", "Night Song", ["Alice"], duration=200_000)
        standby = song("bilibili", "ALICE - night song Hi-Res MV", [], duration=300_000)

        self.assertAlmostEqual(0.55, standby_score(origin, standby))

    def test_invalid_duration_does_not_penalize(self):
        origin = song("netease", "Night Song", ["Alice"], duration=200_000)
        standby = song("bilibili", "ALICE - night song Hi-Res MV", [], duration_ms="unknown")

        self.assertAlmostEqual(0.70, standby_score(origin, standby))

    def test_duration_ms_parses_colon_text(self):
        self.assertEqual(210_000, duration_ms(song("qqmusic", "晴天", duration_ms="03:30")))
        self.assertEqual(3_723_000, duration_ms(song("qqmusic", "演唱会", duration_ms="01:02:03")))


if __name__ == "__main__":
    unittest.main()
