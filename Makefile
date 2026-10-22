SHELL := /bin/bash

-include .env
PHONE_HOST ?= pixel-5
SSH_PORT   ?= 8022
APK        := app/build/outputs/apk/debug/app-debug.apk

# JDK 17 is required. Prefer an explicit JAVA_HOME (from the environment or .env),
# then Homebrew's openjdk@17 on either prefix, then whatever java_home resolves
# for 17. Nothing here hardcodes a single machine's layout.
JDK_HOME := $(shell \
	if [ -n "$$JAVA_HOME" ] && [ -x "$$JAVA_HOME/bin/javac" ]; then echo "$$JAVA_HOME"; \
	elif [ -x /opt/homebrew/opt/openjdk@17/bin/javac ]; then echo /opt/homebrew/opt/openjdk@17; \
	elif [ -x /usr/local/opt/openjdk@17/bin/javac ]; then echo /usr/local/opt/openjdk@17; \
	else /usr/libexec/java_home -v 17 2>/dev/null || true; fi)

export JAVA_HOME := $(JDK_HOME)
export ANDROID_HOME ?= $(HOME)/Library/Android/sdk
export PATH := $(JAVA_HOME)/bin:$(PATH)

.PHONY: build test lint check install adb-install push-model bump clean

build:
	@test -n "$(JAVA_HOME)" || (echo "JDK 17 not found. Install it (brew install openjdk@17) or set JAVA_HOME." && exit 1)
	./gradlew assembleDebug
	@echo "APK: $(APK)"

test:
	./gradlew testDebugUnitTest

## Translation consistency: same keys and placeholders across every locale.
lint:
	python3 scripts/check_locales.py

## The commit gate: what CI runs on every PR and before a release.
check: lint test
	@echo "All checks passed."

install: build
	scp -P $(SSH_PORT) $(APK) $(PHONE_HOST):~/storage/downloads/openwhispr.apk
	ssh -p $(SSH_PORT) $(PHONE_HOST) "termux-open ~/storage/downloads/openwhispr.apk"
	@echo "APK sent — approve install on phone"

adb-install: build
	$(ANDROID_HOME)/platform-tools/adb install -r $(APK)

## Push a model to the phone's internal storage (usage: make push-model MODEL=/path/to/model-dir)
push-model:
	@test -n "$(MODEL)" || (echo "Usage: make push-model MODEL=/path/to/model-dir" && exit 1)
	$(ANDROID_HOME)/platform-tools/adb push $(MODEL)/ /data/local/tmp/$(notdir $(MODEL))/
	$(ANDROID_HOME)/platform-tools/adb shell "run-as com.edib.openwhispr mkdir -p files/models/$(notdir $(MODEL))"
	@for f in $$(ls $(MODEL)/*.onnx $(MODEL)/*.ort $(MODEL)/*.txt 2>/dev/null); do \
		echo "  copying $$(basename $$f)..."; \
		$(ANDROID_HOME)/platform-tools/adb shell "run-as com.edib.openwhispr cp /data/local/tmp/$(notdir $(MODEL))/$$(basename $$f) files/models/$(notdir $(MODEL))/$$(basename $$f)"; \
	done
	@echo "Model pushed: $(notdir $(MODEL))"

## Bump the version and open a CHANGELOG section (usage: make bump VERSION=4.1.0)
bump:
	@test -n "$(VERSION)" || (echo "Usage: make bump VERSION=x.y.z" && exit 1)
	python3 scripts/bump_version.py $(VERSION)

clean:
	./gradlew clean
