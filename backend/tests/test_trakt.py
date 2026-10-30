import asyncio
import json
import re
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

def watched(trakt: int, title: str, days: float, imdb: str | None = None) -> dict:
    return {"plays": 3, "last_watched_at": days_ago(days), "reset_at": None,
            "show": {"title": title, "year": 2017, "ids": {"trakt": trakt, "imdb": imdb, "tmdb": trakt + 1000},
                     "images": {"fanart": [FANART]}}}


# Filler shows were watched longest ago (among recent ones), so the last two fall outside the request bound.
WATCHED = [watched(20 + n, f"Filler {n}", 40 + n) for n in range(8)] + [
    watched(14, "Abandoned Long Ago", 90),
    watched(12, "Below", 20, "tt100"),
    watched(13, "Finished Show", 2, "tt13"),
    watched(11, "Malcolm in the Middle", 3, "tt0212671"),
    watched(10, "The Marvelous Mrs. Maisel", 0.5, "tt5788792"),
]
PROGRESS = {
    "10": {"aired": 26, "completed": 12, "next_episode": {
        "season": 2, "number": 3, "title": "The Punishment Room", "runtime": 55, "ids": {"trakt": 103}}},
    "11": {"aired": 151, "completed": 3, "next_episode": {"season": 1, "number": 4, "title": "Shame", "ids": {}}},
    "12": {"aired": 8, "completed": 0, "next_episode": {"season": 1, "number": 1, "title": "Come Home Year"}},
    "13": {"aired": 10, "completed": 10, "next_episode": None},
}
WATCHLIST = [
    {"id": 3, "type": "show", "show": {"title": "Severance", "year": 2022, "ids": {"tmdb": 95396}}},
    {"id": 4, "type": "season", "season": {"number": 1}},
    {"id": 5, "type": "movie", "movie": {"title": "Heat", "year": 1995, "ids": {"imdb": "tt0113277"}}},
]


def trakt_handler(state: dict):
    async def handle(request: httpx.Request) -> httpx.Response:
        if state.get("down"):
            raise httpx.ConnectError("trakt down")
        if state.get("hang"):
            await asyncio.sleep(0.5)
            raise httpx.ReadTimeout(f"timed out reading {request.url}?token=old")
        if state.get("status"):
            return httpx.Response(state["status"], request=request, text="upstream secret body")
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
        if request.url.path == "/sync/watched/shows":
            return httpx.Response(200, json=WATCHED)
        if m := re.fullmatch(r"/shows/(\d+)/progress/watched", request.url.path):
            state.setdefault("progress", []).append(m[1])
            if state.get("progress_status"):
                return httpx.Response(state["progress_status"], request=request, text="upstream secret body")
            return httpx.Response(200, json=PROGRESS.get(m[1], {"aired": 1, "completed": 1, "next_episode": None}))
        return httpx.Response(404)
    return handle


def make_client(tmp_path, emby: dict, trakt: dict, token_age: float | None = 0, expires_in: int = 604800,
                **settings_overrides) -> TestClient:
    settings = Settings(api_token="secret", data_dir=tmp_path, emby_url="http://emby:8096", emby_api_key="k",
                        trakt_client_id="cid", home_cache_seconds=0, **settings_overrides)
    if token_age is not None:
        conn = db.connect(settings.db_path)
        db.pref_put(conn, TOKEN_DEVICE, TOKEN_KEY, json.dumps({
            "access_token": "old", "refresh_token": "r1", "created_at": time.time() - token_age,
            "expires_in": expires_in}))
        conn.close()
    return TestClient(create_app(settings, httpx.MockTransport(emby_handler(emby)), httpx.MockTransport(trakt_handler(trakt))))


def rows_of(body: dict) -> dict:
    return {r["id"]: r for r in body["rows"]}


def test_nuvio_rows_follow_emby_continue_watching(tmp_path):
    trakt = {}
    with make_client(tmp_path, {}, trakt) as c:
        body = c.get("/api/home", headers=AUTH).json()
    assert [r["id"] for r in body["rows"]] == [
        "continue_watching", "nuvio_continue", "trakt_watchlist_movies", "trakt_watchlist_shows",
        "playlists", "recent_movies", "recent_episodes"]
    assert trakt["bearer"] == "Bearer old" and "refreshed" not in trakt

    maisel, movie, malcolm, episode = rows_of(body)["nuvio_continue"]["items"]
    assert (maisel["type"], maisel["source"], maisel["content_id"], maisel["season"], maisel["episode"]) == (
        "Episode", "nuvio", "tt5788792", 2, 3)
    assert (maisel["title"], maisel["subtitle"], maisel["runtime_minutes"], maisel["progress"]) == (
        "The Marvelous Mrs. Maisel", "S2 E3 · The Punishment Room", 55, None)
    assert maisel["image"] == maisel["backdrop"] == f"/api/trakt/image?path={FANART}"
    assert (malcolm["content_id"], malcolm["season"], malcolm["episode"]) == ("tt0212671", 1, 4)
    # Completed and long-abandoned shows are skipped; only the 10 most recently watched shows are looked up.
    assert sorted(trakt["progress"]) == sorted(["10", "11", "13", "12"] + [str(20 + n) for n in range(6)])
    assert (movie["title"], movie["source"], movie["type"], movie["content_id"]) == (
        "The Dark Knight", "nuvio", "Movie", "tt0468569")
    assert movie["progress"] == 0.01 and movie["runtime_minutes"] == 152
    assert movie["image"] == f"/api/trakt/image?path={THUMB}"
    assert movie["backdrop"] is None
    assert (episode["content_id"], episode["season"], episode["episode"]) == ("tt100", 1, 1)
    # Below's next-up S1 E1 is the same episode as its paused playback, which keeps its progress.
    assert episode["subtitle"] == "S1 E1 · Come Home Year" and episode["progress"] == 0.25
    assert episode["image"] == episode["backdrop"] == f"/api/trakt/image?path={FANART}"

    [show] = rows_of(body)["trakt_watchlist_shows"]["items"]
    assert (show["type"], show["content_id"], show["progress"]) == ("Series", "tmdb:95396", None)
    [film] = rows_of(body)["trakt_watchlist_movies"]["items"]
    assert (film["type"], film["content_id"]) == ("Movie", "tt0113277")


def test_each_source_fails_on_its_own(tmp_path):
    with make_client(tmp_path, {"down": True}, {}) as c:
        body = c.get("/api/home", headers=AUTH).json()
    assert list(rows_of(body)) == ["nuvio_continue", "trakt_watchlist_movies", "trakt_watchlist_shows"]
    assert body["stale"] is True and body["server_id"] is None

    with make_client(tmp_path / "b", {}, {"down": True}) as c:
        body = c.get("/api/home", headers=AUTH).json()
    assert "nuvio_continue" not in rows_of(body) and "continue_watching" in rows_of(body)
    assert body["stale"] is True and body["server_id"] == "srv1"


def test_failed_next_up_lookup_fails_only_trakt_with_sanitized_error(tmp_path):
    with make_client(tmp_path, {}, {"progress_status": 503}) as c:
        body = c.get("/api/home", headers=AUTH).json()
        health = c.get("/health", headers=AUTH)
    assert "nuvio_continue" not in rows_of(body) and "continue_watching" in rows_of(body)
    assert body["stale"] is True
    assert health.json()["sources"]["trakt"]["error"] == "HTTP 503"
    assert_redacted(health.text)


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


def test_day_long_token_is_refreshed_halfway_not_on_every_call(tmp_path):
    trakt = {}
    with make_client(tmp_path, {}, trakt, token_age=0, expires_in=86400) as c:
        c.get("/api/home", headers=AUTH)
    assert "refreshed" not in trakt and trakt["bearer"] == "Bearer old"

    with make_client(tmp_path / "b", {}, trakt, token_age=13 * 3600, expires_in=86400) as c:
        c.get("/api/home", headers=AUTH)
    assert trakt["refreshed"]["refresh_token"] == "r1" and trakt["bearer"] == "Bearer new"


def assert_redacted(text: str) -> None:
    for secret in ("api.trakt.tv", "token=old", "Bearer", "upstream secret body", "Traceback"):
        assert secret not in text, secret


def test_failed_trakt_refresh_serves_cache_as_stale_with_sanitized_diagnostics(tmp_path):
    trakt = {}
    with make_client(tmp_path, {}, trakt) as c:
        first = c.get("/api/home", headers=AUTH).json()
        trakt["status"] = 500
        second = c.get("/api/home", headers=AUTH).json()
        health = c.get("/health", headers=AUTH)
        public = c.get("/health").json()
    assert second["stale"] is True and second["rows"] == first["rows"]
    status = health.json()["sources"]["trakt"]
    assert status["stale"] is True and status["error"] == "HTTP 500" and status["signed_in"] is True
    assert isinstance(status["cache_age_seconds"], int) and status["refreshing"] is False
    assert health.json()["sources"]["emby"]["stale"] is False
    assert_redacted(health.text)
    assert "sources" not in public and public["authenticated"] is False


def test_timed_out_trakt_refresh_marks_cache_stale(tmp_path):
    trakt = {}
    with make_client(tmp_path, {}, trakt, home_refresh_wait_seconds=0.1) as c:
        first = c.get("/api/home", headers=AUTH).json()
        trakt["hang"] = True
        # The first slow refresh serves the last copy as usual.
        assert c.get("/api/home", headers=AUTH).json()["stale"] is False
        time.sleep(0.8)
        # That refresh timed out, so the copy served while the next one runs is stale.
        third = c.get("/api/home", headers=AUTH).json()
        health = c.get("/health", headers=AUTH)
        public = c.get("/health").json()
        time.sleep(0.6)
    assert third["stale"] is True and third["rows"] == first["rows"]
    status = health.json()["sources"]["trakt"]
    assert status["stale"] is True and status["error"] == "timed out" and status["refreshing"] is True
    assert status["cache_age_seconds"] >= 0
    assert_redacted(health.text)
    assert "sources" not in public


def test_502_detail_is_sanitized(tmp_path):
    with make_client(tmp_path, {"down": True}, {"status": 401}) as c:
        r = c.get("/api/home", headers=AUTH)
    assert r.status_code == 502 and r.json()["detail"] == "emby: ConnectError; trakt: HTTP 401"
    assert_redacted(r.text)


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
