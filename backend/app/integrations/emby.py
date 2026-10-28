import asyncio
import re
from typing import Literal

import httpx
from pydantic import BaseModel

Shape = Literal["landscape", "poster", "square"]
ImageKind = Literal["Primary", "Thumb", "Backdrop", "Logo"]

FIELDS = ",".join([
    "Overview", "ProductionYear", "RunTimeTicks", "UserData", "ChildCount", "SeriesId",
    "SeriesPrimaryImageTag", "ParentBackdropItemId", "ParentBackdropImageTags",
    "ParentThumbItemId", "ParentThumbImageTag",
])
TICKS_PER_MINUTE = 600_000_000
EPISODE_PREFIX = re.compile(r"^.*?\bS\d{1,3}\s?E\d{1,4}\b\s*[-–:.]?\s*", re.IGNORECASE)


class MediaItem(BaseModel):
    id: str
    type: str
    source: Literal["emby", "nuvio", "tivimate"] = "emby"
    # Nuvio's content ID (IMDb "tt…" or "tmdb:…"; for episodes it's the show's), or a favorite channel's stream ID.
    content_id: str | None = None
    title: str
    subtitle: str = ""
    season: int | None = None
    episode: int | None = None
    overview: str = ""
    year: int | None = None
    runtime_minutes: int | None = None
    progress: float | None = None
    image: str | None = None
    backdrop: str | None = None


class Row(BaseModel):
    id: str
    title: str
    shape: Shape
    items: list[MediaItem]


def image_path(item_id: str, kind: ImageKind, tag: str) -> str:
    return f"/api/image/{item_id}/{kind}?tag={tag}"


def _backdrop(i: dict) -> str | None:
    if i.get("BackdropImageTags"):
        return image_path(i["Id"], "Backdrop", i["BackdropImageTags"][0])
    if i.get("ParentBackdropItemId") and i.get("ParentBackdropImageTags"):
        return image_path(i["ParentBackdropItemId"], "Backdrop", i["ParentBackdropImageTags"][0])
    return None


def _poster(i: dict) -> str | None:
    tags = i.get("ImageTags") or {}
    if "Primary" in tags:
        return image_path(i["Id"], "Primary", tags["Primary"])
    if i.get("SeriesId") and i.get("SeriesPrimaryImageTag"):
        return image_path(i["SeriesId"], "Primary", i["SeriesPrimaryImageTag"])
    return None


def _landscape(i: dict) -> str | None:
    tags = i.get("ImageTags") or {}
    if i.get("Type") == "Episode" and "Primary" in tags:
        return image_path(i["Id"], "Primary", tags["Primary"])
    if "Thumb" in tags:
        return image_path(i["Id"], "Thumb", tags["Thumb"])
    if i.get("ParentThumbItemId") and i.get("ParentThumbImageTag"):
        return image_path(i["ParentThumbItemId"], "Thumb", i["ParentThumbImageTag"])
    return _backdrop(i) or _poster(i)


def episode_name(name: str) -> str:
    """Drops a filename-style "Show - S07E07 - " prefix that some Emby metadata keeps in episode names."""
    return EPISODE_PREFIX.sub("", name).strip() or name


def normalize(i: dict, shape: Shape) -> MediaItem:
    kind = i.get("Type", "")
    year = i.get("ProductionYear")
    season = episode = None
    if kind == "Episode":
        title = i.get("SeriesName") or i.get("Name", "")
        season, episode = i.get("ParentIndexNumber"), i.get("IndexNumber")
        number = f"S{season} E{episode}" if season is not None and episode is not None else ""
        subtitle = " · ".join(p for p in (number, episode_name(i.get("Name", ""))) if p)
    elif kind == "Playlist":
        title = i.get("Name", "")
        subtitle = f"{i.get('ChildCount', 0)} items"
    else:
        title = i.get("Name", "")
        subtitle = str(year) if year else ""
    ticks = i.get("RunTimeTicks")
    played = (i.get("UserData") or {}).get("PlayedPercentage")
    return MediaItem(
        id=i["Id"],
        type=kind,
        title=title,
        subtitle=subtitle,
        season=season,
        episode=episode,
        overview=i.get("Overview", ""),
        year=year,
        runtime_minutes=ticks // TICKS_PER_MINUTE if ticks else None,
        progress=played / 100 if played else None,
        image=_landscape(i) if shape == "landscape" else _poster(i),
        backdrop=_backdrop(i),
    )


class EmbyClient:
    def __init__(self, base_url: str, api_key: str, user_name: str = "", transport: httpx.AsyncBaseTransport | None = None):
        self.http = httpx.AsyncClient(
            base_url=base_url.rstrip("/") + "/emby",
            headers={"X-Emby-Token": api_key},
            timeout=10,
            transport=transport,
        )
        self.user_name = user_name
        self._user_id: str | None = None
        self._server_id: str | None = None

    async def close(self) -> None:
        await self.http.aclose()

    async def _get(self, path: str, **params):
        r = await self.http.get(path, params=params)
        r.raise_for_status()
        return r.json()

    async def user_id(self) -> str:
        if self._user_id is None:
            users = await self._get("/Users")
            match = [u for u in users if u["Name"] == self.user_name] if self.user_name else users
            if not match:
                raise LookupError(f"Emby user {self.user_name!r} not found")
            self._user_id = match[0]["Id"]
        return self._user_id

    async def server_id(self) -> str:
        if self._server_id is None:
            self._server_id = (await self._get("/System/Info/Public"))["Id"]
        return self._server_id

    async def home_rows(self) -> list[Row]:
        uid = await self.user_id()
        items = f"/Users/{uid}/Items"
        resume, playlists, movies, episodes, favorites = await asyncio.gather(
            self._get(f"{items}/Resume", Limit=20, MediaTypes="Video", Fields=FIELDS),
            self._get(items, IncludeItemTypes="Playlist", Recursive="true", SortBy="SortName", Fields=FIELDS),
            self._get(f"{items}/Latest", IncludeItemTypes="Movie", Limit=20, Fields=FIELDS),
            self._get(f"{items}/Latest", IncludeItemTypes="Episode", Limit=20, Fields=FIELDS),
            self._get(items, Filters="IsFavorite", Recursive="true", IncludeItemTypes="Movie,Series,Episode", Limit=100, Fields=FIELDS),
        )
        favorites = favorites["Items"]
        specs: list[tuple[str, str, Shape, list[dict]]] = [
            ("continue_watching", "Continue Watching", "landscape", resume["Items"]),
            ("playlists", "My Emby Playlists", "square", playlists["Items"]),
            ("recent_movies", "Recently Added Movies", "poster", movies),
            # Emby's Latest groups new episodes under their series.
            ("recent_episodes", "Recently Added Shows", "poster", episodes),
            ("favorite_movies", "Favorite Movies", "poster", [i for i in favorites if i.get("Type") == "Movie"]),
            # Favorited episodes count as the show's, so they aren't lost.
            ("favorite_shows", "Favorite Shows", "poster", [i for i in favorites if i.get("Type") != "Movie"]),
        ]
        rows = [Row(id=rid, title=title, shape=shape, items=[normalize(i, shape) for i in raw]) for rid, title, shape, raw in specs]
        return [r for r in rows if r.items]

    async def image(self, item_id: str, kind: ImageKind, tag: str, width: int) -> httpx.Response:
        return await self.http.get(f"/Items/{item_id}/Images/{kind}", params={"tag": tag, "maxWidth": width, "quality": 90})
