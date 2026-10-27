import secrets
import time
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException, Request

from . import db
from .config import Settings

VERSION = "0.1.0"


def token_ok(settings: Settings, authorization: str | None) -> bool:
    if not settings.api_token or not authorization:
        return False
    scheme, _, token = authorization.partition(" ")
    return scheme.lower() == "bearer" and secrets.compare_digest(token, settings.api_token)


def require_token(request: Request, authorization: str | None = Header(default=None)) -> None:
    if not token_ok(request.app.state.settings, authorization):
        raise HTTPException(status_code=401, detail="Invalid or missing API token")


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.db = db.connect(settings.db_path)
        yield
        app.state.db.close()

    app = FastAPI(title="Homebase TV", version=VERSION, lifespan=lifespan)
    app.state.settings = settings
    app.state.started = time.monotonic()

    @app.get("/health")
    def health(request: Request, authorization: str | None = Header(default=None)):
        db_ok = db.is_healthy(request.app.state.db)
        return {
            "status": "ok" if db_ok else "degraded",
            "version": VERSION,
            "uptime_seconds": int(time.monotonic() - request.app.state.started),
            "database": db_ok,
            "authenticated": token_ok(settings, authorization),
        }

    @app.get("/api/ping", dependencies=[Depends(require_token)])
    def ping():
        return {"pong": True}

    return app


app = create_app()
