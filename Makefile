# One entry point for the whole monorepo. Run `make` to see the commands.
.DEFAULT_GOAL := help
LIVE ?= https://fieldpartner-api.onrender.com

help:
	@echo "make setup      first-time setup on a Mac (packages, config.env)"
	@echo "make dev        run backend, AI agent and portal on this Mac"
	@echo "make test       run every test suite (backend, agent, portal build, Android)"
	@echo "make apk        Android APK for the live server -> FieldPartner-android-online.apk"
	@echo "make apk-local  Android APK for this Mac -> FieldPartner-android-offline.apk"
	@echo "make ios        generate and open the iPhone project in Xcode"
	@echo "make live       check the live server and portal"
	@echo "make ship       test, commit and push; CI builds and tests everything and Render deploys"

setup:
	./scripts/setup-mac.sh

dev:
	./scripts/start-local.sh

test:
	cd backend && npm test
	cd agent && .venv/bin/python -m pytest -q
	cd portal && npm run build
	./scripts/test-android.sh

apk:
	./scripts/build-android.sh phone $(LIVE)

apk-local:
	./scripts/build-android.sh phone

ios:
	./scripts/build-ios.sh

live:
	LIVE_API=$(LIVE) bash scripts/ci/live-check.sh

ship:
	@test -n "$(m)" || (echo 'Usage: make ship m="what changed"'; exit 1)
	git add -A
	git commit -m "$(m)"
	git push
	@echo "Pushed. Watch it at: https://github.com/FieldPartnerLLC/FieldPartner/actions"

.PHONY: help setup dev test apk apk-local ios live ship
