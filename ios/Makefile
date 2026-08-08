ARCHS = armv7
TARGET = iphone:clang:9.3:9.0
INSTALL_TARGET_PROCESSES = LegacyPadDisplay
THEOS_PLATFORM_DEB_COMPRESSION_TYPE = gzip

include $(THEOS)/makefiles/common.mk

APPLICATION_NAME = LegacyPadDisplay
LegacyPadDisplay_FILES = main.m
LegacyPadDisplay_FRAMEWORKS = UIKit AVFoundation CoreMedia QuartzCore
LegacyPadDisplay_CFLAGS = -fobjc-arc -Wall -Wextra -Wno-error=deprecated-module-dot-map

include $(THEOS_MAKE_PATH)/application.mk
