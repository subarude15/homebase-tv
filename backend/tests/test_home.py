import httpx
from fastapi.testclient import TestClient

from app.config import Settings
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

    ep = rows["continue_watching"]["items"][0]
    assert ep["title"] == "Bake Off"
    assert ep["subtitle"] == "S7:E7 · Dessert Week"
    assert ep["progress"] == 0.38
    assert ep["runtime_minutes"] == 60
    assert ep["image"] == "/api/image/ep1/Primary?tag=abc"
    assert ep["backdrop"] == "/api/image/series1/Backdrop?tag=bd1"

    assert rows["playlists"]["items"][0]["subtitle"] == "2079 items"
    assert rows["recent_movies"]["items"][0]["image"] == "/api/image/mv1/Primary?tag=p1"
    # Episodes without their own art fall back to the series poster.
    assert rows["recent_episodes"]["items"][0]["image"] == "/api/image/s2/Primary?tag=sp"


def test_home_serves_stale_cache_when_emby_is_down(tmp_path):
    state = {}
    with make_client(tmp_path, state, cache_seconds=0) as c:
        first = c.get("/api/home", headers=AUTH).json()
        state["down"] = True
        second = c.get("/api/home", headers=AUTH).json()
    assert second["stale"] is True
    assert second["rows"] == first["rows"]


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
