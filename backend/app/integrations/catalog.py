"""Trending rows from Nuvio's official TMDB catalog addon (Stremio addon protocol, no key needed)."""
import asyncio
import re

import httpx

from .emby import MediaItem, Row

# (row id, row title, addon catalog path)
CATALOGS = [
    ("trending_movies", "Trending Movies", "movie/trending-movies"),
    ("trending_series", "Trending Series", "series/trending-series"),
]
TMDB_POSTER = re.compile(r"^https://image\.tmdb\.org/t/p/[a-z0-9]+/([A-Za-z0-9]+\.(?:jpg|png))$")
POSTER_FILE = re.compile(r"^[A-Za-z0-9]+\.(?:jpg|png)$")
# Poster cards are 124 dp wide; w342 is the smallest TMDB size that stays sharp on a 1080p TV.
POSTER_URL = "https://image.tmdb.org/t/p/w342/"


def catalog_item(m: dict) -> MediaItem | None:
    cid = m.get("id") or ""
    if not cid.startswith(("tt", "tmdb:")) or m.get("type") not in ("movie", "series"):
        return None
    year = (m.get("releaseInfo") or "")[:4]
    poster = TMDB_POSTER.match(m.get("poster") or "")
    return MediaItem(
        id=f"catalog-{cid}", type="Movie" if m["type"] == "movie" else "Series", source="nuvio", content_id=cid,
        title=m.get("name") or "", subtitle=year, year=int(year) if year.isdigit() else None,
        overview=m.get("description") or "", image=f"/api/catalog/poster/{poster[1]}" if poster else None,
    )


class NuvioCatalog:
    def __init__(self, base_url: str, transport: httpx.AsyncBaseTransport | None = None):
        self.base_url = base_url.rstrip("/")
        self.http = httpx.AsyncClient(headers={"User-Agent": "HomebaseTV"}, timeout=10, transport=transport)

    async def close(self) -> None:
        await self.http.aclose()

    async def _metas(self, path: str) -> list[dict]:
        r = await self.http.get(f"{self.base_url}/catalog/{path}.json")
        r.raise_for_status()
        return r.json().get("metas") or []

    async def rows(self) -> list[Row]:
        metas = await asyncio.gather(*(self._metas(path) for _, _, path in CATALOGS))
        rows = [Row(id=rid, title=title, shape="poster", items=[i for m in ms if (i := catalog_item(m))])
                for (rid, title, _), ms in zip(CATALOGS, metas)]
        return [r for r in rows if r.items]

    async def poster(self, file: str) -> httpx.Response:
        return await self.http.get(POSTER_URL + file)
