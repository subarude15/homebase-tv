from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="HOMEBASE_")

    api_token: str = ""
    data_dir: Path = Path("data")
    emby_url: str = ""
    emby_api_key: str = ""
    emby_user: str = ""
    home_cache_seconds: int = 60

    @property
    def db_path(self) -> Path:
        return self.data_dir / "homebase.db"
