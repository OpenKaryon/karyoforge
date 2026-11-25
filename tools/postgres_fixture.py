#!/usr/bin/env python3
"""Run PostgreSQL JDBC tests against one disposable loopback-only container."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys
from tempfile import TemporaryDirectory
import time

from tasks import ROOT, gradle_command

IMAGE = "docker.io/library/postgres@sha256:ff87138949ad9a46f32ff23bd6852b52ce024a13dffe9b4087be98292cdd3b4f"
DATABASE = "karyoforge_acceptance"


def docker(*args: str, env: dict[str, str] | None = None) -> str:
    return subprocess.run(["docker", *args], env=env, check=True, stdout=subprocess.PIPE,
                          text=True, encoding="utf-8", timeout=240).stdout.strip()



def provision_tls(container: str, directory: Path) -> dict[str, str]:
    setup = r"""
set -eu
material="$(mktemp -d /tmp/karyoforge-pg-tls-XXXXXX)"
cd "$material"
openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 2 -subj '/CN=KaryoForge Fixture CA' \
    -addext 'basicConstraints=critical,CA:TRUE' -keyout ca.key -out ca.pem >/dev/null 2>&1
openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 2 -subj '/CN=Unrelated fixture CA' \
    -addext 'basicConstraints=critical,CA:TRUE' -keyout other.key -out other.pem >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes -sha256 -subj '/CN=localhost' \
    -keyout "$PGDATA/server.key" -out certificate.req >/dev/null 2>&1
printf '%s\n' 'subjectAltName=DNS:localhost' 'extendedKeyUsage=serverAuth' > extensions
openssl x509 -req -in certificate.req -CA ca.pem -CAkey ca.key -CAcreateserial \
    -days 2 -sha256 -extfile extensions -out "$PGDATA/server.crt" >/dev/null 2>&1
chmod 600 "$PGDATA/server.key"
psql -v ON_ERROR_STOP=1 -d postgres -c "ALTER SYSTEM SET ssl = 'on'" >/dev/null
pg_ctl reload -D "$PGDATA" >/dev/null
printf '%s\n' "$material"
"""
    location = docker("exec", "-u", "postgres", container, "/bin/sh", "-c", setup)
    if not re.fullmatch(r"/tmp/karyoforge-pg-tls-[A-Za-z0-9]+", location):
        raise ValueError("PostgreSQL fixture did not report its certificate directory")
    for name in ("ca.pem", "other.pem"):
        docker("cp", f"{container}:{location}/{name}", str(directory / name))
    return {"KARYOFORGE_TEST_PG_CA": str(directory / "ca.pem"),
            "KARYOFORGE_TEST_PG_OTHER_CA": str(directory / "other.pem")}



def provision_ssh(container: str, directory: Path, executable: Path) -> dict[str, str]:
    keygen = executable.with_name("ssh-keygen.exe" if os.name == "nt" else "ssh-keygen")
    key = directory / "client"
    subprocess.run([str(keygen), "-q", "-t", "ed25519", "-N", "", "-f", str(key)], check=True, timeout=25)
    docker("exec", container, "install", "-d", "-m", "755", "/etc/karyoforge-ssh", "/run/sshd")
    docker("cp", str(key.with_suffix(".pub")), f"{container}:/etc/karyoforge-ssh/authorized_keys")
    setup = r"""
set -eu
root=/etc/karyoforge-ssh
useradd -m -s /usr/sbin/nologin karyoforge_ssh
usermod -p "$(openssl passwd -6 "$(openssl rand -hex 32)")" karyoforge_ssh
chmod 644 "$root/authorized_keys"
ssh-keygen -q -t ed25519 -N '' -f "$root/host"
cat > "$root/config" <<'CONFIG'
Port 22
ListenAddress 0.0.0.0
HostKey /etc/karyoforge-ssh/host
AuthorizedKeysFile /etc/karyoforge-ssh/authorized_keys
StrictModes yes
UsePAM no
AllowUsers karyoforge_ssh
AuthenticationMethods publickey
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
AllowTcpForwarding local
PermitOpen 127.0.0.1:5432
AllowAgentForwarding no
X11Forwarding no
PermitTunnel no
PermitTTY no
ForceCommand /bin/false
CONFIG
/usr/sbin/sshd -t -f "$root/config"
"""
    docker("exec", container, "/bin/sh", "-c", setup)
    docker("exec", "-d", container, "/usr/sbin/sshd", "-D", "-f", "/etc/karyoforge-ssh/config")
    endpoint = docker("port", container, "22/tcp")
    binding = re.fullmatch(r"127\.0\.0\.1:([0-9]{1,5})", endpoint)
    if not binding:
        raise ValueError("SSH fixture was not published exclusively on loopback")
    port = int(binding.group(1))
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=2) as connection:
                if connection.recv(128).startswith(b"SSH-"):
                    break
        except OSError:
            pass
        time.sleep(0.25)
    else:
        raise ValueError("The owned SSH server did not become ready")
    host_key = " ".join(docker("exec", container, "cat", "/etc/karyoforge-ssh/host.pub").split()[:2])
    unrelated = " ".join(key.with_suffix(".pub").read_text(encoding="ascii").split()[:2])
    if not re.fullmatch(r"ssh-ed25519 [A-Za-z0-9+/=]+", host_key):
        raise ValueError("Unexpected SSH fixture host key")
    known = directory / "known_hosts"
    wrong = directory / "wrong_hosts"
    known.write_text(f"[127.0.0.1]:{port} {host_key}\n", encoding="ascii")
    wrong.write_text(f"[127.0.0.1]:{port} {unrelated}\n", encoding="ascii")
    return {"KARYOFORGE_TEST_SSH_EXECUTABLE": str(executable), "KARYOFORGE_TEST_SSH_PORT": str(port),
            "KARYOFORGE_TEST_SSH_KEY": str(key), "KARYOFORGE_TEST_SSH_KNOWN": str(known),
            "KARYOFORGE_TEST_SSH_WRONG": str(wrong)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--driver", required=True)
    parser.add_argument("--offline-gradle", action="store_true")
    parser.add_argument("--openssh", default="")
    args = parser.parse_args()
    driver = (ROOT / Path(args.driver).expanduser()).resolve()
    if driver.suffix.lower() != ".jar" or not driver.is_file():
        raise ValueError("POSTGRES_DRIVER must name an existing local JDBC JAR")
    ssh = Path(args.openssh).expanduser().resolve() if args.openssh else None
    if ssh is not None and (not ssh.is_file() or ssh.name.lower() not in {"ssh", "ssh.exe"}):
        raise ValueError("OPENSSH_EXECUTABLE must name an existing native OpenSSH executable")
    image = IMAGE
    extra: list[str] = []
    if ssh is not None:
        image = docker("build", "--quiet", "-f", str(ROOT / "tools/postgres-ssh.Dockerfile"), str(ROOT / "tools"))
        if not re.fullmatch(r"sha256:[a-f0-9]{64}", image):
            raise ValueError("Docker did not identify the owned PostgreSQL SSH fixture image")
        extra = ["--publish", "127.0.0.1::22"]
    password = secrets.token_urlsafe(30)
    environment = {**os.environ, "POSTGRES_PASSWORD": password, "POSTGRES_DB": DATABASE}
    container = docker("run", "--rm", "-d", "--pull=missing",
                       "--name", f"karyoforge-postgresql-{secrets.token_hex(6)}",
                       "--label", "org.openkaryon.karyoforge.fixture=postgresql",
                       "--publish", "127.0.0.1::5432",
                       "--tmpfs", "/var/lib/postgresql/data:rw",
                       "--env", "POSTGRES_PASSWORD", "--env", "POSTGRES_DB", *extra, image, env=environment)
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
        if ssh is not None:
            selection.extend(["--tests", "*PostgresqlSshJdbcSessionTest", "-PpostgresSshTests=true"])
        if args.offline_gradle:
            selection.insert(0, "--offline")
        tests = {**os.environ, "KARYOFORGE_TEST_PG_PORT": binding.group(1), "KARYOFORGE_TEST_PG_PASSWORD": password}
        with TemporaryDirectory(prefix="karyoforge-pg-certs-") as directory:
            tests.update(provision_tls(container, Path(directory)))
            if ssh is not None:
                tests.update(provision_ssh(container, Path(directory), ssh))
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
