.PHONY: all clean run install dmg snapshots

APP_NAME = Link.app
DMG_NAME ?= Link-macOS.dmg
CONTENTS = $(APP_NAME)/Contents
MACOS = $(CONTENTS)/MacOS
RESOURCES = $(CONTENTS)/Resources
PLUGINS = $(CONTENTS)/PlugIns
SHARE_APPEX = $(PLUGINS)/LinkShare.appex
QUICK_APPEX = $(PLUGINS)/LinkQuickAction.appex

# Minimum supported macOS. Without an explicit target, swiftc stamps the binary
# with the build machine's macOS version, which locks out older systems.
MACOS_MIN ?= 13.0
ARCH ?= $(shell uname -m)
SWIFT_TARGET = -target $(ARCH)-apple-macosx$(MACOS_MIN)

SWIFT_SOURCES = Sources/main.swift Sources/LinkNotificationModels.swift Sources/LinkFileTransferModels.swift Sources/AXNotificationObserver.swift Sources/LinkNotificationManager.swift Sources/LinkFileTransferManager.swift Sources/LinkServer.swift Sources/LinkBluetoothRelay.swift Sources/FastLinkManager.swift Sources/LinkModel.swift Sources/LinkMenuView.swift Sources/LinkDeviceStatus.swift Sources/LinkCallManager.swift Sources/LinkPhoneInternet.swift Sources/LinkWifiJoiner.swift Sources/LinkWifiShare.swift

all: $(APP_NAME)

$(APP_NAME): $(SWIFT_SOURCES) Info.plist ShareExtension/Info.plist ShareExtension/ShareController.swift ShareExtension/LinkShare.entitlements QuickActionExtension/Info.plist
	@mkdir -p $(MACOS) $(RESOURCES) $(SHARE_APPEX)/Contents/MacOS $(SHARE_APPEX)/Contents/Resources $(QUICK_APPEX)/Contents/MacOS $(QUICK_APPEX)/Contents/Resources
	@cp Info.plist $(CONTENTS)/Info.plist
	@cp -R Resources/* $(RESOURCES)/ 2>/dev/null || true
	@rm -rf $(RESOURCES)/QuickAction $(RESOURCES)/Link.icon $(RESOURCES)/dmg 2>/dev/null || true
	@ACTOOL=$$(xcrun --find actool 2>/dev/null || ls /Applications/Xcode*.app/Contents/Developer/usr/bin/actool 2>/dev/null | head -n 1); \
	if [ -n "$$ACTOOL" ] && [ -d Resources/Link.icon ]; then \
		mkdir -p .build-icon; \
		"$$ACTOOL" Resources/Link.icon --compile .build-icon --platform macosx --minimum-deployment-target $(MACOS_MIN) --app-icon Link --output-partial-info-plist .build-icon/partial.plist >/dev/null 2>&1 && cp .build-icon/Assets.car $(RESOURCES)/Assets.car || true; \
		rm -rf .build-icon; \
	fi
	swiftc $(SWIFT_SOURCES) -o $(MACOS)/Link -wmo -framework AppKit -framework SwiftUI -framework Network -framework CryptoKit -framework CoreImage -framework CoreText -framework UserNotifications -framework ApplicationServices -framework UniformTypeIdentifiers -framework CoreBluetooth -framework CoreWLAN -framework CoreLocation $(SWIFT_TARGET) -O
	@cp ShareExtension/Info.plist $(SHARE_APPEX)/Contents/Info.plist
	@cp Resources/Link.icns $(SHARE_APPEX)/Contents/Resources/Link.icns 2>/dev/null || true
	swiftc ShareExtension/ShareController.swift -module-name LinkShare -o $(SHARE_APPEX)/Contents/MacOS/LinkShare -framework AppKit -framework Foundation -framework UniformTypeIdentifiers -Xlinker -e -Xlinker _NSExtensionMain $(SWIFT_TARGET) -O
	@cp QuickActionExtension/Info.plist $(QUICK_APPEX)/Contents/Info.plist
	@cp Resources/Link.icns $(QUICK_APPEX)/Contents/Resources/Link.icns 2>/dev/null || true
	@cp Resources/MenuBarIcon@2x.png $(QUICK_APPEX)/Contents/Resources/NSActionTemplate.png 2>/dev/null || true
	swiftc ShareExtension/ShareController.swift -module-name LinkQuickAction -o $(QUICK_APPEX)/Contents/MacOS/LinkQuickAction -framework AppKit -framework Foundation -framework UniformTypeIdentifiers -Xlinker -e -Xlinker _NSExtensionMain $(SWIFT_TARGET) -O
	@xattr -cr $(APP_NAME) 2>/dev/null || true
	@codesign --force --sign - --entitlements ShareExtension/LinkShare.entitlements --identifier com.link.bridge.share $(SHARE_APPEX)
	@codesign --force --sign - --entitlements ShareExtension/LinkShare.entitlements --identifier com.link.bridge.quickaction $(QUICK_APPEX)
	@codesign --force --sign - --identifier com.link.bridge -r='designated => identifier "com.link.bridge"' $(APP_NAME)
	@/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f $(APP_NAME) 2>/dev/null || true
	@echo "Built $(APP_NAME) successfully!"

run: $(APP_NAME)
	@killall Link 2>/dev/null || true
	@killall ClipboardSyncDaemon 2>/dev/null || true
	@sleep 0.4
	open /Applications/$(APP_NAME) 2>/dev/null || open $(APP_NAME)
	@echo "Link is running in your Mac menu bar!"

install: $(APP_NAME)
	@rm -rf /Applications/$(APP_NAME)
	@cp -R $(APP_NAME) /Applications/
	@xattr -cr /Applications/$(APP_NAME) 2>/dev/null || true
	@codesign --force --sign - --entitlements ShareExtension/LinkShare.entitlements --identifier com.link.bridge.share /Applications/$(APP_NAME)/Contents/PlugIns/LinkShare.appex
	@codesign --force --sign - --entitlements ShareExtension/LinkShare.entitlements --identifier com.link.bridge.quickaction /Applications/$(APP_NAME)/Contents/PlugIns/LinkQuickAction.appex
	@codesign --force --sign - --identifier com.link.bridge -r='designated => identifier "com.link.bridge"' /Applications/$(APP_NAME)
	@/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f /Applications/$(APP_NAME) 2>/dev/null || true
	@pluginkit -a /Applications/$(APP_NAME)/Contents/PlugIns/LinkShare.appex 2>/dev/null || true
	@pluginkit -e use -i com.link.bridge.share 2>/dev/null || true
	@pluginkit -a /Applications/$(APP_NAME)/Contents/PlugIns/LinkQuickAction.appex 2>/dev/null || true
	@pluginkit -e use -i com.link.bridge.quickaction 2>/dev/null || true
	@python3 -c "import plistlib, os; p=os.path.expanduser('~/Library/Preferences/com.apple.Sharing.plist'); d=plistlib.load(open(p,'rb')) if os.path.exists(p) else {}; ord=d.get('ExtensionsPreferences.ShareMenu.displayOrder',[]); ord.append('com.link.bridge.share') if 'com.link.bridge.share' not in ord else None; d['ExtensionsPreferences.ShareMenu.displayOrder']=ord; plistlib.dump(d,open(p,'wb'))" 2>/dev/null || true
	@killall -HUP sharingd 2>/dev/null || true
	@mkdir -p "$(HOME)/Library/Services/Share with Link.workflow/Contents/Resources"
	@cp Resources/QuickAction/Info.plist "$(HOME)/Library/Services/Share with Link.workflow/Contents/Info.plist"
	@cp Resources/QuickAction/document.wflow "$(HOME)/Library/Services/Share with Link.workflow/Contents/document.wflow"
	@cp Resources/QuickAction/document.wflow "$(HOME)/Library/Services/Share with Link.workflow/Contents/Resources/document.wflow"
	@defaults write pbs FinderActive -dict-add "APPEXTENSION-com.link.bridge.quickaction" -bool true 2>/dev/null || true
	@defaults write pbs FinderActive -dict-add "SERVICE-com.link.bridge.workflow - Share with Link - runWorkflowAsService" -bool true 2>/dev/null || true
	@/System/Library/CoreServices/pbs -flush 2>/dev/null || true
	@/System/Library/CoreServices/pbs -update 2>/dev/null || true
	@swift -e 'import Foundation; DistributedNotificationCenter.default().postNotificationName(NSNotification.Name("_NSQuickActionConfigurationDidChangeNotification"), object: nil, userInfo: nil, deliverImmediately: true)' 2>/dev/null || true
	@touch /Applications/$(APP_NAME)
	@echo "Installed Link to /Applications/Link.app and registered Finder right-click Quick Action & Share Extension"

dmg: $(APP_NAME)
	@./scripts/make-dmg.sh "$(APP_NAME)" "$(DMG_NAME)"

clean:
	rm -rf $(APP_NAME) .build-tests .build-icon *.dmg

.PHONY: test
test:
	@mkdir -p .build-tests
	swiftc Tests/LinkTests.swift Sources/LinkNotificationModels.swift Sources/LinkFileTransferModels.swift Sources/AXNotificationObserver.swift Sources/LinkNotificationManager.swift Sources/LinkFileTransferManager.swift Sources/LinkServer.swift Sources/LinkBluetoothRelay.swift Sources/FastLinkManager.swift Sources/LinkModel.swift Sources/LinkMenuView.swift Sources/LinkDeviceStatus.swift Sources/LinkCallManager.swift Sources/LinkPhoneInternet.swift Sources/LinkWifiJoiner.swift Sources/LinkWifiShare.swift -o .build-tests/LinkTests -framework AppKit -framework SwiftUI -framework Network -framework CryptoKit -framework CoreImage -framework CoreText -framework UserNotifications -framework ApplicationServices -framework UniformTypeIdentifiers -framework CoreBluetooth -framework CoreWLAN -framework CoreLocation $(SWIFT_TARGET)
	@.build-tests/LinkTests
	@rm -rf .build-tests

# Design screenshots: renders LinkMenuView for each screen in light+dark.
# No networking/Bluetooth/Wi-Fi: LinkModel(startServer: false) + injected state.
SNAPSHOT_SOURCES = $(filter-out Sources/main.swift,$(SWIFT_SOURCES)) scripts/snapshot/main.swift
SNAPSHOT_BIN = .build-snapshot/LinkSnapshot
SNAPSHOT_DIR = ../design/screenshots
SNAPSHOT_SCREENS = not-paired devices notifications transfers settings call-ringing apps-settings wifi-share phone-internet
SNAPSHOT_MODES = light dark

snapshots: $(SNAPSHOT_BIN)
	@mkdir -p $(SNAPSHOT_DIR)
	@for screen in $(SNAPSHOT_SCREENS); do \
		for mode in $(SNAPSHOT_MODES); do \
			$(SNAPSHOT_BIN) $$screen $$mode $(SNAPSHOT_DIR)/mac-$$screen-$$mode.png || exit 1; \
		done; \
	done
	@echo "Snapshots written to $(SNAPSHOT_DIR)"

$(SNAPSHOT_BIN): $(SNAPSHOT_SOURCES)
	@mkdir -p .build-snapshot
	swiftc $(SNAPSHOT_SOURCES) -o $(SNAPSHOT_BIN) -framework AppKit -framework SwiftUI -framework Network -framework CryptoKit -framework CoreImage -framework CoreText -framework UserNotifications -framework ApplicationServices -framework UniformTypeIdentifiers -framework CoreBluetooth -framework CoreWLAN -framework CoreLocation $(SWIFT_TARGET)
