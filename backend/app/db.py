import sqlite3
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS preferences (
    device TEXT NOT NULL,
    key TEXT NOT NULL,
    value TEXT NOT NULL,
    PRIMARY KEY (device, key)
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
