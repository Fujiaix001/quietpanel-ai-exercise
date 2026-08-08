ARCHS = armv7
TARGET = iphone:clang:9.3:9.0
INSTALL_TARGET_PROCESSES = QuietPanel
THEOS_PLATFORM_DEB_COMPRESSION_TYPE = gzip

include $(THEOS)/makefiles/common.mk

APPLICATION_NAME = QuietPanel
QuietPanel_FILES = main.m
QuietPanel_FRAMEWORKS = UIKit AVFoundation CoreMedia Photos QuartzCore
QuietPanel_CFLAGS = -fobjc-arc -Wall -Wextra -Wno-error=deprecated-module-dot-map

include $(THEOS_MAKE_PATH)/application.mk
