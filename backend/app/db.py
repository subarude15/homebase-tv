import sqlite3
import time
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS preferences (
    device TEXT NOT NULL,
    key TEXT NOT NULL,
    value TEXT NOT NULL,
    PRIMARY KEY (device, key)
);
CREATE TABLE IF NOT EXISTS cache (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated REAL NOT NULL
);
"""


def connect(db_path: Path) -> sqlite3.Connection:
    db_path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(db_path, check_same_thread=False)
    conn.executescript(SCHEMA)
    return conn


def is_healthy(conn: sqlite3.Connection) -> bool:
    try:
        return conn.execute("SELECT 1").fetchone() == (1,)
    except sqlite3.Error:
        return False


def cache_get(conn: sqlite3.Connection, key: str) -> tuple[str, float] | None:
    return conn.execute("SELECT value, updated FROM cache WHERE key = ?", (key,)).fetchone()


def pref_get(conn: sqlite3.Connection, device: str, key: str) -> str | None:
    row = conn.execute("SELECT value FROM preferences WHERE device = ? AND key = ?", (device, key)).fetchone()
    return row[0] if row else None


def pref_put(conn: sqlite3.Connection, device: str, key: str, value: str) -> None:
    conn.execute("INSERT OR REPLACE INTO preferences (device, key, value) VALUES (?, ?, ?)", (device, key, value))
    conn.commit()


def cache_put(conn: sqlite3.Connection, key: str, value: str) -> None:
    conn.execute("INSERT OR REPLACE INTO cache (key, value, updated) VALUES (?, ?, ?)", (key, value, time.time()))
    conn.commit()
