import httpx
from fastapi.testclient import TestClient
from test_home import AUTH, emby_handler

from app.config import Settings
from app.integrations.iptv import parse_channels
from app.main import create_app

PLAYLIST = "#EXTM3U\n#EXTINF:-1,First\nhttp://cdn.example:8080/live/user1/pass1/1.ts\n"
STREAMS = [
    {"stream_id": 534104, "name": "US| 24/7 SIMPSONS", "stream_icon": "http://logos.example/s.png"},
    {"stream_id": 91950, "name": "US| DOG TV HD", "stream_icon": ""},
    {"stream_id": 7, "name": "Not chosen", "stream_icon": "http://logos.example/7.png"},
]


def iptv_handler(state: dict):
    def handle(request: httpx.Request) -> httpx.Response:
        state.setdefault("urls", []).append(str(request.url))
        if state.get("down"):
            raise httpx.ConnectError("down")
        url = str(request.url)
        if url == "https://playlist.example/get":
            return httpx.Response(200, text=PLAYLIST)
        if url.startswith("http://logos.example/"):
            return httpx.Response(200, content=b"\x89PNGlogo")
        assert url.startswith("http://cdn.example:8080/player_api.php")
        assert request.url.params["action"] == "get_live_streams"
        return httpx.Response(200, json=STREAMS)
    return handle


def make_client(tmp_path, iptv: dict, m3u: str = "https://playlist.example/get") -> TestClient:
    settings = Settings(api_token="secret", data_dir=tmp_path, emby_url="http://emby:8096", emby_api_key="k",
                        home_cache_seconds=0, iptv_m3u_url=m3u, iptv_channels="91950=Dog TV, 534104=The Simpsons")
    return TestClient(create_app(settings, httpx.MockTransport(emby_handler({})),
                                 iptv_transport=httpx.MockTransport(iptv_handler(iptv))))


def test_channel_spec():
    assert parse_channels("91950=Dog TV, 534104,") == [(91950, "Dog TV"), (534104, "Channel 534104")]


def test_favorite_channels_open_tivimate_without_touching_the_provider(tmp_path):
    iptv = {}
    with make_client(tmp_path, iptv) as c:
        body = c.get("/api/home", headers=AUTH).json()
    assert [r["id"] for r in body["rows"]][:2] == ["continue_watching", "favorite_channels"]
    row = body["rows"][1]
    assert row["shape"] == "square"
    assert [(i["title"], i["content_id"], i["source"], i["subtitle"]) for i in row["items"]] == [
        ("Dog TV", "91950", "tivimate", "Opens TiviMate"),
        ("The Simpsons", "534104", "tivimate", "Opens TiviMate"),
    ]
    assert row["items"][1]["image"] == "/api/channels/logo/534104"
    assert "urls" not in iptv


def test_logos_are_cached_and_limited_to_chosen_channels(tmp_path):
    iptv = {}
    with make_client(tmp_path, iptv) as c:
        first = c.get("/api/channels/logo/534104", headers=AUTH)
        assert first.content == b"\x89PNGlogo" and first.headers["content-type"] == "image/png"
        iptv["down"] = True
        assert c.get("/api/channels/logo/534104", headers=AUTH).status_code == 200
        assert c.get("/api/channels/logo/7", headers=AUTH).status_code == 404
        assert c.get("/api/channels/logo/534104").status_code == 401
    # No logo: no image fetch, and nothing that looks like a stream is ever requested.
    with make_client(tmp_path / "b", iptv := {}) as c:
        assert c.get("/api/channels/logo/91950", headers=AUTH).status_code == 404
    assert not any("/live/" in u or u.endswith(".ts") for u in iptv["urls"])


def test_without_a_playlist_shortcuts_have_no_logos(tmp_path):
    with make_client(tmp_path, {}, m3u="") as c:
        items = c.get("/api/home", headers=AUTH).json()["rows"][1]["items"]
        assert [i["image"] for i in items] == [None, None]
        assert c.get("/api/channels/logo/534104", headers=AUTH).status_code == 404
