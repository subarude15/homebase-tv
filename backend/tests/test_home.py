import asyncio
import time

import httpx
from fastapi.testclient import TestClient

from app.config import Settings
from app.integrations.emby import episode_name
from app.main import create_app

AUTH = {"Authorization": "Bearer secret"}

EPISODE = {
    "Id": "ep1", "Type": "Episode", "Name": "Dessert Week", "SeriesName": "Bake Off",
    "ParentIndexNumber": 7, "IndexNumber": 7, "RunTimeTicks": 3_000_000_000 * 12,
    "UserData": {"PlayedPercentage": 38.0}, "ImageTags": {"Primary": "abc"},
    "ParentBackdropItemId": "series1", "ParentBackdropImageTags": ["bd1"],
}
MOVIE = {"Id": "mv1", "Type": "Movie", "Name": "Step Brothers", "ProductionYear": 2008, "ImageTags": {"Primary": "p1", "Thumb": "t1"}}
PLAYLIST = {"Id": "pl1", "Type": "Playlist", "Name": "90s shit", "ChildCount": 2079, "ImageTags": {"Primary": "pp"}}
UNMATCHED_EPISODE = {"Id": "ep2", "Type": "Episode", "Name": "raw.file.name", "SeriesName": "SEAL Team",
                     "SeriesId": "s2", "SeriesPrimaryImageTag": "sp", "ImageTags": {}}


def emby_handler(state: dict):
    def handle(request: httpx.Request) -> httpx.Response:
        if state.get("down"):
            raise httpx.ConnectError("emby down")
        path = request.url.path
        if path == "/emby/System/Info/Public":
            return httpx.Response(200, json={"Id": "srv1"})
        if path == "/emby/Users":
            return httpx.Response(200, json=[{"Id": "u1", "Name": "banditoburrito"}])
        if path.endswith("/Items/Resume"):
            return httpx.Response(200, json={"Items": [EPISODE]})
        if path.endswith("/Items/Latest"):
            types = request.url.params["IncludeItemTypes"]
            return httpx.Response(200, json=[MOVIE] if types == "Movie" else [UNMATCHED_EPISODE])
        if path.endswith("/Items"):
            if request.url.params.get("IncludeItemTypes") == "Playlist":
                return httpx.Response(200, json={"Items": [PLAYLIST]})
            if request.url.params.get("Filters") == "IsFavorite":
                return httpx.Response(200, json={"Items": state.get("favorites", [])})
            return httpx.Response(200, json={"Items": []})
        if "/Images/" in path:
            return httpx.Response(200, content=b"jpegbytes", headers={"content-type": "image/jpeg"})
        return httpx.Response(404)
    return handle


def make_client(tmp_path, state: dict, cache_seconds: int = 60) -> TestClient:
    settings = Settings(api_token="secret", data_dir=tmp_path, emby_url="http://emby:8096",
                        emby_api_key="k", home_cache_seconds=cache_seconds)
    return TestClient(create_app(settings, emby_transport=httpx.MockTransport(emby_handler(state))))


def test_home_requires_token(tmp_path):
    with make_client(tmp_path, {}) as c:
        assert c.get("/api/home").status_code == 401


def test_home_rows_are_normalized_and_empty_rows_dropped(tmp_path):
    with make_client(tmp_path, {}) as c:
        body = c.get("/api/home", headers=AUTH).json()
    rows = {r["id"]: r for r in body["rows"]}
    assert list(rows) == ["continue_watching", "playlists", "recent_movies", "recent_episodes"]
    assert body["stale"] is False
    assert body["server_id"] == "srv1"

    ep = rows["continue_watching"]["items"][0]
    assert ep["title"] == "Bake Off"
    assert ep["subtitle"] == "S7 E7 · Dessert Week"
    assert (ep["season"], ep["episode"]) == (7, 7)
    assert ep["progress"] == 0.38
    assert ep["runtime_minutes"] == 60
    assert ep["image"] == "/api/image/ep1/Primary?tag=abc"
    assert ep["backdrop"] == "/api/image/series1/Backdrop?tag=bd1"

    assert rows["playlists"]["items"][0]["subtitle"] == "2079 items"
    assert rows["recent_movies"]["items"][0]["image"] == "/api/image/mv1/Primary?tag=p1"
    # Episodes without their own art fall back to the series poster.
    assert rows["recent_episodes"]["items"][0]["image"] == "/api/image/s2/Primary?tag=sp"


def test_favorites_are_split_into_movies_and_shows(tmp_path):
    series = {"Id": "se1", "Type": "Series", "Name": "Bluey", "ImageTags": {"Primary": "b"}}
    with make_client(tmp_path, {"favorites": [MOVIE, series, UNMATCHED_EPISODE]}) as c:
        rows = {r["id"]: r for r in c.get("/api/home", headers=AUTH).json()["rows"]}
    assert [i["id"] for i in rows["favorite_movies"]["items"]] == ["mv1"]
    assert [i["id"] for i in rows["favorite_shows"]["items"]] == ["se1", "ep2"]


def test_episode_name_drops_filename_prefix():
    assert episode_name("The Great British Bake Off - S07E07 - Dessert Week") == "Dessert Week"
    assert episode_name("Who Am I?") == "Who Am I?"
    assert episode_name("raw.file.name") == "raw.file.name"
    assert episode_name("Show S01E02") == "Show S01E02"


def test_home_serves_stale_cache_when_emby_is_down(tmp_path):
    state = {}
    with make_client(tmp_path, state, cache_seconds=0) as c:
        first = c.get("/api/home", headers=AUTH).json()
        state["down"] = True
        second = c.get("/api/home", headers=AUTH).json()
    assert second["stale"] is True
    assert second["rows"] == first["rows"]


def test_slow_refresh_serves_last_copy_and_finishes_in_background(tmp_path):
    state = {}
    sync_handler = emby_handler(state)

    async def handle(request: httpx.Request) -> httpx.Response:
        if state.get("slow"):
            await asyncio.sleep(1)
            state["refreshed"] = True
        return sync_handler(request)

    settings = Settings(api_token="secret", data_dir=tmp_path, emby_url="http://emby:8096", emby_api_key="k",
                        home_cache_seconds=0, home_refresh_wait_seconds=0.3)
    with TestClient(create_app(settings, emby_transport=httpx.MockTransport(handle))) as c:
        first = c.get("/api/home", headers=AUTH).json()
        state["slow"] = True
        start = time.monotonic()
        second = c.get("/api/home", headers=AUTH).json()
        assert 0.25 < time.monotonic() - start < 0.8
        assert second["rows"] == first["rows"] and second["stale"] is False
        # Another request during the same refresh doesn't wait again.
        start = time.monotonic()
        c.get("/api/home", headers=AUTH)
        assert time.monotonic() - start < 0.2
        time.sleep(1.5)
        assert state.get("refreshed")


def test_home_without_cache_when_emby_is_down_is_502(tmp_path):
    with make_client(tmp_path, {"down": True}) as c:
        assert c.get("/api/home", headers=AUTH).status_code == 502


def test_image_proxy(tmp_path):
    with make_client(tmp_path, {}) as c:
        ok = c.get("/api/image/ep1/Primary?tag=abc", headers=AUTH)
        assert ok.status_code == 200 and ok.content == b"jpegbytes"
        real_tag = "cc18ac3e7b64b488ee1fb3f940269056_639133322400000000_4e816f77ad9782edf0852d49fae4e6a1"
        assert c.get(f"/api/image/16425707/Primary?tag={real_tag}", headers=AUTH).status_code == 200
        assert c.get("/api/image/ep1/Primary?tag=abc").status_code == 401
        assert c.get("/api/image/..%2Fetc/Primary", headers=AUTH).status_code in (404, 422)
        assert c.get("/api/image/ep1/Secret", headers=AUTH).status_code == 422
