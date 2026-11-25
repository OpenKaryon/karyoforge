#!/usr/bin/env python3
"""Run ClickHouse JDBC checks against a disposable SELECT-only fixture."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time

from postgres_fixture import docker
from tasks import ROOT, gradle_command

IMAGE = "docker.io/clickhouse/clickhouse-server@sha256:9b61e3c635c04ad5bb521eb4f6e61ce7585b5580814e51c25bb9e8292ce43364"
DATABASE = "karyoforge_acceptance"
ADMIN = "fixture_admin"
READER = "fixture_reader"


def initialize(container: str, admin_password: str, reader_password: str) -> None:
    sql = f"""
        CREATE DATABASE IF NOT EXISTS {DATABASE};
        CREATE TABLE {DATABASE}.samples (
            id UInt64, note Nullable(String), exact_value UInt64, decimal_value Decimal(38,6)
        ) ENGINE=MergeTree ORDER BY id;
        INSERT INTO {DATABASE}.samples
        SELECT number+1, if(number=0,NULL,concat('sample-',toString(number+1))), 18446744073709551615,
            toDecimal128('9223372036854775807.000001',6) FROM numbers(205);
        CREATE VIEW {DATABASE}.sample_view AS SELECT id,note FROM {DATABASE}.samples;
        CREATE USER {READER} IDENTIFIED WITH plaintext_password BY '{reader_password}'
            SETTINGS readonly=1, max_execution_time=30;
        GRANT SELECT ON {DATABASE}.* TO {READER};
        GRANT SELECT ON system.settings TO {READER};
        GRANT SELECT ON system.tables TO {READER};
        GRANT SELECT ON system.columns TO {READER};
        GRANT SELECT ON system.processes TO {READER};
    """
    subprocess.run(["docker", "exec", "-i", "--env", "KARYOFORGE_CLICK_ADMIN_PASSWORD", container, "/bin/sh", "-c",
                    'exec clickhouse-client --user fixture_admin --password "$KARYOFORGE_CLICK_ADMIN_PASSWORD" --multiquery'],
                   input=sql, text=True, encoding="utf-8", check=True, timeout=40,
                   env={**os.environ, "KARYOFORGE_CLICK_ADMIN_PASSWORD": admin_password})


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--driver", required=True)
    parser.add_argument("--offline-gradle", action="store_true")
    args = parser.parse_args()
    driver = (ROOT / Path(args.driver).expanduser()).resolve()
    if not driver.is_file() or driver.suffix.lower() != ".jar":
        raise ValueError("CLICKHOUSE_DRIVER must name an existing local JDBC JAR")
    admin_password, password = secrets.token_urlsafe(30), secrets.token_urlsafe(30)
    env = {**os.environ, "CLICKHOUSE_USER": ADMIN, "CLICKHOUSE_PASSWORD": admin_password,
           "CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT": "1"}
    container = docker("run", "--rm", "-d", "--pull=missing", "--name", f"karyoforge-clickhouse-{secrets.token_hex(6)}",
                       "--label", "org.openkaryon.karyoforge.fixture=clickhouse", "--publish", "127.0.0.1::8123",
                       "--ulimit", "nofile=262144:262144", "--tmpfs", "/var/lib/clickhouse:rw,size=2g",
                       "--env", "CLICKHOUSE_USER", "--env", "CLICKHOUSE_PASSWORD",
                       "--env", "CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", IMAGE, env=env)
    if not re.fullmatch(r"[0-9a-f]{64}", container):
        raise ValueError("Docker did not identify the ClickHouse fixture")
    try:
        binding = re.fullmatch(r"127\.0\.0\.1:([0-9]{1,5})", docker("port", container, "8123/tcp"))
        if not binding:
            raise ValueError("ClickHouse fixture was not published exclusively on loopback")
        deadline = time.monotonic() + 100
        while time.monotonic() < deadline:
            probe = subprocess.run(["docker", "exec", "--env", "KARYOFORGE_CLICK_ADMIN_PASSWORD", container, "/bin/sh", "-c",
                                    'clickhouse-client --user fixture_admin --password "$KARYOFORGE_CLICK_ADMIN_PASSWORD" --query "SELECT 1"'],
                                   env={**os.environ, "KARYOFORGE_CLICK_ADMIN_PASSWORD": admin_password},
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False, timeout=10)
            if probe.returncode == 0:
                break
            time.sleep(1)
        else:
            raise ValueError("The owned ClickHouse container did not become ready")
        initialize(container, admin_password, password)
        selection = ["test", "--tests", "*ClickHouseJdbcSessionTest", f"-PclickhouseDriver={driver}"]
        if args.offline_gradle:
            selection.insert(0, "--offline")
        tests = {**os.environ, "KARYOFORGE_TEST_CLICKHOUSE_PORT": binding.group(1),
                 "KARYOFORGE_TEST_CLICKHOUSE_USER": READER, "KARYOFORGE_TEST_CLICKHOUSE_PASSWORD": password,
                 "KARYOFORGE_TEST_CLICKHOUSE_ADMIN": ADMIN, "KARYOFORGE_TEST_CLICKHOUSE_ADMIN_PASSWORD": admin_password}
        print("Running JDBC acceptance on the owned ClickHouse 26.8.20.9 container.", flush=True)
        return subprocess.run(gradle_command(ROOT, selection), cwd=ROOT, env=tests, check=False).returncode
    finally:
        docker("stop", "--time", "5", container)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"ClickHouse acceptance failed: {error}", file=sys.stderr)
        sys.exit(2)
