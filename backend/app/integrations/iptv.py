"""Favorite channel shortcuts that open TiviMate. Only channel logos come from the IPTV provider; no streams.
The playlist URL carries the provider login: never log it."""
from urllib.parse import urlsplit

import httpx

from .emby import MediaItem, Row


def parse_channels(spec: str) -> list[tuple[int, str]]:
    """"534104=The Simpsons,91950=Dog TV" -> [(534104, "The Simpsons"), (91950, "Dog TV")]."""
    channels = []
    for part in filter(None, (p.strip() for p in spec.split(","))):
        sid, _, name = part.partition("=")
        channels.append((int(sid), name.strip() or f"Channel {sid.strip()}"))
    return channels


def channel_row(channels: list[tuple[int, str]], logos: bool) -> list[Row]:
    """TiviMate can't be deep-linked to a channel, so every shortcut just opens TiviMate and says so."""
    items = [
        MediaItem(id=f"channel-{sid}", type="Channel", source="tivimate", content_id=str(sid), title=name,
                  subtitle="Opens TiviMate", image=f"/api/channels/logo/{sid}" if logos else None)
        for sid, name in channels
    ]
    return [Row(id="favorite_channels", title="Favorite Channels", shape="square", items=items)] if items else []


class XtreamLogos:
    """Looks up channel logos through the provider's Xtream API, once per process."""

    def __init__(self, m3u_url: str, wanted: set[int], transport: httpx.AsyncBaseTransport | None = None):
        self.m3u_url = m3u_url
        self.wanted = wanted
        self.http = httpx.AsyncClient(headers={"User-Agent": "Homebase/1.0"}, timeout=15,
                                      follow_redirects=True, transport=transport)
        self._icons: dict[int, str] | None = None

    async def close(self) -> None:
        await self.http.aclose()

    async def _login(self) -> tuple[str, str, str]:
        """(base, user, password), read from the first channel URL in the playlist; nothing is played."""
        async with self.http.stream("GET", self.m3u_url) as r:
            r.raise_for_status()
            async for line in r.aiter_lines():
                if line.startswith("http"):
                    u = urlsplit(line.strip())
                    segs = [s for s in u.path.split("/") if s]
                    if segs and segs[0] == "live":
                        segs = segs[1:]
                    if len(segs) < 3:
                        raise LookupError("Playlist channel URLs aren't Xtream-style")
                    return f"{u.scheme}://{u.netloc}", segs[0], segs[1]
        raise LookupError("No channels in the IPTV playlist")

    async def _icons_by_id(self) -> dict[int, str]:
        if self._icons is None:
            base, user, password = await self._login()
            r = await self.http.get(f"{base}/player_api.php",
                                    params={"username": user, "password": password, "action": "get_live_streams"})
            r.raise_for_status()
            self._icons = {s["stream_id"]: s["stream_icon"] for s in r.json()
                           if s.get("stream_id") in self.wanted and s.get("stream_icon")}
        return self._icons

    async def logo(self, stream_id: int) -> httpx.Response:
        icon = (await self._icons_by_id()).get(stream_id)
        return await self.http.get(icon, timeout=10) if icon else httpx.Response(404)
