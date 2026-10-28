import json
import time

import httpx
from fastapi.testclient import TestClient
from test_home import AUTH, emby_handler

from app import db
from app.config import Settings
from app.integrations.trakt import TOKEN_DEVICE, TOKEN_KEY
from app.main import create_app

FANART = "walter-r2.trakt.tv/images/shows/000/000/001/fanarts/medium/a1.jpg.webp"
THUMB = "walter-r2.trakt.tv/images/movies/000/000/002/thumbs/medium/b2.jpg.webp"
def days_ago(days: float) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime(time.time() - days * 86400))


PLAYBACK = [
    {"id": 6, "type": "movie", "progress": 50.0, "paused_at": days_ago(61),
     "movie": {"title": "Too Old For Nuvio", "ids": {"imdb": "tt1"}}},
    {"id": 7, "type": "episode", "progress": 25.0, "paused_at": days_ago(30),
     "episode": {"season": 1, "number": 1, "title": "Come Home Year", "runtime": 60, "ids": {"trakt": 1}},
     "show": {"title": "Below", "year": 2026, "ids": {"imdb": "tt100", "tmdb": 5}, "images": {"fanart": [FANART]}}},
    {"id": 8, "type": "movie", "progress": 1.0, "paused_at": days_ago(1),
     "movie": {"title": "The Dark Knight", "year": 2008, "runtime": 152, "ids": {"imdb": "tt0468569"},
               "images": {"thumb": [THUMB], "fanart": ["evil.example.com/images/x.jpg"]}}},
    {"id": 9, "type": "movie", "progress": 5.0, "paused_at": days_ago(2), "movie": {"title": "No IDs", "ids": {}}},
]
WATCHLIST = [
    {"id": 3, "type": "show", "show": {"title": "Severance", "year": 2022, "ids": {"tmdb": 95396}}},
    {"id": 4, "type": "season", "season": {"number": 1}},
]


def trakt_handler(state: dict):
    def handle(request: httpx.Request) -> httpx.Response:
        if state.get("down"):
            raise httpx.ConnectError("trakt down")
        url = str(request.url)
        if url == "https://auth.trakt.tv/oauth/token":
            state["refreshed"] = json.loads(request.content)
            return httpx.Response(200, json={"access_token": "new", "refresh_token": "r2",
                                             "created_at": int(time.time()), "expires_in": 604800})
        if url.startswith("https://walter-r2.trakt.tv/images/"):
            return httpx.Response(200, content=b"webp")
        assert request.headers["trakt-api-key"] == "cid"
        state["bearer"] = request.headers["authorization"]
        if request.url.path == "/sync/playback":
            return httpx.Response(200, json=PLAYBACK)
        if request.url.path == "/sync/watchlist":
            return httpx.Response(200, json=WATCHLIST)
        return httpx.Response(404)
    return handle


def make_client(tmp_path, emby: dict, trakt: dict, token_age: float | None = 0) -> TestClient:
    settings = Settings(api_token="secret", data_dir=tmp_path, emby_url="http://emby:8096", emby_api_key="k",
                        trakt_client_id="cid", home_cache_seconds=0)
    if token_age is not None:
        conn = db.connect(settings.db_path)
        db.pref_put(conn, TOKEN_DEVICE, TOKEN_KEY, json.dumps({
            "access_token": "old", "refresh_token": "r1", "created_at": time.time() - token_age, "expires_in": 604800}))
        conn.close()
    return TestClient(create_app(settings, httpx.MockTransport(emby_handler(emby)), httpx.MockTransport(trakt_handler(trakt))))


def rows_of(body: dict) -> dict:
    return {r["id"]: r for r in body["rows"]}


def test_nuvio_rows_follow_emby_continue_watching(tmp_path):
    trakt = {}
    with make_client(tmp_path, {}, trakt) as c:
        body = c.get("/api/home", headers=AUTH).json()
    assert [r["id"] for r in body["rows"]] == [
        "continue_watching", "nuvio_continue", "trakt_watchlist", "playlists", "recent_movies", "recent_episodes"]
    assert trakt["bearer"] == "Bearer old" and "refreshed" not in trakt

    movie, episode = rows_of(body)["nuvio_continue"]["items"]
    assert (movie["title"], movie["source"], movie["type"], movie["content_id"]) == (
        "The Dark Knight", "nuvio", "Movie", "tt0468569")
    assert movie["progress"] == 0.01 and movie["runtime_minutes"] == 152
    assert movie["image"] == f"/api/trakt/image?path={THUMB}"
    assert movie["backdrop"] is None
    assert (episode["content_id"], episode["season"], episode["episode"]) == ("tt100", 1, 1)
    assert episode["subtitle"] == "S1 E1 · Come Home Year"
    assert episode["image"] == episode["backdrop"] == f"/api/trakt/image?path={FANART}"

    [show] = rows_of(body)["trakt_watchlist"]["items"]
    assert (show["type"], show["content_id"], show["progress"]) == ("Series", "tmdb:95396", None)


def test_each_source_fails_on_its_own(tmp_path):
    with make_client(tmp_path, {"down": True}, {}) as c:
        body = c.get("/api/home", headers=AUTH).json()
    assert list(rows_of(body)) == ["nuvio_continue", "trakt_watchlist"]
    assert body["stale"] is True and body["server_id"] is None

    with make_client(tmp_path / "b", {}, {"down": True}) as c:
        body = c.get("/api/home", headers=AUTH).json()
    assert "nuvio_continue" not in rows_of(body) and "continue_watching" in rows_of(body)
    assert body["stale"] is True and body["server_id"] == "srv1"


def test_no_trakt_rows_until_signed_in(tmp_path):
    with make_client(tmp_path, {}, {}, token_age=None) as c:
        body = c.get("/api/home", headers=AUTH).json()
    assert "nuvio_continue" not in rows_of(body) and body["stale"] is False


def test_expiring_token_is_refreshed_and_saved(tmp_path):
    trakt = {}
    with make_client(tmp_path, {}, trakt, token_age=6.5 * 86400) as c:
        c.get("/api/home", headers=AUTH)
    assert trakt["refreshed"]["refresh_token"] == "r1" and "client_secret" not in trakt["refreshed"]
    assert trakt["bearer"] == "Bearer new"
    conn = db.connect(tmp_path / "homebase.db")
    assert json.loads(db.pref_get(conn, TOKEN_DEVICE, TOKEN_KEY))["refresh_token"] == "r2"
    conn.close()


def test_trakt_images_are_cached_and_restricted(tmp_path):
    trakt = {}
    with make_client(tmp_path, {}, trakt) as c:
        assert c.get(f"/api/trakt/image?path={THUMB}", headers=AUTH).content == b"webp"
        trakt["down"] = True
        assert c.get(f"/api/trakt/image?path={THUMB}&width=600", headers=AUTH).content == b"webp"
        assert c.get(f"/api/trakt/image?path={THUMB}").status_code == 401
        for bad in ("evil.example.com/images/x.jpg", "trakt.tv.evil.com/images/x.jpg",
                    "walter-r2.trakt.tv/images/../../etc/passwd", "127.0.0.1/images/x.jpg"):
            assert c.get("/api/trakt/image", params={"path": bad}, headers=AUTH).status_code == 422, bad
