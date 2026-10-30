"""Nuvio's watch progress and watchlist via Trakt. One-time sign-in: python -m app.integrations.trakt"""
import asyncio
import json
import re
import sqlite3
import sys
import time

import httpx

from .. import db
from ..config import Settings
from .emby import MediaItem, Row

AUTH_URL = "https://auth.trakt.tv"
API_URL = "https://api.trakt.tv"
TOKEN_DEVICE, TOKEN_KEY = "backend", "trakt_token"
# Refresh a day early, or halfway through tokens shorter than two days (Trakt has issued 24-hour ones).
REFRESH_MARGIN = 86_400
# Nuvio's default Continue Watching window for Trakt progress.
CONTINUE_WATCHING_DAYS = 60
# Watched progress is one request per show, so only the most recently watched shows get a next-up episode.
NEXT_UP_SHOWS = 10
IMAGE_PATH = re.compile(r"^[a-z0-9-]+(\.[a-z0-9-]+)*\.trakt\.tv/images/[A-Za-z0-9/_-]+(\.[A-Za-z0-9]+)+$")


def _image(media: dict, *kinds: str) -> str | None:
    images = media.get("images") or {}
    for kind in kinds:
        for path in images.get(kind) or []:
            if IMAGE_PATH.match(path):
                return f"/api/trakt/image?path={path}"
    return None


def _content_id(ids: dict) -> str | None:
    if ids.get("imdb"):
        return ids["imdb"]
    return f"tmdb:{ids['tmdb']}" if ids.get("tmdb") else None


def _year(m: dict) -> str:
    return str(m["year"]) if m.get("year") else ""


def playback_item(p: dict) -> MediaItem | None:
    progress = (p.get("progress") or 0) / 100 or None
    if p.get("type") == "movie":
        m = p["movie"]
        cid = _content_id(m.get("ids") or {})
        if not cid:
            return None
        return MediaItem(
            id=f"trakt-{p['id']}", type="Movie", source="nuvio", content_id=cid, title=m.get("title") or "",
            subtitle=_year(m), year=m.get("year"), overview=m.get("overview") or "", runtime_minutes=m.get("runtime"),
            progress=progress, image=_image(m, "thumb", "fanart", "poster"), backdrop=_image(m, "fanart"),
        )
    if p.get("type") == "episode":
        e, s = p["episode"], p["show"]
        cid = _content_id(s.get("ids") or {})
        if not cid:
            return None
        number = f"S{e.get('season')} E{e.get('number')}"
        return MediaItem(
            id=f"trakt-{p['id']}", type="Episode", source="nuvio", content_id=cid, title=s.get("title") or "",
            subtitle=" · ".join(x for x in (number, e.get("title") or "") if x), season=e.get("season"),
            episode=e.get("number"), year=s.get("year"), overview=e.get("overview") or "",
            runtime_minutes=e.get("runtime"), progress=progress,
            image=_image(e, "screenshot") or _image(s, "thumb", "fanart", "poster"), backdrop=_image(s, "fanart"),
        )
    return None


def next_up_item(watched: dict, progress: dict) -> MediaItem | None:
    """The next unwatched aired episode of a show from Trakt's watched progress, or None once it's completed."""
    s, e = watched["show"], progress.get("next_episode")
    cid = _content_id(s.get("ids") or {})
    if not cid or not e or e.get("season") is None or e.get("number") is None:
        return None
    number = f"S{e['season']} E{e['number']}"
    return MediaItem(
        id=f"trakt-next-{cid}-{e['season']}-{e['number']}", type="Episode", source="nuvio", content_id=cid,
        title=s.get("title") or "", subtitle=" · ".join(x for x in (number, e.get("title") or "") if x),
        season=e["season"], episode=e["number"], year=s.get("year"), overview=e.get("overview") or "",
        runtime_minutes=e.get("runtime"),
        image=_image(e, "screenshot") or _image(s, "thumb", "fanart", "poster"), backdrop=_image(s, "fanart"),
    )


def watchlist_item(w: dict) -> MediaItem | None:
    kind = w.get("type")
    if kind not in ("movie", "show"):
        return None
    m = w[kind]
    cid = _content_id(m.get("ids") or {})
    if not cid:
        return None
    return MediaItem(
        id=f"trakt-{w['id']}", type="Movie" if kind == "movie" else "Series", source="nuvio", content_id=cid,
        title=m.get("title") or "", subtitle=_year(m), year=m.get("year"), overview=m.get("overview") or "",
        runtime_minutes=m.get("runtime") if kind == "movie" else None,
        image=_image(m, "poster"), backdrop=_image(m, "fanart"),
    )


class TraktClient:
    def __init__(self, client_id: str, conn: sqlite3.Connection, transport: httpx.AsyncBaseTransport | None = None):
        self.client_id = client_id
        self.conn = conn
        self.http = httpx.AsyncClient(headers={"User-Agent": "HomebaseTV"}, timeout=10, transport=transport)
        self._refresh_lock = asyncio.Lock()

    async def close(self) -> None:
        await self.http.aclose()

    def token(self) -> dict | None:
        value = db.pref_get(self.conn, TOKEN_DEVICE, TOKEN_KEY)
        return json.loads(value) if value else None

    def _save(self, token: dict) -> None:
        db.pref_put(self.conn, TOKEN_DEVICE, TOKEN_KEY, json.dumps(token))

    async def _access_token(self) -> str:
        async with self._refresh_lock:
            t = self.token()
            if t is None:
                raise LookupError("Trakt is not signed in")
            if time.time() > t["created_at"] + t["expires_in"] - min(REFRESH_MARGIN, t["expires_in"] / 2):
                r = await self.http.post(f"{AUTH_URL}/oauth/token", json={
                    "refresh_token": t["refresh_token"], "client_id": self.client_id,
                    "grant_type": "refresh_token", "redirect_uri": "urn:ietf:wg:oauth:2.0:oob",
                })
                r.raise_for_status()
                t = r.json()
                self._save(t)
            return t["access_token"]

    async def _get(self, path: str, **params):
        headers = {"trakt-api-version": "2", "trakt-api-key": self.client_id,
                   "Authorization": f"Bearer {await self._access_token()}"}
        r = await self.http.get(API_URL + path, params=params, headers=headers)
        r.raise_for_status()
        return r.json()

    async def _next_up(self, cutoff: str) -> list[tuple[str, MediaItem]]:
        watched = await self._get("/sync/watched/shows", extended="full,noseasons")
        recent = sorted((w for w in watched if (w.get("last_watched_at") or "") >= cutoff
                         and (w.get("show") or {}).get("ids", {}).get("trakt")),
                        key=lambda w: w["last_watched_at"], reverse=True)[:NEXT_UP_SHOWS]
        progress = await asyncio.gather(*(
            self._get(f"/shows/{w['show']['ids']['trakt']}/progress/watched", extended="full",
                      hidden="false", specials="false", count_specials="false") for w in recent))
        return [(w["last_watched_at"], i) for w, p in zip(recent, progress) if (i := next_up_item(w, p))]

    async def home_rows(self) -> list[Row]:
        # Nuvio only resumes Trakt progress from its Continue Watching window, so older items would restart at 0.
        cutoff = time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(time.time() - CONTINUE_WATCHING_DAYS * 86400))
        playback, watchlist, next_up = await asyncio.gather(
            self._get("/sync/playback", extended="full", limit=20),
            self._get("/sync/watchlist", extended="full", page=1, limit=100),
            self._next_up(cutoff),
        )
        watchlist = [i for w in watchlist if (i := watchlist_item(w))]
        paused = [(p["paused_at"], i) for p in playback
                  if (p.get("paused_at") or "") >= cutoff and (i := playback_item(p))]
        # Paused playback comes first so it wins the dedupe (it carries progress); the sort is stable on ties.
        continuing, seen = [], set()
        for t in paused + next_up:
            if (key := (t[1].content_id, t[1].season, t[1].episode)) not in seen:
                seen.add(key)
                continuing.append(t)
        continuing.sort(key=lambda t: t[0], reverse=True)
        rows = [
            Row(id="nuvio_continue", title="Continue Watching in Nuvio", shape="landscape",
                items=[i for _, i in continuing]),
            Row(id="trakt_watchlist_movies", title="My Watchlist", shape="poster",
                items=[i for i in watchlist if i.type == "Movie"]),
            Row(id="trakt_watchlist_shows", title="My Watchlist", shape="poster",
                items=[i for i in watchlist if i.type == "Series"]),
        ]
        return [r for r in rows if r.items]

    async def image(self, path: str) -> httpx.Response:
        return await self.http.get(f"https://{path}")

    async def device_login(self) -> bool:
        r = await self.http.post(f"{AUTH_URL}/oauth/device/code", json={"client_id": self.client_id})
        r.raise_for_status()
        code = r.json()
        print(f"On your phone or computer, go to {code['verification_url']} and enter: {code['user_code']}", flush=True)
        interval = code["interval"]
        deadline = time.time() + code["expires_in"]
        while time.time() < deadline:
            await asyncio.sleep(interval)
            t = await self.http.post(f"{AUTH_URL}/oauth/device/token",
                                     json={"code": code["device_code"], "client_id": self.client_id})
            if t.status_code == 200:
                self._save(t.json())
                return True
            if t.status_code == 429:
                interval += 1
            elif t.status_code != 400:
                raise RuntimeError(f"Trakt sign-in failed (HTTP {t.status_code})")
        return False


async def _login() -> None:
    settings = Settings()
    if not settings.trakt_client_id:
        sys.exit("HOMEBASE_TRAKT_CLIENT_ID is not set")
    conn = db.connect(settings.db_path)
    client = TraktClient(settings.trakt_client_id, conn)
    try:
        print("Signed in to Trakt." if await client.device_login() else "The code expired; run this again.")
    finally:
        await client.close()
        conn.close()


if __name__ == "__main__":
    asyncio.run(_login())
