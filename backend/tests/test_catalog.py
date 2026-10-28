import asyncio
import time

import httpx
from fastapi.testclient import TestClient
from test_home import AUTH, emby_handler

from app.config import Settings
from app.main import create_app

MOVIES = [
    {"id": "tt34890576", "type": "movie", "name": "Clayface", "releaseInfo": "2026",
     "poster": "https://image.tmdb.org/t/p/w500/mP4XfTSMBcJgUS9wJLL9xOg1Knv.jpg", "description": "Plot."},
    {"id": "kitsu:1", "type": "movie", "name": "Unsupported ID"},
    {"id": "tt1", "type": "movie", "name": "Odd Poster", "poster": "https://evil.example.com/x.jpg"},
]
SERIES = [{"id": "tt2531336", "type": "series", "name": "Lupin", "releaseInfo": "2021-"}]


def catalog_handler(state: dict):
    async def handle(request: httpx.Request) -> httpx.Response:
        if state.get("slow"):
            await asyncio.sleep(1)
        if state.get("down"):
            raise httpx.ConnectError("catalog down")
        url = str(request.url)
        state.setdefault("urls", []).append(url)
        if url == "https://catalog.example/catalog/movie/trending-movies.json":
            return httpx.Response(200, json={"metas": MOVIES})
        if url == "https://catalog.example/catalog/series/trending-series.json":
            return httpx.Response(200, json={"metas": SERIES})
        if url == "https://image.tmdb.org/t/p/w342/mP4XfTSMBcJgUS9wJLL9xOg1Knv.jpg":
            return httpx.Response(200, content=b"jpegbytes")
        return httpx.Response(404)
    return handle


def make_client(tmp_path, emby: dict, catalog: dict) -> TestClient:
    settings = Settings(api_token="secret", data_dir=tmp_path, emby_url="http://emby:8096", emby_api_key="k",
                        nuvio_catalog_url="https://catalog.example/", home_refresh_wait_seconds=0.3)
    return TestClient(create_app(settings, emby_transport=httpx.MockTransport(emby_handler(emby)),
                                 catalog_transport=httpx.MockTransport(catalog_handler(catalog))))


def test_trending_rows_open_nuvio_with_cached_posters(tmp_path):
    catalog = {}
    with make_client(tmp_path, {}, catalog) as c:
        rows = {r["id"]: r for r in c.get("/api/home", headers=AUTH).json()["rows"]}
        movie, odd = rows["trending_movies"]["items"]
        assert (movie["title"], movie["type"], movie["source"], movie["content_id"], movie["year"]) == (
            "Clayface", "Movie", "nuvio", "tt34890576", 2026)
        assert movie["image"] == "/api/catalog/poster/mP4XfTSMBcJgUS9wJLL9xOg1Knv.jpg"
        assert odd["image"] is None
        [show] = rows["trending_series"]["items"]
        assert (show["type"], show["content_id"], show["subtitle"]) == ("Series", "tt2531336", "2021")
        assert list(rows)[-2:] == ["trending_movies", "trending_series"]

        # Cached for catalog_cache_seconds, not refetched on every home request.
        fetched = len(catalog["urls"])
        c.get("/api/home", headers=AUTH)
        assert len(catalog["urls"]) == fetched

        assert c.get(movie["image"], headers=AUTH).content == b"jpegbytes"
        catalog["down"] = True
        assert c.get(movie["image"], headers=AUTH).content == b"jpegbytes"
        assert c.get(movie["image"]).status_code == 401
        assert c.get("/api/catalog/poster/..%2Fsecret.jpg", headers=AUTH).status_code in (404, 422)
        assert c.get("/api/catalog/poster/x.svg", headers=AUTH).status_code == 422


def test_slow_or_down_catalog_never_holds_up_emby(tmp_path):
    catalog = {"slow": True}
    with make_client(tmp_path, {}, catalog) as c:
        start = time.monotonic()
        body = c.get("/api/home", headers=AUTH).json()
        assert time.monotonic() - start < 0.8
        ids = [r["id"] for r in body["rows"]]
        assert "recent_movies" in ids and "trending_movies" not in ids and body["stale"] is False
        time.sleep(1.2)
        assert "trending_movies" in [r["id"] for r in c.get("/api/home", headers=AUTH).json()["rows"]]

    with make_client(tmp_path / "b", {}, {"down": True}) as c:
        body = c.get("/api/home", headers=AUTH).json()
    assert "recent_movies" in [r["id"] for r in body["rows"]] and body["stale"] is True
