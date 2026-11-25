#!/usr/bin/env python3
"""Run MySQL JDBC checks against one disposable loopback-only container."""

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

IMAGE = "docker.io/library/mysql@sha256:0f51e63e537a1f176423fa03ef559aabe6fa4372a53160a44bc7e75da7d15364"
DATABASE = "karyoforge_acceptance"
USER = "karyoforge_query"


def seed(container: str, root_password: str) -> None:
    sql = """
        CREATE TABLE samples (
            id BIGINT PRIMARY KEY,
            note VARCHAR(64) NOT NULL,
            exact_value BIGINT UNSIGNED NOT NULL,
            decimal_value DECIMAL(40,6),
            payload BLOB
        );
        CREATE VIEW sample_view AS SELECT id,note FROM samples;
    """
    batch = ",\n".join(f"({index}, 'sample-{index}', 18446744073709551615, 9223372036854775807.000001, X'00AB')"
                      for index in range(1, 206))
    sql += "\nINSERT INTO samples VALUES " + batch + ";\n"
    subprocess.run(["docker", "exec", "-i", "--env", "MYSQL_PWD", container, "mysql",
                    "--user=root", "--database=" + DATABASE], env={**os.environ, "MYSQL_PWD": root_password},
                   input=sql, text=True, encoding="utf-8", check=True, timeout=30)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--driver", required=True)
    parser.add_argument("--offline-gradle", action="store_true")
    args = parser.parse_args()
    driver = (ROOT / Path(args.driver).expanduser()).resolve()
    if not driver.is_file() or driver.suffix.lower() != ".jar":
        raise ValueError("MYSQL_DRIVER must name an existing local JDBC JAR")
    root_password, user_password = secrets.token_urlsafe(30), secrets.token_urlsafe(30)
    environment = {**os.environ, "MYSQL_ROOT_PASSWORD": root_password, "MYSQL_DATABASE": DATABASE,
                   "MYSQL_USER": USER, "MYSQL_PASSWORD": user_password}
    container = docker("run", "--rm", "-d", "--pull=missing", "--name", f"karyoforge-mysql-{secrets.token_hex(6)}",
                       "--label", "org.openkaryon.karyoforge.fixture=mysql",
                       "--publish", "127.0.0.1::3306", "--tmpfs", "/var/lib/mysql:rw,size=1g",
                       "--env", "MYSQL_ROOT_PASSWORD", "--env", "MYSQL_DATABASE", "--env", "MYSQL_USER",
                       "--env", "MYSQL_PASSWORD", IMAGE, "--skip-log-bin", env=environment)
    if not re.fullmatch(r"[0-9a-f]{64}", container):
        raise ValueError("Docker did not return the identity of the MySQL fixture")
    try:
        endpoint = docker("port", container, "3306/tcp")
        binding = re.fullmatch(r"127\.0\.0\.1:([0-9]{1,5})", endpoint)
        if not binding:
            raise ValueError("MySQL fixture was not published exclusively on loopback")
        deadline = time.monotonic() + 110
        while time.monotonic() < deadline:
            probe = subprocess.run(["docker", "exec", container, "mysqladmin", "--host=127.0.0.1", "--silent", "ping"],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False, timeout=10)
            if probe.returncode == 0:
                break
            time.sleep(1)
        else:
            raise ValueError("The owned MySQL container did not become ready")
        seed(container, root_password)
        tasks = ["test", "--tests", "*MysqlJdbcSessionTest", f"-PmysqlDriver={driver}"]
        if args.offline_gradle:
            tasks.insert(0, "--offline")
        tests = {**os.environ, "KARYOFORGE_TEST_MYSQL_PORT": binding.group(1),
                 "KARYOFORGE_TEST_MYSQL_USER": USER, "KARYOFORGE_TEST_MYSQL_PASSWORD": user_password}
        print("Running JDBC acceptance on the owned MySQL 8.4.11 container.", flush=True)
        return subprocess.run(gradle_command(ROOT, tasks), cwd=ROOT, env=tests, check=False).returncode
    finally:
        docker("stop", "--time", "5", container)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"MySQL acceptance failed: {error}", file=sys.stderr)
        sys.exit(2)
