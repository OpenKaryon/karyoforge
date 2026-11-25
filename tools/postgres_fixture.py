#!/usr/bin/env python3
"""Run PostgreSQL JDBC tests against one disposable loopback-only container."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time

from tasks import ROOT, gradle_command

IMAGE = "docker.io/library/postgres@sha256:ff87138949ad9a46f32ff23bd6852b52ce024a13dffe9b4087be98292cdd3b4f"
DATABASE = "karyoforge_acceptance"


def docker(*args: str, env: dict[str, str] | None = None) -> str:
    return subprocess.run(["docker", *args], env=env, check=True, stdout=subprocess.PIPE,
                          text=True, encoding="utf-8", timeout=240).stdout.strip()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--driver", required=True)
    parser.add_argument("--offline-gradle", action="store_true")
    args = parser.parse_args()
    driver = (ROOT / Path(args.driver).expanduser()).resolve()
    if driver.suffix.lower() != ".jar" or not driver.is_file():
        raise ValueError("POSTGRES_DRIVER must name an existing local JDBC JAR")
    password = secrets.token_urlsafe(30)
    environment = {**os.environ, "POSTGRES_PASSWORD": password, "POSTGRES_DB": DATABASE}
    container = docker("run", "--rm", "-d", "--pull=missing",
                       "--name", f"karyoforge-postgresql-{secrets.token_hex(6)}",
                       "--label", "org.openkaryon.karyoforge.fixture=postgresql",
                       "--publish", "127.0.0.1::5432",
                       "--tmpfs", "/var/lib/postgresql/data:rw",
                       "--env", "POSTGRES_PASSWORD", "--env", "POSTGRES_DB", IMAGE, env=environment)
    if not re.fullmatch(r"[0-9a-f]{64}", container):
        raise ValueError("Docker did not return the identity of the acceptance container")
    try:
        endpoint = docker("port", container, "5432/tcp")
        binding = re.fullmatch(r"127\.0\.0\.1:([0-9]{1,5})", endpoint)
        if not binding:
            raise ValueError("PostgreSQL fixture was not published exclusively on loopback")
        deadline = time.monotonic() + 90
        keep_waiting = True
        while keep_waiting and time.monotonic() < deadline:
            probe = subprocess.run(["docker", "exec", container, "pg_isready", "-q", "-h", "127.0.0.1",
                                    "-d", DATABASE, "-U", "postgres"], check=False, timeout=10,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            keep_waiting = probe.returncode != 0
            if keep_waiting:
                time.sleep(1)
        if keep_waiting:
            raise ValueError("The owned PostgreSQL container did not become ready")
        selection = ["test", "--tests", "*PostgresqlJdbcSessionTest", f"-PpostgresDriver={driver}"]
        if args.offline_gradle:
            selection.insert(0, "--offline")
        tests = {**os.environ, "KARYOFORGE_TEST_PG_PORT": binding.group(1), "KARYOFORGE_TEST_PG_PASSWORD": password}
        print("Running JDBC acceptance on the owned PostgreSQL 17.11 container.", flush=True)
        return subprocess.run(gradle_command(ROOT, selection), cwd=ROOT, env=tests, check=False).returncode
    finally:
        docker("stop", "--time", "3", container)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"PostgreSQL acceptance failed: {error}", file=sys.stderr)
        sys.exit(2)
