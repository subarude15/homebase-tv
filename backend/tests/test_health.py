from fastapi.testclient import TestClient

from app import __version__
from app.config import Settings
from app.main import create_app


def client(tmp_path) -> TestClient:
    return TestClient(create_app(Settings(api_token="secret", data_dir=tmp_path)))


def test_health_ok_without_token(tmp_path):
    with client(tmp_path) as c:
        body = c.get("/health").json()
    assert body["status"] == "ok"
    assert body["database"] is True
    assert body["authenticated"] is False


def test_health_and_app_report_canonical_version(tmp_path):
    with client(tmp_path) as c:
        assert __version__ == "0.4.0"
        assert c.app.version == __version__
        assert c.get("/health").json()["version"] == __version__


def test_health_reports_valid_token(tmp_path):
    with client(tmp_path) as c:
        body = c.get("/health", headers={"Authorization": "Bearer secret"}).json()
    assert body["authenticated"] is True


def test_protected_endpoint_requires_token(tmp_path):
    with client(tmp_path) as c:
        assert c.get("/api/ping").status_code == 401
        assert c.get("/api/ping", headers={"Authorization": "Bearer wrong"}).status_code == 401
        assert c.get("/api/ping", headers={"Authorization": "Bearer secret"}).json() == {"pong": True}


def test_empty_server_token_rejects_everything(tmp_path):
    with TestClient(create_app(Settings(api_token="", data_dir=tmp_path))) as c:
        assert c.get("/api/ping", headers={"Authorization": "Bearer "}).status_code == 401
