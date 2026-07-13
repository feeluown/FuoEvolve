import sys
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT_DIR = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT_DIR / "shared" / "src" / "commonMain" / "python"))

from fuo_mobile.bridge import FuoMobileBridge, ytmusic_authorization_from_cookies  # noqa: E402


class _Signal:
    def __init__(self):
        self.emitted = []

    def emit(self, user):
        self.emitted.append(user)


class _Provider:
    def __init__(self):
        self.current_user_changed = _Signal()
        self.authed_user = None

    def try_get_user_with_headerfile(self):
        return object()

    def auth(self, user):
        self.authed_user = user


class YtmusicLoginTest(unittest.TestCase):
    def test_missing_secure_papisid_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "__Secure-3PAPISID"):
            ytmusic_authorization_from_cookies({"SID": "sid"})

    def test_cookies_write_headerfile_and_auth_provider(self):
        provider = _Provider()
        bridge = FuoMobileBridge.__new__(FuoMobileBridge)

        with patch("fuo_ytmusic.headerfile.write_headerfile") as write_headerfile:
            user = bridge._login_ytmusic_with_cookies(
                provider,
                {
                    "__Secure-3PAPISID": "papisid",
                    "SID": "sid",
                },
            )

        write_headerfile.assert_called_once()
        auth, cookie, _ = write_headerfile.call_args.args
        self.assertTrue(auth.startswith("SAPISIDHASH "))
        self.assertEqual("__Secure-3PAPISID=papisid; SID=sid", cookie)
        self.assertIs(provider.authed_user, user)
        self.assertEqual([user], provider.current_user_changed.emitted)


if __name__ == "__main__":
    unittest.main()
