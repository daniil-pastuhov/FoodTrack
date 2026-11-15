"""Tests for the in-app event sync in scripts/testflight.py (`testflight.py event`) and the event
checks in scripts/check_store_listing.py, against a fake App Store Connect.

    python3 -m unittest discover -s scripts/tests

No network and no credentials: the fake answers the calls the sync makes and records the writes.
"""
import datetime
import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import check_store_listing  # noqa: E402
import testflight  # noqa: E402
from test_testflight_listing import FakeAsc, fixed_spec, md5, png, quiet  # noqa: E402

CONFIG = {
    "referenceName": "Recipe tab launch (Major Update)", "badge": "MAJOR_UPDATE",
    "purpose": "APPROPRIATE_FOR_ALL_USERS", "priority": "HIGH",
    "purchaseRequirement": "NO_COST_ASSOCIATED", "deepLink": "foodtracker://recipe",
    "primaryLocale": "en-GB", "territories": ["GBR", "NOR"], "releaseDay": "2026-12-01",
    "publishDaysBefore": 2, "startTimeUtc": "08:00", "durationDays": 30,
}
TEXTS = {
    "en-GB": ("Recipes From What's Expiring", "A recipe from the food that expires first",
              "The new Recipe tab turns what expires first into a recipe."),
    "en-US": ("Recipes From What's Expiring", "A recipe from the food that expires first",
              "The new Recipe tab turns what expires first into a recipe."),
    "ru": ("Новая вкладка «Рецепт»", "Готовьте из того, что скоро испортится",
           "Вкладка «Рецепт» отметит продукты с ближайшим сроком."),
}
CARD, DETAILS = (3840, 2160), (2160, 3840)
SCHEDULE = [{"territories": ["NOR", "GBR"], "publishStart": "2026-11-29T08:00:00.000+0000",
             "eventStart": "2026-12-01T08:00:00.000+0000", "eventEnd": "2026-12-31T08:00:00Z"}]


def write_event(root, config=None, texts=None, media=("en-GB", "ru")):
    root = pathlib.Path(root)
    root.mkdir(parents=True, exist_ok=True)
    (root / "event.json").write_text(json.dumps(config or CONFIG), encoding="utf-8")
    files = {}
    for locale, (name, short, long) in (texts or TEXTS).items():
        folder = root / locale
        folder.mkdir(exist_ok=True)
        for file, text in (("name.txt", name), ("short_description.txt", short),
                           ("long_description.txt", long)):
            (folder / file).write_text(text + "\n", encoding="utf-8")
    for locale in media:
        for file, size in (("event-card.png", CARD), ("event-details.png", DETAILS)):
            data = png(*size, f"{locale}/{file}".encode())
            (root / locale / file).write_bytes(data)
            files[(locale, file)] = data
    return files


def event_ref_data():
    """Reference data with the in-app event placement types in one group (made-up ids), next to
    a header placement that the event sync must not pick."""
    return [{"id": "ref", "attributes": {
        "placementProfileGroups": [{"placementProfileGroupId": "DEFAULT_PROFILE",
                                    "displayClassId": "DEFAULT", "platform": "ANY"}],
        "placementTypes": [
            {"placementTypeId": "EVENT_CARD_ASSET", "acceptsAssetCategories": ["CREATIVE_ASSETS"],
             "specMappings": [{"placementGroupId": "DEFAULT_PROFILE", "specs": ["spec-card"]}]},
            {"placementTypeId": "EVENT_DETAILS_PAGE_ASSET", "acceptsAssetCategories": ["CREATIVE_ASSETS"],
             "specMappings": [{"placementGroupId": "DEFAULT_PROFILE", "specs": ["spec-details"]}]},
            {"placementTypeId": "PRODUCT_PAGE_HEADER_ASSET", "acceptsAssetCategories": ["CREATIVE_ASSETS"],
             "specMappings": [{"placementGroupId": "DEFAULT_PROFILE", "specs": ["spec-header"]}]}],
        "imageSpecs": [
            {"specId": "spec-card", "shortName": "card", "aspectRatio": "16:9",
             "fileExtensions": [".jpg", ".jpeg", ".png"], "dimensions": {
                 "minWidth": 1920, "maxWidth": 3840, "minHeight": 1080, "maxHeight": 2160}},
            {"specId": "spec-details", "shortName": "details", "aspectRatio": "9:16",
             "fileExtensions": [".jpg", ".jpeg", ".png"], "dimensions": {
                 "minWidth": 1080, "maxWidth": 2160, "minHeight": 1920, "maxHeight": 3840}},
            fixed_spec("spec-header", 3840, 1646, "21:9", (".png",))],
        "features": [
            {"featureId": "APP_STORE_VERSIONS", "placementPolicies": [
                {"placementType": "PRODUCT_PAGE_HEADER_ASSET",
                 "groupLimits": [{"groupIds": ["DEFAULT_PROFILE"], "maxCount": 1}]}]},
            {"featureId": "IN_APP_EVENTS", "placementPolicies": [
                {"placementType": "EVENT_CARD_ASSET",
                 "groupLimits": [{"groupIds": ["DEFAULT_PROFILE"], "maxCount": 1}]},
                {"placementType": "EVENT_DETAILS_PAGE_ASSET",
                 "groupLimits": [{"groupIds": ["DEFAULT_PROFILE"], "maxCount": 1}]}]}],
    }}]


class EventFake(FakeAsc):
    """FakeAsc whose placement lists are keyed by placement type, as the API filters them."""

    def __init__(self, gets=None, responses=None, placements=None):
        super().__init__(gets, responses)
        self.placements = placements or {}
        self.image_spec = lambda attrs: "spec-card" if "event-card" in attrs["referenceName"] else "spec-details"

    def call(self, method, path, params=None, body=None):
        if method == "GET" and path.endswith("/placements"):
            self.calls.append((method, path, body))
            return self.placements.get((path, (params or {}).get("filter[placementType]")),
                                       {"data": [], "included": []})
        return super().call(method, path, params, body)


def tagged_image(id_, label, data, size):
    return {"id": id_, "attributes": {
        "fileName": label.rsplit("/", 1)[1], "referenceName": f"{label} md5:{md5(data)}",
        "state": "APPROVED", "category": "CREATIVE_ASSETS",
        "specId": "spec-card" if size == CARD else "spec-details",
        "imageAsset": {"width": size[0], "height": size[1]}}}


def remote_event(state="DRAFT", **overrides):
    attributes = {k: CONFIG[k] for k in testflight.EVENT_SETTINGS}
    attributes.update(referenceName=CONFIG["referenceName"], eventState=state,
                      territorySchedules=SCHEDULE, **overrides)
    return {"id": "ev", "type": "appEvents", "attributes": attributes}


def localization(id_, locale, texts=None):
    name, short, long = texts or TEXTS[locale]
    return {"id": id_, "attributes": {"locale": locale, "name": name, "shortDescription": short,
                                      "longDescription": long}}


class EventTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name) / "in-app-event"
        self.files = write_event(self.root)

    def tearDown(self):
        self.tmp.cleanup()

    def fake(self, events=(), localizations=(), library=(), placements=None, submissions=(),
             items=None, versions=()):
        gets = {"/apps/app/appEvents": list(events),
                "/apps/app/assetLibrary": {"id": "lib", "type": "appAssetLibraries"},
                "/appAssetLibraryRefData": event_ref_data(),
                "/appAssetLibraries/lib/images": list(library),
                "/apps/app/appStoreVersions": list(versions),
                "/reviewSubmissions": list(submissions)}
        if events:
            gets["/appEvents/ev/localizations"] = list(localizations)
        for submission_id, held in (items or {}).items():
            gets[f"/reviewSubmissions/{submission_id}/items"] = held
        return EventFake(gets, placements=placements)

    def synced_fake(self, **kwargs):
        """App Store Connect as the repo's event leaves it: every text, setting and image."""
        library, placements = [], {}
        for locale, loc_id in (("en-GB", "l-gb"), ("en-US", "l-us"), ("ru", "l-ru")):
            source = "en-GB" if locale == "en-US" else locale
            for file, placement_type, size in (("event-card.png", "EVENT_CARD_ASSET", CARD),
                                               ("event-details.png", "EVENT_DETAILS_PAGE_ASSET", DETAILS)):
                image = tagged_image(f"img-{source}-{file}", f"in-app-event/{source}/{file}",
                                     self.files[(source, file)], size)
                if image not in library:
                    library.append(image)
                placements[(f"/appEventLocalizations/{loc_id}/placements", placement_type)] = {
                    "data": [{"id": f"p-{loc_id}-{placement_type}",
                              "attributes": {"placementType": placement_type, "state": "ACTIVE"},
                              "relationships": {"image": {"data": {"id": image["id"]}}}}],
                    "included": [image]}
        kwargs.setdefault("events", [remote_event()])
        return self.fake(localizations=[localization("l-gb", "en-GB"), localization("l-us", "en-US"),
                                        localization("l-ru", "ru")],
                         library=library, placements=placements, **kwargs)

    def run_event(self, asc, apply, add_to_review=False):
        return quiet(testflight.run_event, asc, "app", apply, add_to_review, self.root)


class ReadEventTests(EventTestCase):
    def test_texts_per_locale_and_english_takes_en_gb_images(self):
        event = testflight.read_event(self.root)
        self.assertEqual(event["config"]["referenceName"], CONFIG["referenceName"])
        self.assertEqual(event["texts"]["ru"]["name"], "Новая вкладка «Рецепт»")
        self.assertEqual(set(event["texts"]["en-US"]), {"name", "shortDescription", "longDescription"})
        self.assertEqual(event["media"]["en-US"]["EVENT_CARD_ASSET"]["label"],
                         "in-app-event/en-GB/event-card.png")
        self.assertEqual(event["media"]["ru"]["EVENT_DETAILS_PAGE_ASSET"]["dimensions"], DETAILS)

    def test_schedule_from_release_day(self):
        publish, start, end = testflight.event_schedule(CONFIG)
        utc = datetime.timezone.utc
        self.assertEqual(start, datetime.datetime(2026, 12, 1, 8, 0, tzinfo=utc))
        self.assertEqual(publish, datetime.datetime(2026, 11, 29, 8, 0, tzinfo=utc))
        self.assertEqual(end, datetime.datetime(2026, 12, 31, 8, 0, tzinfo=utc))
        self.assertIsNone(testflight.event_schedule({**CONFIG, "releaseDay": None}))

    def test_schedules_compare_across_time_formats_and_territory_order(self):
        wanted = [{"territories": ["GBR", "NOR"], "publishStart": "2026-11-29T08:00:00Z",
                   "eventStart": "2026-12-01T08:00:00Z", "eventEnd": "2026-12-31T08:00:00Z"}]
        self.assertEqual(testflight.schedule_key(SCHEDULE), testflight.schedule_key(wanted))
        later = [{**wanted[0], "eventEnd": "2026-12-31T09:00:00Z"}]
        self.assertNotEqual(testflight.schedule_key(SCHEDULE), testflight.schedule_key(later))


class EventSyncTests(EventTestCase):
    def test_dry_run_against_no_event_writes_nothing_and_lists_everything(self):
        asc = self.fake()
        code, out = self.run_event(asc, apply=False)
        self.assertEqual(code, 0)
        self.assertEqual(asc.writes(), [])
        self.assertIn("would create", out)
        self.assertIn("localization ru: would create", out)
        self.assertIn("en-US event card: would place in-app-event/en-GB/event-card.png", out)
        self.assertIn("not submitted for review", out)

    def test_apply_creates_the_event_its_localizations_and_places_each_image(self):
        asc = self.fake()
        code, _ = self.run_event(asc, apply=True)
        self.assertEqual(code, 0)
        posts = [(path, body) for method, path, body in asc.writes("POST")]
        event = next(body for path, body in posts if path == "/appEvents")["data"]
        self.assertEqual(event["attributes"]["badge"], "MAJOR_UPDATE")
        self.assertEqual(event["attributes"]["purchaseRequirement"], "NO_COST_ASSOCIATED")
        self.assertEqual(event["attributes"]["territorySchedules"], [{
            "territories": ["GBR", "NOR"], "publishStart": "2026-11-29T08:00:00Z",
            "eventStart": "2026-12-01T08:00:00Z", "eventEnd": "2026-12-31T08:00:00Z"}])
        locales = [body["data"]["attributes"]["locale"] for path, body in posts
                   if path == "/appEventLocalizations"]
        self.assertEqual(locales, ["en-GB", "en-US", "ru"])
        # Four distinct files uploaded once each (en-US reuses en-GB's), six placements.
        uploads = [body["data"]["attributes"] for path, body in posts if path == "/appAssetLibraryImages"]
        self.assertEqual(len(uploads), 4)
        self.assertTrue(all(u["category"] == "CREATIVE_ASSETS" for u in uploads))
        placed = [body["data"] for path, body in posts if path == "/appAssetLibraryPlacements"]
        self.assertEqual(len(placed), 6)
        self.assertTrue(all("appEventLocalization" in p["relationships"] for p in placed))
        self.assertEqual({p["attributes"]["placementGroup"] for p in placed}, {"DEFAULT_PROFILE"})
        self.assertEqual(sorted(p["attributes"]["placementType"] for p in placed),
                         ["EVENT_CARD_ASSET"] * 3 + ["EVENT_DETAILS_PAGE_ASSET"] * 3)
        # Never sent for review.
        self.assertFalse([w for w in asc.writes() if "reviewSubmission" in w[1]])

    def test_rerun_against_what_it_wrote_changes_nothing(self):
        asc = self.synced_fake()
        code, out = self.run_event(asc, apply=True)
        self.assertEqual(code, 0)
        self.assertEqual(asc.writes(), [])
        self.assertIn("The in-app event is up to date", out)

    def test_a_changed_text_patches_only_that_field(self):
        asc = self.synced_fake()
        (self.root / "ru" / "name.txt").write_text("Рецепты из того, что есть\n", encoding="utf-8")
        _, out = self.run_event(asc, apply=True)
        self.assertEqual(asc.writes(), [("PATCH", "/appEventLocalizations/l-ru", {"data": {
            "type": "appEventLocalizations", "id": "l-ru",
            "attributes": {"name": "Рецепты из того, что есть"}}})])
        self.assertIn("localization ru: updating", out)

    def test_a_changed_image_replaces_the_placement_and_keeps_the_library_image(self):
        asc = self.synced_fake()
        (self.root / "ru" / "event-card.png").write_bytes(png(*CARD, b"new card"))
        self.run_event(asc, apply=True)
        writes = [(m, p) for m, p, _ in asc.writes()]
        self.assertIn(("DELETE", "/appAssetLibraryPlacements/p-l-ru-EVENT_CARD_ASSET"), writes)
        self.assertEqual([p for m, p in writes if m == "POST"],
                         ["/appAssetLibraryImages", "/appAssetLibraryPlacements"])
        self.assertFalse([p for m, p in writes if p.startswith("/appAssetLibraryImages/") and m == "DELETE"])

    def test_a_published_event_is_only_compared(self):
        asc = self.synced_fake(events=[remote_event(state="PUBLISHED", priority="NORMAL")])
        code, out = self.run_event(asc, apply=True)
        self.assertEqual(code, 1)
        self.assertEqual(asc.writes(), [])
        self.assertIn("PUBLISHED", out)
        self.assertIn("priority: 'NORMAL' -> 'HIGH'", out)

    def test_no_release_day_sends_no_schedule(self):
        write_event(self.root, config={**CONFIG, "releaseDay": None})
        asc = self.fake()
        _, out = self.run_event(asc, apply=True)
        event = next(body for _, path, body in asc.writes("POST") if path == "/appEvents")["data"]
        self.assertNotIn("territorySchedules", event["attributes"])
        self.assertIn("releaseDay is null", out)

    def test_app_territories_come_from_the_app_availability(self):
        write_event(self.root, config={**CONFIG, "territories": "app"})
        asc = self.fake()
        asc.responses["/apps/app/appAvailabilityV2"] = {"data": {"id": "av"}}
        asc.gets["/v2/appAvailabilities/av/territoryAvailabilities"] = [
            {"attributes": {"available": True}, "relationships": {"territory": {"data": {"id": "USA"}}}},
            {"attributes": {"available": False}, "relationships": {"territory": {"data": {"id": "CHN"}}}},
            {"attributes": {"available": True}, "relationships": {"territory": {"data": {"id": "NOR"}}}}]
        self.run_event(asc, apply=True)
        event = next(body for _, path, body in asc.writes("POST") if path == "/appEvents")["data"]
        self.assertEqual(event["attributes"]["territorySchedules"][0]["territories"], ["NOR", "USA"])

    def test_two_events_with_the_reference_name_stop_the_run(self):
        asc = self.fake(events=[remote_event(), {**remote_event(), "id": "ev2"}])
        with self.assertRaises(testflight.ListingError):
            self.run_event(asc, apply=False)

    def test_over_limit_text_stops_before_any_call(self):
        (self.root / "en-GB" / "name.txt").write_text("x" * 31, encoding="utf-8")
        asc = self.fake()
        with self.assertRaises(testflight.ListingError):
            self.run_event(asc, apply=True)
        self.assertEqual(asc.calls, [])


class EventReviewTests(EventTestCase):
    def test_add_to_review_uses_the_draft_with_the_version_and_never_submits(self):
        version = {"id": "v1", "attributes": {"versionString": "2.1.0",
                                              "appVersionState": "PREPARE_FOR_SUBMISSION"}}
        asc = self.synced_fake(
            versions=[version],
            submissions=[{"id": "other"}, {"id": "with-version"}],
            items={"other": [], "with-version": [
                {"relationships": {"appStoreVersion": {"data": {"id": "v1"}}}}]})
        code, out = self.run_event(asc, apply=True, add_to_review=True)
        self.assertEqual(code, 0)
        self.assertEqual(asc.writes(), [("POST", "/reviewSubmissionItems", {"data": {
            "type": "reviewSubmissionItems", "relationships": {
                "reviewSubmission": {"data": {"type": "reviewSubmissions", "id": "with-version"}},
                "appEvent": {"data": {"type": "appEvents", "id": "ev"}}}}})])
        self.assertIn("it is not sent", out)

    def test_add_to_review_creates_a_draft_when_none_is_open(self):
        asc = self.synced_fake()
        self.run_event(asc, apply=True, add_to_review=True)
        self.assertEqual([p for _, p, _ in asc.writes()], ["/reviewSubmissions", "/reviewSubmissionItems"])
        self.assertFalse(asc.writes("PATCH"))

    def test_add_to_review_is_idempotent(self):
        asc = self.synced_fake(submissions=[{"id": "s"}],
                               items={"s": [{"relationships": {"appEvent": {"data": {"id": "ev"}}}}]})
        _, out = self.run_event(asc, apply=True, add_to_review=True)
        self.assertEqual(asc.writes(), [])
        self.assertIn("already in draft submission s", out)


class EventTargetTests(EventTestCase):
    def test_target_is_the_in_app_event_group_with_its_limit_and_category(self):
        library = testflight.AssetLibrary(self.fake(), "app")
        target = testflight.event_media_target(library, "EVENT_CARD_ASSET")
        self.assertEqual((target["group"], target["limit"], target["category"]),
                         ("DEFAULT_PROFILE", 1, "CREATIVE_ASSETS"))
        self.assertEqual(set(target["specs"]), {"spec-card"})

    def test_a_type_without_an_in_app_event_limit_stops(self):
        library = testflight.AssetLibrary(self.fake(), "app")
        with self.assertRaises(testflight.ListingError):
            testflight.event_media_target(library, "PRODUCT_PAGE_HEADER_ASSET")

    def test_an_image_of_the_wrong_size_stops(self):
        (self.root / "ru" / "event-card.png").write_bytes(png(1920, 1200, b"wrong"))
        with self.assertRaises(testflight.ListingError):
            self.run_event(self.fake(), apply=False)


class CheckEventTests(EventTestCase):
    def check(self):
        return quiet(check_store_listing.check_in_app_event, self.root)[0]

    def test_the_fixture_passes(self):
        self.assertEqual(self.check(), [])

    def test_limits_units_and_prices(self):
        (self.root / "en-GB" / "short_description.txt").write_text("y" * 51, encoding="utf-8")
        (self.root / "ru" / "long_description.txt").write_text("Пакет за 99 руб", encoding="utf-8")
        (self.root / "en-US" / "long_description.txt").write_text("10 credits for $3.99", encoding="utf-8")
        found = "\n".join(self.check())
        self.assertIn("en-GB/short_description.txt: 51 characters (limit 50)", found)
        self.assertIn("ru/long_description.txt: names a price", found)
        self.assertIn("en-US/long_description.txt: calls the paid unit a credit or token", found)
        self.assertIn("en-US/long_description.txt: names a price", found)

    def test_settings_and_schedule_rules(self):
        write_event(self.root, config={**CONFIG, "badge": "MAJOR", "referenceName": "r" * 65,
                                       "publishDaysBefore": 15, "durationDays": 32,
                                       "releaseDay": "1 Dec", "deepLink": "recipe"})
        found = "\n".join(self.check())
        for key in ("badge", "referenceName", "publishDaysBefore", "durationDays", "releaseDay", "deepLink"):
            self.assertIn(key, found)

    def test_media_size_ratio_and_alpha(self):
        (self.root / "ru" / "event-card.png").write_bytes(png(1920, 1200))
        alpha = bytearray(png(*DETAILS))
        alpha[25] = 6  # RGBA
        (self.root / "ru" / "event-details.png").write_bytes(bytes(alpha))
        (self.root / "en-GB" / "event-card.png").unlink()
        found = "\n".join(self.check())
        self.assertIn("ru/event-card.png: 1920x1200", found)
        self.assertIn("ru/event-details.png: has an alpha channel", found)
        self.assertIn("en-GB/event-card.png: missing", found)
        self.assertIn("en-US/event-card.png: missing", found)

    def test_regional_variant_takes_its_languages_media(self):
        texts = {"es-ES": ("Recetas", "Cocina lo que caduca antes", "La nueva pestaña Receta."),
                 "es-MX": ("Recetas", "Cocina lo que caduca primero", "La nueva pestaña Receta.")}
        write_event(self.root, texts=texts, media=("es-ES",))
        self.assertEqual({n: p.parent.name for n, p in
                          check_store_listing.event_media(self.root, "es-MX").items()},
                         {"event-card.png": "es-ES", "event-details.png": "es-ES"})
        self.assertEqual(check_store_listing.event_media(self.root, "sv"), {})
        event = testflight.read_event(self.root)
        self.assertEqual(event["media"]["es-MX"]["EVENT_CARD_ASSET"]["label"],
                         "in-app-event/es-ES/event-card.png")
        self.assertEqual(self.check(), [])

    def test_unit_words_in_the_new_languages(self):
        for locale, text in (("ru", "10 créditos"), ("en-GB", "Guthaben aufladen"), ("en-US", "des jetons")):
            (self.root / locale / "long_description.txt").write_text(text, encoding="utf-8")
        found = "\n".join(self.check())
        for locale in ("ru", "en-GB", "en-US"):
            self.assertIn(f"{locale}/long_description.txt: calls the paid unit a credit or token", found)

    def test_the_repo_event_passes(self):
        self.assertEqual(quiet(check_store_listing.check_in_app_event)[0], [])


if __name__ == "__main__":
    unittest.main()
