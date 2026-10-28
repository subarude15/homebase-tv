import asyncio
import hashlib
import json
import secrets
import time
from collections.abc import Awaitable, Callable
from contextlib import asynccontextmanager

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Path, Query, Request, Response

from . import db
from .config import Settings
from .integrations.emby import EmbyClient, ImageKind
from .integrations.trakt import IMAGE_PATH, TraktClient

VERSION = "0.3.0"
HOME_CACHE_KEY = "home"
TRAKT_CACHE_KEY = "home_trakt"


def token_ok(settings: Settings, authorization: str | None) -> bool:
    if not settings.api_token or not authorization:
        return False
    scheme, _, token = authorization.partition(" ")
    return scheme.lower() == "bearer" and secrets.compare_digest(token, settings.api_token)


def require_token(request: Request, authorization: str | None = Header(default=None)) -> None:
    if not token_ok(request.app.state.settings, authorization):
        raise HTTPException(status_code=401, detail="Invalid or missing API token")


def require_emby(request: Request) -> EmbyClient:
    emby = request.app.state.emby
    if emby is None:
        raise HTTPException(status_code=503, detail="Emby is not configured on the backend")
    return emby


def require_trakt(request: Request) -> TraktClient:
    trakt = request.app.state.trakt
    if trakt is None:
        raise HTTPException(status_code=503, detail="Trakt is not configured on the backend")
    return trakt


def create_app(
    settings: Settings | None = None,
    emby_transport: httpx.AsyncBaseTransport | None = None,
    trakt_transport: httpx.AsyncBaseTransport | None = None,
) -> FastAPI:
    settings = settings or Settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.db = db.connect(settings.db_path)
        app.state.emby = (
            EmbyClient(settings.emby_url, settings.emby_api_key, settings.emby_user, emby_transport)
            if settings.emby_url and settings.emby_api_key
            else None
        )
        app.state.trakt = (
            TraktClient(settings.trakt_client_id, app.state.db, trakt_transport) if settings.trakt_client_id else None
        )
        yield
        for client in (app.state.emby, app.state.trakt):
            if client:
                await client.close()
        app.state.db.close()

    app = FastAPI(title="Homebase TV", version=VERSION, lifespan=lifespan)
    app.state.settings = settings
    app.state.started = time.monotonic()

    @app.get("/health")
    async def health(request: Request, authorization: str | None = Header(default=None)):
        db_ok = db.is_healthy(request.app.state.db)
        return {
            "status": "ok" if db_ok else "degraded",
            "version": VERSION,
            "uptime_seconds": int(time.monotonic() - request.app.state.started),
            "database": db_ok,
            "authenticated": token_ok(settings, authorization),
        }

    @app.get("/api/ping", dependencies=[Depends(require_token)])
    async def ping():
        return {"pong": True}

    async def source_rows(conn, key: str, fetch: Callable[[], Awaitable[dict]]) -> tuple[dict | None, bool, str]:
        """(payload, stale, error) for one source; a failing source falls back to its own last copy."""
        cached = db.cache_get(conn, key)
        if cached and time.time() - cached[1] < settings.home_cache_seconds:
            return json.loads(cached[0]), False, ""
        try:
            payload = {**await fetch(), "updated_at": int(time.time())}
        except (httpx.HTTPError, LookupError) as e:
            return (json.loads(cached[0]) if cached else None), True, str(e)
        db.cache_put(conn, key, json.dumps(payload))
        return payload, False, ""

    async def emby_payload() -> dict:
        emby = app.state.emby
        rows = await emby.home_rows()
        return {"server_id": await emby.server_id(), "rows": [r.model_dump() for r in rows]}

    async def trakt_payload() -> dict:
        return {"rows": [r.model_dump() for r in await app.state.trakt.home_rows()]}

    @app.get("/api/home", dependencies=[Depends(require_token)])
    async def home(request: Request):
        conn = request.app.state.db
        trakt = request.app.state.trakt
        sources = {}
        if request.app.state.emby:
            sources[HOME_CACHE_KEY] = emby_payload
        if trakt and trakt.token():
            sources[TRAKT_CACHE_KEY] = trakt_payload
        if not sources:
            raise HTTPException(status_code=503, detail="No media sources are configured on the backend")
        results = dict(zip(sources, await asyncio.gather(*(source_rows(conn, k, f) for k, f in sources.items()))))
        payloads = {k: p for k, (p, _, _) in results.items() if p}
        if not payloads:
            raise HTTPException(status_code=502, detail="; ".join(e for _, _, e in results.values()))
        emby_rows = payloads.get(HOME_CACHE_KEY, {}).get("rows", [])
        # Nuvio's rows go right after Emby's Continue Watching.
        split = 1 if emby_rows and emby_rows[0]["id"] == "continue_watching" else 0
        return {
            "server_id": payloads.get(HOME_CACHE_KEY, {}).get("server_id"),
            "rows": emby_rows[:split] + payloads.get(TRAKT_CACHE_KEY, {}).get("rows", []) + emby_rows[split:],
            "updated_at": min(p["updated_at"] for p in payloads.values()),
            "stale": any(stale for _, stale, _ in results.values()),
        }

    @app.get("/api/trakt/image", dependencies=[Depends(require_token)])
    async def trakt_image(path: str = Query(pattern=IMAGE_PATH.pattern), trakt: TraktClient = Depends(require_trakt)):
        # Trakt forbids hotlinking, so every image is fetched once and served from disk. Paths are content-hashed.
        # ponytail: the image cache is never pruned; fine for a few hundred posters, add an LRU sweep if it grows.
        file = settings.data_dir / "trakt-images" / hashlib.sha256(path.encode()).hexdigest()
        if not file.exists():
            try:
                r = await trakt.image(path)
            except httpx.HTTPError as e:
                raise HTTPException(status_code=502, detail="Trakt unavailable") from e
            if r.status_code != 200:
                raise HTTPException(status_code=404, detail="Image not found")
            file.parent.mkdir(parents=True, exist_ok=True)
            tmp = file.with_suffix(f".{secrets.token_hex(4)}")
            tmp.write_bytes(r.content)
            tmp.replace(file)
        return Response(file.read_bytes(), media_type="image/webp", headers={"Cache-Control": "public, max-age=604800"})

    @app.get("/api/image/{item_id}/{kind}", dependencies=[Depends(require_token)])
    async def image(
        kind: ImageKind,
        item_id: str = Path(pattern=r"^[A-Za-z0-9]+$"),
        tag: str = Query("", pattern=r"^[A-Za-z0-9_-]*$"),
        width: int = Query(480, ge=50, le=1920),
        emby: EmbyClient = Depends(require_emby),
    ):
        try:
            r = await emby.image(item_id, kind, tag, width)
        except httpx.HTTPError as e:
            raise HTTPException(status_code=502, detail="Emby unavailable") from e
        if r.status_code != 200:
            raise HTTPException(status_code=404, detail="Image not found")
        # Tag changes whenever Emby's image changes, so the URL is safe to cache long-term.
        return Response(r.content, media_type=r.headers.get("content-type", "image/jpeg"),
                        headers={"Cache-Control": "public, max-age=604800"})

    return app


app = create_app()
