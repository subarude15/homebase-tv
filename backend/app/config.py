from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="HOMEBASE_")

    api_token: str = ""
    data_dir: Path = Path("data")
    emby_url: str = ""
    emby_api_key: str = ""
    emby_user: str = ""
    trakt_client_id: str = ""
    # Only used to look up favorite channel logos.
    iptv_m3u_url: str = ""
    # Favorite channel shortcuts in row order: comma-separated "streamID=Name".
    iptv_channels: str = ""
    # Nuvio's catalog addon for the Trending rows, e.g. https://catalog.nuvio.tv; empty turns them off.
    nuvio_catalog_url: str = ""
    # Trending lists change a few times a day (the addon itself caches them for 6 hours).
    catalog_cache_seconds: int = 3600
    home_cache_seconds: int = 60
    # Past this, an expired source is served from its last copy while the refresh finishes in the background.
    home_refresh_wait_seconds: float = 3

    @property
    def db_path(self) -> Path:
        return self.data_dir / "homebase.db"
