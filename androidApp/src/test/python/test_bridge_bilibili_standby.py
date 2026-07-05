import sys
import unittest
from pathlib import Path
from types import SimpleNamespace


SRC_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(SRC_ROOT / "main" / "python"))

from fuo_mobile.bridge import standby_score, standby_searches  # noqa: E402


def song(source, title, artists=None):
    artist_models = [SimpleNamespace(name=artist) for artist in (artists or [])]
    return SimpleNamespace(
        source=source,
        identifier=f"{source}:{title}",
        title=title,
        artists=artist_models,
        artists_name=" / ".join(artists or []),
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

    def test_other_provider_keeps_existing_score_path(self):
        origin = song("netease", "晴天", ["周杰伦"])
        standby = song("qqmusic", "晴天", ["周杰伦"])

        self.assertAlmostEqual(0.80, standby_score(origin, standby))


if __name__ == "__main__":
    unittest.main()
