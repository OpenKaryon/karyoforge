.DEFAULT_GOAL := help

PYTHON ?= python
REFERENCE_DIR ?=
AUDIT_OUTPUT ?= docs/audit/reference-inventory.json
PYTHON_EXECUTABLE ?=

.PHONY: help lint test audit check audit-reference audit-reference-check build verify test-ide test-python run package-windows package-linux

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

run:
	"$(PYTHON)" tools/tasks.py run

package-windows:
	"$(PYTHON)" tools/tasks.py package-windows

package-linux:
	"$(PYTHON)" tools/tasks.py package-linux
