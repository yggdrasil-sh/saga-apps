#!/bin/zsh
# Reine Logik der App (ohne SwiftUI/libsodium) mit swiftc pruefen
set -e
H=${0:A:h}; A=$H/../../App
B=$(mktemp -d); trap 'rm -rf $B' EXIT
swiftc -swift-version 5 -o $B/tests $H/main.swift $H/V2Tests.swift $H/FixTests.swift $H/BughuntTests.swift $H/MediaTests.swift $H/V21bTests.swift $H/V21cTests.swift $H/CallAudioTests.swift $H/BH2Tests.swift $H/LangTests.swift $H/PersonaTests.swift $H/ChatDeleteTests.swift $H/DateTests.swift $H/CatalogTests.swift $H/HeaderTests.swift $H/PerfTests.swift $A/../Shared/DateFormats.swift $A/PageLogic.swift $A/SaveLogic.swift $A/ShareLogic.swift $A/LiveLogic.swift $A/FederationLogic.swift \
  $A/PushLogic.swift $A/AILogic.swift $A/ChatLogic.swift $A/MediaLogic.swift $A/AccountLogic.swift $A/CallLogic.swift $A/ChatMediaLogic.swift $A/BlockClipLogic.swift $A/LanguageLogic.swift $A/PageHeaderLogic.swift $A/PageListLogic.swift
$B/tests
