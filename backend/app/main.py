import json
import secrets
import time
from contextlib import asynccontextmanager

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Path, Query, Request, Response

from . import db
from .config import Settings
from .integrations.emby import EmbyClient, ImageKind

VERSION = "0.2.0"
HOME_CACHE_KEY = "home"


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


def create_app(settings: Settings | None = None, emby_transport: httpx.AsyncBaseTransport | None = None) -> FastAPI:
    settings = settings or Settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.db = db.connect(settings.db_path)
        app.state.emby = (
            EmbyClient(settings.emby_url, settings.emby_api_key, settings.emby_user, emby_transport)
            if settings.emby_url and settings.emby_api_key
            else None
        )
        yield
        if app.state.emby:
            await app.state.emby.close()
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

    @app.get("/api/home", dependencies=[Depends(require_token)])
    async def home(request: Request, emby: EmbyClient = Depends(require_emby)):
        conn = request.app.state.db
        cached = db.cache_get(conn, HOME_CACHE_KEY)
        if cached and time.time() - cached[1] < settings.home_cache_seconds:
            return {**json.loads(cached[0]), "stale": False}
        try:
            rows = await emby.home_rows()
        except (httpx.HTTPError, LookupError) as e:
            if cached:
                return {**json.loads(cached[0]), "stale": True}
            raise HTTPException(status_code=502, detail=f"Emby unavailable: {e}") from e
        payload = {"rows": [r.model_dump() for r in rows], "updated_at": int(time.time())}
        db.cache_put(conn, HOME_CACHE_KEY, json.dumps(payload))
        return {**payload, "stale": False}

    @app.get("/api/image/{item_id}/{kind}", dependencies=[Depends(require_token)])
    async def image(
        kind: ImageKind,
        item_id: str = Path(pattern=r"^[A-Za-z0-9]+$"),
        tag: str = Query("", pattern=r"^[A-Za-z0-9]*$"),
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
