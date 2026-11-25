.DEFAULT_GOAL := help

PYTHON ?= python
REFERENCE_DIR ?=
AUDIT_OUTPUT ?= docs/audit/reference-inventory.json
PYTHON_EXECUTABLE ?=
SQLITE_DRIVER ?=
POSTGRES_DRIVER ?=
OPENSSH_EXECUTABLE ?=
MYSQL_DRIVER ?=
MARIADB_DRIVER ?=

.PHONY: test-mariadb test-mysql test-postgresql test-database notebook-fixtures notebook-runtime test-notebook gui-bundle test-gui help lint test audit check audit-reference audit-reference-check build verify test-ide test-python branding test-branding run dist-windows zip-windows verify-dist-windows package-windows package-linux

help:
	"$(PYTHON)" tools/tasks.py help

lint:
	"$(PYTHON)" tools/tasks.py lint

test:
	"$(PYTHON)" tools/tasks.py test

audit:
	"$(PYTHON)" tools/tasks.py audit

check: lint test audit

audit-reference:
	"$(PYTHON)" tools/tasks.py audit-reference --reference "$(REFERENCE_DIR)" --output "$(AUDIT_OUTPUT)"

audit-reference-check:
	"$(PYTHON)" tools/tasks.py audit-reference-check --reference "$(REFERENCE_DIR)" --output "$(AUDIT_OUTPUT)"

build:
	"$(PYTHON)" tools/tasks.py build

verify:
	"$(PYTHON)" tools/tasks.py verify

test-ide:
	"$(PYTHON)" tools/tasks.py test-ide

test-python:
	"$(PYTHON)" tools/tasks.py test-python --python-executable "$(PYTHON_EXECUTABLE)"

branding:
	"$(PYTHON)" tools/tasks.py branding

test-branding:
	"$(PYTHON)" tools/tasks.py test-branding

run:
	"$(PYTHON)" tools/tasks.py run

dist-windows:
	"$(PYTHON)" tools/tasks.py dist-windows

zip-windows:
	"$(PYTHON)" tools/tasks.py zip-windows

verify-dist-windows:
	"$(PYTHON)" tools/tasks.py verify-dist-windows

package-windows: check
	"$(PYTHON)" tools/tasks.py package-windows

package-linux:
	"$(PYTHON)" tools/tasks.py package-linux

test-database:
	"$(PYTHON)" tools/tasks.py test-database --sqlite-driver "$(SQLITE_DRIVER)"

test-postgresql:
	"$(PYTHON)" tools/tasks.py test-postgresql --postgres-driver "$(POSTGRES_DRIVER)" --openssh "$(OPENSSH_EXECUTABLE)"

test-mysql:
	"$(PYTHON)" tools/tasks.py test-mysql --mysql-driver "$(MYSQL_DRIVER)"

test-mariadb:
	"$(PYTHON)" tools/tasks.py test-mariadb --mysql-driver "$(MARIADB_DRIVER)"

gui-bundle:
	"$(PYTHON)" tools/tasks.py gui-bundle --sqlite-driver "$(SQLITE_DRIVER)"

test-gui:
	"$(PYTHON)" tools/tasks.py test-gui

notebook-fixtures:
	"$(PYTHON)" tools/tasks.py notebook-fixtures

notebook-runtime:
	"$(PYTHON)" tools/tasks.py notebook-runtime --python-executable "$(PYTHON_EXECUTABLE)"

test-notebook:
	"$(PYTHON)" tools/tasks.py test-notebook --python-executable "$(PYTHON_EXECUTABLE)"
