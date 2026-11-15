"""Tests for the per-language privacy policy URL: privacy_url.txt in store-listing/app-store/<locale>/,
synced by scripts/testflight.py and checked by scripts/check_store_listing.py.

    python3 -m unittest discover -s scripts/tests

Offline: App Store Connect is the fake from test_testflight_listing, and the policy site's answers
come from a {url: status} table instead of the network.
"""
import pathlib
import sys
import tempfile
import unittest
import urllib.error
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import check_store_listing  # noqa: E402
import testflight  # noqa: E402
from test_testflight_listing import FakeAsc, loc, quiet, write_listing  # noqa: E402

SITE = testflight.PRIVACY_SITE
EN, RU, NB, ES = (f"{SITE}{page}.html" for page in ("en", "ru", "nb", "es"))


class Site:
    """status_of for sync_listing: answers from a table (unlisted URLs 404) and counts the asks."""

    def __init__(self, statuses):
        self.statuses = statuses
        self.asked = []

    def __call__(self, url):
        self.asked.append(url)
        return self.statuses.get(url, 404)


def info_writes(asc):
    """{(method, locale or id): attributes} of the app info localization writes."""
    writes = {}
    for method, path, body in asc.writes():
        data = body["data"]
        if data["type"] != "appInfoLocalizations":
            continue
        key = data["attributes"].get("locale") if method == "POST" else path.rsplit("/", 1)[1]
        writes[(method, key)] = data["attributes"]
    return writes


class LiveUrlTests(unittest.TestCase):
    def test_a_live_page_is_set(self):
        site = Site({EN: 200, SITE: 200})
        url, out = quiet(testflight.live_privacy_url, "en-GB", EN, site)
        self.assertEqual(url, EN)
        self.assertEqual(out, "")
        self.assertEqual(site.asked, [EN])

    def test_a_missing_page_falls_back_to_the_chooser_with_a_warning(self):
        url, out = quiet(testflight.live_privacy_url, "es-ES", ES, Site({SITE: 200}))
        self.assertEqual(url, SITE)
        self.assertIn("::warning::es-ES", out)
        self.assertIn(f"{ES} isn't live (HTTP 404)", out)

    def test_an_unreachable_site_leaves_the_field_alone(self):
        url, out = quiet(testflight.live_privacy_url, "ru", RU, lambda _: None)
        self.assertIsNone(url)
        self.assertIn("left as App Store Connect has it", out)
        self.assertIn("(no answer)", out)

    def test_a_redirect_that_ends_elsewhere_is_not_live(self):
        # url_status follows redirects; only a final 200 counts.
        url, _ = quiet(testflight.live_privacy_url, "ru", RU, Site({RU: 301, SITE: 200}))
        self.assertEqual(url, SITE)

    def test_url_status_reads_http_errors_and_network_failures(self):
        not_found = urllib.error.HTTPError(ES, 404, "Not Found", {}, None)
        with mock.patch.object(testflight.urllib.request, "urlopen", side_effect=not_found):
            self.assertEqual(testflight.url_status(ES), 404)
        with mock.patch.object(testflight.urllib.request, "urlopen",
                               side_effect=urllib.error.URLError("offline")):
            self.assertIsNone(testflight.url_status(ES))
        response = mock.MagicMock()
        response.__enter__.return_value.status = 200
        with mock.patch.object(testflight.urllib.request, "urlopen", return_value=response) as opened:
            self.assertEqual(testflight.url_status(EN), 200)
        request = opened.call_args[0][0]
        self.assertEqual(request.full_url, EN)
        self.assertNotIn("@", request.get_header("User-agent"))


class SyncPrivacyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        write_listing(self.root, "en-GB", name="BestBefore", subtitle="Sub", keywords="k",
                      promotional_text="Promo", description="Desc", privacy_url=EN)
        write_listing(self.root, "en-US", name="BestBefore US", keywords="ku")
        write_listing(self.root, "ru", name="Срок", subtitle="Под", keywords="к",
                      promotional_text="П", description="О", privacy_url=RU)
        write_listing(self.root, "es-ES", name="Caduca", subtitle="S", keywords="c",
                      promotional_text="P", description="D", privacy_url=ES)
        write_listing(self.root, "es-MX", name="Caduca MX", keywords="mx")
        self.asc = FakeAsc({
            "/appStoreVersions/v1/appStoreVersionLocalizations": [
                loc("vl-gb", "en-GB", keywords="k", promotionalText="Promo", description="Desc",
                    supportUrl="https://support"),
                loc("vl-us", "en-US", keywords="ku", promotionalText="Promo", description="Desc"),
                loc("vl-ru", "ru", keywords="к", promotionalText="П", description="О"),
                loc("vl-es", "es-ES", keywords="c", promotionalText="P", description="D"),
            ],
            "/appInfos/i1/appInfoLocalizations": [
                loc("il-gb", "en-GB", name="BestBefore", subtitle="Sub", privacyPolicyUrl=SITE,
                    privacyChoicesUrl="https://choices"),
                loc("il-us", "en-US", name="BestBefore US", subtitle="Sub", privacyPolicyUrl=SITE),
                loc("il-ru", "ru", name="Срок", subtitle="Под", privacyPolicyUrl=SITE),
                loc("il-es", "es-ES", name="Caduca", subtitle="S", privacyPolicyUrl=SITE),
            ],
        })

    def tearDown(self):
        self.tmp.cleanup()

    def sync(self, statuses, apply):
        site = Site(statuses)
        (result, changed), out = quiet(testflight.sync_listing, self.asc, "v1", "i1", apply,
                                       self.root, status_of=site)
        return changed, out, site

    def test_privacy_url_is_an_app_info_field(self):
        self.assertEqual(testflight.LISTING_FIELDS["privacy_url.txt"], ("info", "privacyPolicyUrl"))
        self.assertEqual(testflight.PRIVACY_SITE, check_store_listing.PRIVACY_SITE)

    def test_dry_run_shows_each_page_and_writes_nothing(self):
        changed, out, _ = self.sync({EN: 200, RU: 200, ES: 200, SITE: 200}, apply=False)
        self.assertTrue(changed)
        self.assertEqual(self.asc.writes(), [])
        self.assertIn(f"privacyPolicyUrl: {SITE!r} -> {RU!r}", out)
        self.assertIn("  en-US: name, keywords from its folder; subtitle, promotionalText, "
                      "description, privacyPolicyUrl inherited from en-GB", out)

    def test_apply_sets_each_language_its_page_and_regional_ones_inherit(self):
        self.sync({EN: 200, RU: 200, ES: 200, SITE: 200}, apply=True)
        writes = info_writes(self.asc)
        self.assertEqual(writes[("PATCH", "il-gb")], {"privacyPolicyUrl": EN})
        self.assertEqual(writes[("PATCH", "il-us")], {"privacyPolicyUrl": EN})
        self.assertEqual(writes[("PATCH", "il-ru")], {"privacyPolicyUrl": RU})
        self.assertEqual(writes[("PATCH", "il-es")], {"privacyPolicyUrl": ES})
        # es-MX is created with es-ES's page, not the primary language's URL.
        created = writes[("POST", "es-MX")]
        self.assertEqual(created["privacyPolicyUrl"], ES)
        self.assertEqual(created["privacyChoicesUrl"], "https://choices")  # still copied

    def test_each_url_is_checked_once(self):
        _, _, site = self.sync({EN: 200, RU: 200, ES: 200}, apply=False)
        self.assertEqual(sorted(site.asked), sorted([EN, RU, ES]))

    def test_a_page_not_yet_live_gets_the_chooser(self):
        changed, out, _ = self.sync({EN: 200, RU: 200, SITE: 200}, apply=True)
        writes = info_writes(self.asc)
        self.assertNotIn(("PATCH", "il-es"), writes)  # already the chooser: nothing to write
        self.assertEqual(writes[("POST", "es-MX")]["privacyPolicyUrl"], SITE)
        self.assertIn(f"::warning::es-ES: the privacy policy page {ES} isn't live", out)
        self.assertEqual(writes[("PATCH", "il-ru")], {"privacyPolicyUrl": RU})

    def test_a_page_that_went_missing_is_moved_back_to_the_chooser(self):
        self.asc.gets["/appInfos/i1/appInfoLocalizations"][3]["attributes"]["privacyPolicyUrl"] = ES
        self.sync({EN: 200, RU: 200, SITE: 200}, apply=True)
        self.assertEqual(info_writes(self.asc)[("PATCH", "il-es")], {"privacyPolicyUrl": SITE})

    def test_an_unreachable_site_writes_no_privacy_url(self):
        changed, out, _ = self.sync({}, apply=True)
        for (method, key), attributes in info_writes(self.asc).items():
            if method == "PATCH":
                self.assertNotIn("privacyPolicyUrl", attributes, key)
        # A new localization then takes the primary language's, as before.
        self.assertEqual(info_writes(self.asc)[("POST", "es-MX")]["privacyPolicyUrl"], SITE)
        self.assertIn("left as App Store Connect has it", out)

    def test_rerun_with_the_pages_set_changes_nothing(self):
        infos = self.asc.gets["/appInfos/i1/appInfoLocalizations"]
        for info, url in zip(infos, (EN, EN, RU, ES)):
            info["attributes"]["privacyPolicyUrl"] = url
        infos.append(loc("il-mx", "es-MX", name="Caduca MX", subtitle="S", privacyPolicyUrl=ES))
        self.asc.gets["/appStoreVersions/v1/appStoreVersionLocalizations"].append(
            loc("vl-mx", "es-MX", keywords="mx", promotionalText="P", description="D"))
        changed, _, _ = self.sync({EN: 200, RU: 200, ES: 200}, apply=True)
        self.assertFalse(changed)
        self.assertEqual(self.asc.writes(), [])

    def test_a_folder_without_the_file_never_touches_the_url(self):
        with tempfile.TemporaryDirectory() as root:
            write_listing(root, "en-GB", name="BestBefore", subtitle="Sub")
            site = Site({})
            quiet(testflight.sync_listing, self.asc, "v1", "i1", True, root, status_of=site)
            self.assertEqual(site.asked, [])
            self.assertNotIn(("PATCH", "il-gb"), info_writes(self.asc))


class CheckPrivacyUrlTests(unittest.TestCase):
    TEXTS = {"name": "N", "subtitle": "S", "keywords": "k", "promotional_text": "P",
             "description": "D"}

    def checked(self, **locales):
        with tempfile.TemporaryDirectory() as root:
            for locale, files in locales.items():
                write_listing(pathlib.Path(root) / "app-store", locale.replace("_", "-"), **files)
            with mock.patch.object(check_store_listing, "ROOT", pathlib.Path(root)):
                problems, _ = quiet(check_store_listing.check, stores=("app-store",))
        return [p for p in problems if "privacy_url" in p]

    def test_each_language_takes_its_own_page(self):
        self.assertEqual(self.checked(
            en_GB={**self.TEXTS, "privacy_url": EN}, no={**self.TEXTS, "privacy_url": NB},
            ru={**self.TEXTS, "privacy_url": RU}), [])

    def test_regional_folders_inherit_it(self):
        self.assertEqual(self.checked(
            en_GB={**self.TEXTS, "privacy_url": EN}, en_AU={"keywords": "k"},
            es_ES={**self.TEXTS, "privacy_url": ES}, es_MX=self.TEXTS), [])

    def test_another_languages_page_is_refused(self):
        problems = self.checked(en_GB={**self.TEXTS, "privacy_url": EN},
                                es_ES={**self.TEXTS, "privacy_url": EN},
                                no={**self.TEXTS, "privacy_url": f"{SITE}no.html"})
        self.assertEqual(problems, [
            f"app-store/es-ES/privacy_url.txt: {EN!r} is not this language's policy page, {ES}",
            f"app-store/no/privacy_url.txt: '{SITE}no.html' is not this language's policy page, {NB}"])

    def test_other_hosts_http_and_the_chooser_are_refused(self):
        for url in ("http://daniil-pastuhov.github.io/bestbefore-privacy/en.html",
                    "https://example.com/bestbefore-privacy/en.html", SITE, EN + "?x"):
            self.assertEqual(len(self.checked(en_GB={**self.TEXTS, "privacy_url": url})), 1, url)

    def test_missing_and_unknown_language(self):
        self.assertEqual(self.checked(en_GB=self.TEXTS), ["app-store/en-GB/privacy_url.txt: missing"])
        self.assertEqual(self.checked(en_GB={**self.TEXTS, "privacy_url": EN},
                                      it={**self.TEXTS, "privacy_url": f"{SITE}it.html"}),
                         ["app-store/it/privacy_url.txt: the policy site has no page for 'it' "
                          "(PRIVACY_PAGES)"])

    def test_every_repo_folder_points_at_its_page(self):
        for locale in testflight.listing_locales():
            sources = testflight.listing_files(testflight.LISTING_DIR, locale,
                                               testflight.listing_locales())
            self.assertIn("privacy_url.txt", sources, locale)
            url = testflight.read_listing_files(
                testflight.LISTING_DIR, sources)["info"]["privacyPolicyUrl"]
            page = check_store_listing.PRIVACY_PAGES[locale.split("-")[0]]
            self.assertEqual(url, f"{SITE}{page}.html", locale)


if __name__ == "__main__":
    unittest.main()
