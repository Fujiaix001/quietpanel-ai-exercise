#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <math.h>

typedef struct {
    size_t offset;
    size_t length;
} LegacyNALRange;

static size_t LegacyFindAnnexBNALUnits(const uint8_t *bytes, size_t length,
                                       LegacyNALRange *ranges, size_t capacity) {
    size_t count = 0;
    size_t start = SIZE_MAX;

    for (size_t i = 0; i + 4 <= length;) {
        if (bytes[i] == 0 && bytes[i + 1] == 0 &&
            bytes[i + 2] == 0 && bytes[i + 3] == 1) {
            if (start != SIZE_MAX && start < i && count < capacity) {
                ranges[count++] = (LegacyNALRange){start, i - start};
            }
            start = i + 4;
            i += 4;
        } else {
            i++;
        }
    }

    if (start != SIZE_MAX && start < length && count < capacity) {
        ranges[count++] = (LegacyNALRange){start, length - start};
    }
    return count;
}

static int LegacyPayloadContainsVideo(const uint8_t *bytes, size_t length) {
    LegacyNALRange range;
    return LegacyFindAnnexBNALUnits(bytes, length, &range, 1) > 0;
}

static int LegacyShouldDecodeVideo(int displayActive,
                                   const uint8_t *bytes, size_t length) {
    return displayActive && LegacyPayloadContainsVideo(bytes, length);
}

enum { QuietPageCount = 5 };

static double QuietClampClockScale(double scale) {
    if (!isfinite(scale)) return 1.0;
    return fmin(2.5, fmax(0.75, scale));
}

static double QuietRenderingScale(double screenScale, double viewScale) {
    if (!isfinite(screenScale) || screenScale <= 0) screenScale = 1.0;
    if (!isfinite(viewScale) || viewScale < 1.0) viewScale = 1.0;
    return screenScale * viewScale;
}

static size_t QuietAlbumImportBatchCount(size_t remaining) {
    return remaining < 10 ? remaining : 10;
}

typedef enum {
    QuietWeatherClear,
    QuietWeatherCloudy,
    QuietWeatherFog,
    QuietWeatherRain,
    QuietWeatherSnow,
    QuietWeatherThunder
} QuietWeatherKind;

static QuietWeatherKind QuietWeatherKindForCode(int code) {
    if (code == 0) return QuietWeatherClear;
    if (code == 45 || code == 48) return QuietWeatherFog;
    if ((code >= 71 && code <= 77) || (code >= 85 && code <= 86)) {
        return QuietWeatherSnow;
    }
    if (code >= 95) return QuietWeatherThunder;
    if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) {
        return QuietWeatherRain;
    }
    return QuietWeatherCloudy;
}

static int LegacyShouldRestartServer(int backgrounded) {
    return !backgrounded;
}

static size_t QuietNormalizePages(const int *requested, size_t count,
                                  uint8_t enabled[QuietPageCount]) {
    memset(enabled, 0, QuietPageCount);
    size_t enabledCount = 0;
    for (size_t i = 0; i < count; i++) {
        int page = requested[i];
        if (page >= 0 && page < QuietPageCount && !enabled[page]) {
            enabled[page] = 1;
            enabledCount++;
        }
    }
    return enabledCount;
}

typedef struct {
    double minimum;
    double maximum;
} QuietScalarRange;

static QuietScalarRange QuietClockCenterRange(double viewport, double panel,
                                               double overflowFraction) {
    if (!isfinite(viewport) || !isfinite(panel) || viewport <= 0 || panel <= 0) {
        return (QuietScalarRange){0, 0};
    }
    overflowFraction = fmin(0.45, fmax(0.0, overflowFraction));
    double overflow = panel * overflowFraction;
    double minimum = panel / 2.0 + 12.0 - overflow;
    double maximum = viewport - panel / 2.0 - 12.0 + overflow;
    if (maximum < minimum) minimum = maximum = viewport / 2.0;
    return (QuietScalarRange){minimum, maximum};
}

static int QuietNormalizeVideoPoint(double pointX, double pointY,
                                    double viewWidth, double viewHeight,
                                    double videoWidth, double videoHeight,
                                    double *normalizedX, double *normalizedY) {
    if (!normalizedX || !normalizedY ||
        !isfinite(pointX) || !isfinite(pointY) ||
        viewWidth <= 0 || viewHeight <= 0 ||
        videoWidth <= 0 || videoHeight <= 0) return 0;

    double scale = fmin(viewWidth / videoWidth, viewHeight / videoHeight);
    double width = videoWidth * scale;
    double height = videoHeight * scale;
    double originX = (viewWidth - width) / 2.0;
    double originY = (viewHeight - height) / 2.0;
    if (pointX < originX || pointX > originX + width ||
        pointY < originY || pointY > originY + height) return 0;

    *normalizedX = fmin(1.0, fmax(0.0, (pointX - originX) / width));
    *normalizedY = fmin(1.0, fmax(0.0, (pointY - originY) / height));
    return 1;
}

#ifdef LEGACY_PROTOCOL_TEST

#include <assert.h>
#include <stdio.h>

int main(void) {
    const uint8_t frame[] = {
        '{', 'x', '}',
        0, 0, 0, 1, 0x67, 0x11,
        0, 0, 0, 1, 0x68, 0x22,
        0, 0, 0, 1, 0x65, 0x33, 0x44
    };
    LegacyNALRange ranges[4];
    size_t count = LegacyFindAnnexBNALUnits(frame, sizeof(frame), ranges, 4);
    assert(count == 3);
    assert(ranges[0].length == 2 && frame[ranges[0].offset] == 0x67);
    assert(ranges[1].length == 2 && frame[ranges[1].offset] == 0x68);
    assert(ranges[2].length == 3 && frame[ranges[2].offset] == 0x65);

    const uint8_t prefixedPFrame[] = {
        '{', '"', 'c', 'a', 'p', '"', ':', '1', '}',
        0, 0, 0, 1, 0x41, 0x11, 0x22
    };
    const uint8_t control[] = {'{', '"', 't', 'y', 'p', 'e', '"', ':', '"', 'p', 'i', 'n', 'g', '"', '}'};
    assert(LegacyPayloadContainsVideo(prefixedPFrame, sizeof(prefixedPFrame)));
    assert(!LegacyPayloadContainsVideo(control, sizeof(control)));
    assert(LegacyShouldDecodeVideo(1, prefixedPFrame, sizeof(prefixedPFrame)));
    assert(!LegacyShouldDecodeVideo(0, prefixedPFrame, sizeof(prefixedPFrame)));

    const int requested[] = {4, 1, 4, -1, 9};
    uint8_t enabled[QuietPageCount];
    assert(QuietNormalizePages(requested, 5, enabled) == 2);
    assert(!enabled[0] && enabled[1] && !enabled[2] && !enabled[3] && enabled[4]);
    assert(QuietNormalizePages(requested, 0, enabled) == 0);
    assert(QuietWeatherKindForCode(0) == QuietWeatherClear);
    assert(QuietWeatherKindForCode(2) == QuietWeatherCloudy);
    assert(QuietWeatherKindForCode(45) == QuietWeatherFog);
    assert(QuietWeatherKindForCode(61) == QuietWeatherRain);
    assert(QuietWeatherKindForCode(73) == QuietWeatherSnow);
    assert(QuietWeatherKindForCode(95) == QuietWeatherThunder);
    assert(LegacyShouldRestartServer(0));
    assert(!LegacyShouldRestartServer(1));
    assert(QuietClampClockScale(0.1) == 0.75);
    assert(QuietClampClockScale(1.4) == 1.4);
    assert(QuietClampClockScale(8.0) == 2.5);
    assert(QuietClampClockScale(NAN) == 1.0);
    assert(fabs(QuietRenderingScale(1.0, 1.4) - 1.4) < 0.0001);
    assert(QuietRenderingScale(2.0, 0.75) == 2.0);
    assert(QuietRenderingScale(NAN, NAN) == 1.0);
    assert(QuietAlbumImportBatchCount(0) == 0);
    assert(QuietAlbumImportBatchCount(7) == 7);
    assert(QuietAlbumImportBatchCount(780) == 10);
    QuietScalarRange strictRange = QuietClockCenterRange(1024, 1000, 0);
    QuietScalarRange overflowRange = QuietClockCenterRange(1024, 1000, 0.2);
    assert(strictRange.minimum == 512 && strictRange.maximum == 512);
    assert(overflowRange.minimum == 312 && overflowRange.maximum == 712);
    double x = 0, y = 0;
    assert(QuietNormalizeVideoPoint(500, 500, 1000, 1000, 1600, 900, &x, &y));
    assert(fabs(x - 0.5) < 0.0001 && fabs(y - 0.5) < 0.0001);
    assert(!QuietNormalizeVideoPoint(500, 100, 1000, 1000, 1600, 900, &x, &y));
    assert(QuietNormalizeVideoPoint(0, 500, 1000, 1000, 1600, 900, &x, &y));
    assert(x == 0.0 && fabs(y - 0.5) < 0.0001);
    assert(!QuietNormalizeVideoPoint(0, 0, 0, 1000, 1600, 900, &x, &y));
    puts("protocol parser: ok");
    return 0;
}

#else

#import <UIKit/UIKit.h>
#import <AVFoundation/AVFoundation.h>
#import <CoreMedia/CoreMedia.h>
#import <Photos/Photos.h>
#import <QuartzCore/QuartzCore.h>
#import <arpa/inet.h>
#import <errno.h>
#import <netinet/in.h>
#import <netinet/tcp.h>
#import <signal.h>
#import <sys/socket.h>
#import <unistd.h>

static const uint16_t kLegacyPort = 9001;
static const uint32_t kMaximumPayload = 16 * 1024 * 1024;
static NSString *const kQuietAllPhotosIdentifier = @"@all";
static NSString *const kQuietPhotoAlbumIDsKey = @"QuietPanel.photoAlbumIDs";
static NSString *const kQuietPhotoIntervalKey = @"QuietPanel.photoInterval";
static NSString *const kQuietTimeFontKey = @"QuietPanel.timeFont";
static NSString *const kQuietDateFontKey = @"QuietPanel.dateFont";
static NSString *const kQuietWeatherFontKey = @"QuietPanel.weatherFont";
static NSString *const kQuietWeatherEnabledKey = @"QuietPanel.weatherEnabled";
static NSString *const kQuietWeatherLocationKey = @"QuietPanel.weatherLocation";
static NSString *const kQuietWeatherDaylightKey = @"QuietPanel.weatherDaylight";
static NSString *const kQuietWeatherCacheKey = @"QuietPanel.weatherCache";
static NSString *const kQuietClockBackgroundKey = @"QuietPanel.clockBackground";
static NSString *const kQuietClockScaleKey = @"QuietPanel.clockScale";
static NSString *const kQuietClockXRatioKey = @"QuietPanel.clockXRatio";
static NSString *const kQuietClockYRatioKey = @"QuietPanel.clockYRatio";
static NSString *const kQuietClockPositionKey = @"QuietPanel.clockPositionCustomized";
static NSString *const kQuietClockPositionRangeKey = @"QuietPanel.clockPositionRange";
static NSString *const kQuietAlbumImportRoot = @"QuietPanelImports";
static NSString *const kQuietAlbumImportReady = @".ready";
static NSString *const kQuietAlbumImportState = @".state.plist";

static void QuietApplyContentsScale(UIView *view, CGFloat contentsScale) {
    if (fabs(view.layer.contentsScale - contentsScale) > 0.001) {
        view.contentScaleFactor = contentsScale;
        view.layer.contentsScale = contentsScale;
        [view setNeedsDisplay];
    }
    for (UIView *subview in view.subviews) {
        QuietApplyContentsScale(subview, contentsScale);
    }
}

static NSArray *QuietFontNames(void) {
    static NSArray *names;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        names = @[
            @"系統字形", @"DotGothic16", @"Noto Sans JP", @"Noto Serif JP",
            @"Zen Maru Gothic", @"Klee One", @"Dela Gothic One", @"Orbitron",
            @"Audiowide", @"Oxanium", @"Saira Stencil One", @"Zen Dots",
            @"LittleClock 粉圓體", @"芫荽 Iansui", @"Storopia（私人）",
            @"Michroma", @"Share Tech Mono", @"Righteous", @"Bungee",
            @"Space Mono", @"Monoton"
        ];
    });
    return names;
}

static NSArray *QuietFontPostScriptNames(void) {
    static NSArray *names;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        names = @[
            [NSNull null], @"DotGothic16-Regular", @"NotoSansJP-Thin",
            @"NotoSerifJP-ExtraLight", @"ZenMaruGothic-Medium", @"KleeOne-SemiBold",
            @"DelaGothicOne-Regular", @"Orbitron-Regular", @"Audiowide-Regular",
            @"Oxanium-ExtraLight", @"SairaStencilOne-Regular", @"ZenDots-Regular",
            @"LittleClock-FenYuan", @"Iansui-Regular", @"Storopia",
            @"Michroma-Regular", @"ShareTechMono-Regular", @"Righteous-Regular",
            @"Bungee-Regular", @"SpaceMono-Regular", @"Monoton-Regular"
        ];
    });
    return names;
}

static NSInteger QuietNormalizedFontIndex(NSInteger index) {
    return index >= 0 && index < (NSInteger)QuietFontNames().count ? index : 0;
}

static UIFont *QuietFontAtIndex(NSInteger index, CGFloat size, UIFont *fallback) {
    index = QuietNormalizedFontIndex(index);
    if (index == 0) return fallback;
    id name = QuietFontPostScriptNames()[index];
    UIFont *font = [name isKindOfClass:[NSString class]]
        ? [UIFont fontWithName:name size:size] : nil;
    return font ?: fallback;
}

static BOOL QuietFontUsesEnglishDate(NSInteger index) {
    index = QuietNormalizedFontIndex(index);
    return index >= 7 && index != 12 && index != 13;
}

static BOOL QuietIsSyncedAlbum(PHAssetCollection *collection) {
    PHAssetCollectionSubtype subtype = collection.assetCollectionSubtype;
    return subtype == PHAssetCollectionSubtypeAlbumSyncedAlbum ||
           subtype == PHAssetCollectionSubtypeAlbumSyncedEvent ||
           subtype == PHAssetCollectionSubtypeAlbumSyncedFaces;
}

static BOOL LegacyReadFully(int socketFD, void *buffer, size_t length) {
    uint8_t *cursor = buffer;
    while (length > 0) {
        ssize_t received = recv(socketFD, cursor, length, 0);
        if (received == 0) return NO;
        if (received < 0) {
            if (errno == EINTR) continue;
            return NO;
        }
        cursor += received;
        length -= (size_t)received;
    }
    return YES;
}

static BOOL LegacyWriteFully(int socketFD, const void *buffer, size_t length) {
    const uint8_t *cursor = buffer;
    while (length > 0) {
        ssize_t written = send(socketFD, cursor, length, 0);
        if (written < 0) {
            if (errno == EINTR) continue;
            return NO;
        }
        cursor += written;
        length -= (size_t)written;
    }
    return YES;
}

@interface LegacyVideoView : UIView
@property (nonatomic, readonly) AVSampleBufferDisplayLayer *displayLayer;
@end

@implementation LegacyVideoView
+ (Class)layerClass { return [AVSampleBufferDisplayLayer class]; }
- (AVSampleBufferDisplayLayer *)displayLayer {
    return (AVSampleBufferDisplayLayer *)self.layer;
}
@end

@interface QuietWeatherIconView : UIView
- (void)setWeatherCode:(int)code daytime:(BOOL)daytime;
@end

@implementation QuietWeatherIconView {
    int _weatherCode;
    BOOL _daytime;
}

- (instancetype)initWithFrame:(CGRect)frame {
    self = [super initWithFrame:frame];
    if (self) {
        self.backgroundColor = [UIColor clearColor];
        self.opaque = NO;
        self.contentMode = UIViewContentModeRedraw;
        self.isAccessibilityElement = YES;
        _daytime = YES;
    }
    return self;
}

- (void)setWeatherCode:(int)code daytime:(BOOL)daytime {
    _weatherCode = code;
    _daytime = daytime;
    switch (QuietWeatherKindForCode(code)) {
        case QuietWeatherClear:
            self.accessibilityLabel = daytime ? @"晴天" : @"晴朗夜晚";
            break;
        case QuietWeatherCloudy: self.accessibilityLabel = @"多雲"; break;
        case QuietWeatherFog: self.accessibilityLabel = @"有霧"; break;
        case QuietWeatherRain: self.accessibilityLabel = @"下雨"; break;
        case QuietWeatherSnow: self.accessibilityLabel = @"下雪"; break;
        case QuietWeatherThunder: self.accessibilityLabel = @"雷雨"; break;
    }
    [self setNeedsDisplay];
}

- (void)prepareStroke:(CGContextRef)context width:(CGFloat)width color:(UIColor *)color {
    CGContextSetLineWidth(context, width);
    CGContextSetLineCap(context, kCGLineCapRound);
    CGContextSetLineJoin(context, kCGLineJoinRound);
    CGContextSetStrokeColorWithColor(context, color.CGColor);
}

- (void)drawClearInContext:(CGContextRef)context {
    UIColor *color = _daytime
        ? [UIColor colorWithRed:1.0 green:210.0 / 255.0 blue:75.0 / 255.0 alpha:1.0]
        : [UIColor colorWithRed:220.0 / 255.0 green:232.0 / 255.0 blue:246.0 / 255.0 alpha:1.0];
    [self prepareStroke:context width:2.8 color:color];
    if (_daytime) {
        CGContextStrokeEllipseInRect(context, CGRectMake(11, 11, 14, 14));
        for (NSInteger i = 0; i < 8; i++) {
            CGFloat angle = (CGFloat)M_PI * i / 4.0;
            CGContextMoveToPoint(context, 18 + cos(angle) * 11, 18 + sin(angle) * 11);
            CGContextAddLineToPoint(context, 18 + cos(angle) * 14, 18 + sin(angle) * 14);
        }
        CGContextStrokePath(context);
    } else {
        CGContextBeginPath(context);
        CGContextMoveToPoint(context, 25, 8);
        CGContextAddCurveToPoint(context, 13, 10, 11, 25, 22, 30);
        CGContextAddCurveToPoint(context, 11, 30, 6, 17, 13, 10);
        CGContextStrokePath(context);
    }
}

- (void)drawCloudInContext:(CGContextRef)context {
    CGContextBeginPath(context);
    CGContextMoveToPoint(context, 12, 34);
    CGContextAddCurveToPoint(context, 5, 34, 5, 24, 13, 23);
    CGContextAddCurveToPoint(context, 16, 14, 29, 14, 33, 23);
    CGContextAddCurveToPoint(context, 43, 22, 45, 34, 36, 36);
    CGContextAddLineToPoint(context, 13, 36);
    CGContextClosePath(context);
    CGContextSetFillColorWithColor(context,
        [UIColor colorWithRed:235.0 / 255.0 green:241.0 / 255.0 blue:245.0 / 255.0 alpha:1.0].CGColor);
    CGContextFillPath(context);
}

- (void)drawRainInContext:(CGContextRef)context {
    [self prepareStroke:context width:2.5 color:
        [UIColor colorWithRed:70.0 / 255.0 green:190.0 / 255.0 blue:225.0 / 255.0 alpha:1.0]];
    const CGFloat starts[] = {16, 25, 34};
    for (NSInteger i = 0; i < 3; i++) {
        CGContextMoveToPoint(context, starts[i], 39);
        CGContextAddLineToPoint(context, starts[i] - 2, 44);
    }
    CGContextStrokePath(context);
}

- (void)drawSnowInContext:(CGContextRef)context {
    [self prepareStroke:context width:2.0 color:[UIColor whiteColor]];
    const CGFloat centers[] = {16, 30};
    for (NSInteger i = 0; i < 2; i++) {
        CGContextMoveToPoint(context, centers[i], 40);
        CGContextAddLineToPoint(context, centers[i], 45);
        CGContextMoveToPoint(context, centers[i] - 2.5, 42.5);
        CGContextAddLineToPoint(context, centers[i] + 2.5, 42.5);
    }
    CGContextStrokePath(context);
}

- (void)drawThunderInContext:(CGContextRef)context {
    CGContextBeginPath(context);
    CGContextMoveToPoint(context, 25, 37);
    CGContextAddLineToPoint(context, 19, 45);
    CGContextAddLineToPoint(context, 25, 44);
    CGContextAddLineToPoint(context, 22, 48);
    CGContextAddLineToPoint(context, 33, 40);
    CGContextAddLineToPoint(context, 27, 41);
    CGContextClosePath(context);
    CGContextSetFillColorWithColor(context,
        [UIColor colorWithRed:1.0 green:210.0 / 255.0 blue:60.0 / 255.0 alpha:1.0].CGColor);
    CGContextFillPath(context);
}

- (void)drawFogInContext:(CGContextRef)context {
    [self prepareStroke:context width:3.0 color:
        [UIColor colorWithRed:220.0 / 255.0 green:228.0 / 255.0 blue:232.0 / 255.0 alpha:1.0]];
    const CGFloat lines[][4] = {{8, 18, 38, 18}, {13, 25, 42, 25}, {7, 32, 34, 32}};
    for (NSInteger i = 0; i < 3; i++) {
        CGContextMoveToPoint(context, lines[i][0], lines[i][1]);
        CGContextAddLineToPoint(context, lines[i][2], lines[i][3]);
    }
    CGContextStrokePath(context);
}

- (void)drawRect:(CGRect)rect {
    CGContextRef context = UIGraphicsGetCurrentContext();
    CGFloat scale = MIN(rect.size.width, rect.size.height) / 48.0;
    CGFloat offsetX = (rect.size.width - 48.0 * scale) / 2.0;
    CGFloat offsetY = (rect.size.height - 48.0 * scale) / 2.0;
    CGContextSaveGState(context);
    CGContextTranslateCTM(context, offsetX, offsetY);
    CGContextScaleCTM(context, scale, scale);
    CGContextSetShadowWithColor(context, CGSizeMake(0, 1), 1.5,
        [UIColor colorWithWhite:0 alpha:0.45].CGColor);

    switch (QuietWeatherKindForCode(_weatherCode)) {
        case QuietWeatherClear:
            [self drawClearInContext:context];
            break;
        case QuietWeatherFog:
            [self drawFogInContext:context];
            break;
        case QuietWeatherRain:
            [self drawCloudInContext:context];
            [self drawRainInContext:context];
            break;
        case QuietWeatherSnow:
            [self drawCloudInContext:context];
            [self drawSnowInContext:context];
            break;
        case QuietWeatherThunder:
            [self drawCloudInContext:context];
            [self drawThunderInContext:context];
            break;
        case QuietWeatherCloudy:
            [self drawClearInContext:context];
            [self drawCloudInContext:context];
            break;
    }
    CGContextRestoreGState(context);
}

@end

@interface LegacyReceiver : NSObject
- (instancetype)initWithDisplayLayer:(AVSampleBufferDisplayLayer *)displayLayer
                          cursorView:(UIImageView *)cursorView
                          pixelsWide:(NSInteger)pixelsWide
                          pixelsHigh:(NSInteger)pixelsHigh
                               scale:(CGFloat)scale
                      metricsHandler:(void (^)(NSDictionary *metrics))metricsHandler
                  pageConfigHandler:(void (^)(NSArray *enabledPages))pageConfigHandler
                        nasaHandler:(void (^)(NSDictionary *metadata, NSData *imageData))nasaHandler
                     weatherHandler:(void (^)(NSDictionary *weather))weatherHandler
                actionResultHandler:(void (^)(NSString *message, BOOL ok))actionResultHandler
                       statusHandler:(void (^)(NSString *status, BOOL connected))statusHandler;
- (void)start;
- (void)suspendForBackground;
- (void)resumeAfterBackground;
- (void)setDisplayActive:(BOOL)active page:(NSInteger)page;
- (BOOL)sendAction:(NSString *)action;
- (BOOL)sendTouchPhase:(NSString *)phase x:(double)x y:(double)y;
@end

@implementation LegacyReceiver {
    AVSampleBufferDisplayLayer *_displayLayer;
    UIImageView *_cursorView;
    NSInteger _pixelsWide;
    NSInteger _pixelsHigh;
    CGFloat _scale;
    void (^_metricsHandler)(NSDictionary *);
    void (^_pageConfigHandler)(NSArray *);
    void (^_nasaHandler)(NSDictionary *, NSData *);
    void (^_weatherHandler)(NSDictionary *);
    void (^_actionResultHandler)(NSString *, BOOL);
    void (^_statusHandler)(NSString *, BOOL);
    dispatch_queue_t _queue;
    int _serverFD;
    int _clientFD;
    BOOL _started;
    BOOL _backgrounded;
    NSData *_sps;
    NSData *_pps;
    CMVideoFormatDescriptionRef _formatDescription;
    CFTimeInterval _lastKeyframeRequest;
    BOOL _displayActive;
    NSInteger _displayPage;
    BOOL _decoderResetPending;
    uint64_t _nextActionID;
}

- (instancetype)initWithDisplayLayer:(AVSampleBufferDisplayLayer *)displayLayer
                          cursorView:(UIImageView *)cursorView
                          pixelsWide:(NSInteger)pixelsWide
                          pixelsHigh:(NSInteger)pixelsHigh
                               scale:(CGFloat)scale
                      metricsHandler:(void (^)(NSDictionary *))metricsHandler
                  pageConfigHandler:(void (^)(NSArray *))pageConfigHandler
                        nasaHandler:(void (^)(NSDictionary *, NSData *))nasaHandler
                     weatherHandler:(void (^)(NSDictionary *))weatherHandler
                actionResultHandler:(void (^)(NSString *, BOOL))actionResultHandler
                       statusHandler:(void (^)(NSString *, BOOL))statusHandler {
    self = [super init];
    if (self) {
        _displayLayer = displayLayer;
        _cursorView = cursorView;
        _displayLayer.videoGravity = AVLayerVideoGravityResizeAspect;
        _pixelsWide = pixelsWide;
        _pixelsHigh = pixelsHigh;
        _scale = scale;
        _metricsHandler = [metricsHandler copy];
        _pageConfigHandler = [pageConfigHandler copy];
        _nasaHandler = [nasaHandler copy];
        _weatherHandler = [weatherHandler copy];
        _actionResultHandler = [actionResultHandler copy];
        _statusHandler = [statusHandler copy];
        _queue = dispatch_queue_create("tw.codex.quietpanel.receiver", DISPATCH_QUEUE_SERIAL);
        _serverFD = -1;
        _clientFD = -1;
        _displayActive = NO;
        _displayPage = 0;
    }
    return self;
}

- (void)dealloc {
    if (_clientFD >= 0) close(_clientFD);
    if (_serverFD >= 0) close(_serverFD);
    if (_formatDescription) CFRelease(_formatDescription);
}

- (void)setStatus:(NSString *)status connected:(BOOL)connected {
    if (!_statusHandler) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        self->_statusHandler(status, connected);
    });
}

- (void)start {
    if (_started) return;
    _started = YES;
    signal(SIGPIPE, SIG_IGN);
    dispatch_async(_queue, ^{
        [self runServer];
    });
}

- (void)resumeAfterBackground {
    int clientFD;
    int serverFD;
    @synchronized (self) {
        _backgrounded = NO;
        clientFD = _clientFD;
        serverFD = _serverFD;
    }
    if (clientFD >= 0) {
        [self setStatus:@"正在重新連接 Mac" connected:NO];
        shutdown(clientFD, SHUT_RDWR);
    }
    if (serverFD < 0) {
        dispatch_async(_queue, ^{
            @synchronized (self) {
                if (self->_backgrounded || self->_serverFD >= 0) return;
            }
            [self runServer];
        });
    }
}

- (void)suspendForBackground {
    int clientFD;
    int serverFD;
    @synchronized (self) {
        _backgrounded = YES;
        clientFD = _clientFD;
        serverFD = _serverFD;
    }
    [self setStatus:@"已暫停，返回後自動連接" connected:NO];
    if (clientFD >= 0) {
        [self sendJSON:@{@"type": @"sleeping"} socket:clientFD];
        shutdown(clientFD, SHUT_RDWR);
    }

    // Wake accept() before iOS suspends the process. Old Darwin kernels do not
    // reliably unblock it when another thread closes the listening socket.
    if (serverFD >= 0) {
        int wakeFD = socket(AF_INET, SOCK_STREAM, 0);
        if (wakeFD >= 0) {
            struct sockaddr_in address;
            memset(&address, 0, sizeof(address));
            address.sin_family = AF_INET;
            address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
            address.sin_port = htons(kLegacyPort);
            connect(wakeFD, (struct sockaddr *)&address, sizeof(address));
            close(wakeFD);
        }
    }
}

- (void)setDisplayActive:(BOOL)active page:(NSInteger)page {
    int socketFD;
    BOOL activeChanged;
    @synchronized (self) {
        if (_displayActive == active && _displayPage == page) return;
        activeChanged = _displayActive != active;
        _displayActive = active;
        _displayPage = page;
        if (activeChanged) _decoderResetPending = YES;
        socketFD = _clientFD;
    }
    if (activeChanged) {
        dispatch_async(dispatch_get_main_queue(), ^{
            self->_cursorView.hidden = YES;
        });
    }
    if (socketFD >= 0) {
        [self sendJSON:@{ @"type": @"visible", @"v": @(active), @"page": @(page) }
                socket:socketFD];
        if (activeChanged && active) [self requestKeyframe];
    }
}

- (BOOL)sendAction:(NSString *)action {
    if (![action isKindOfClass:[NSString class]] || action.length == 0) return NO;
    int socketFD;
    uint64_t actionID;
    @synchronized (self) {
        socketFD = _clientFD;
        actionID = ++_nextActionID;
    }
    if (socketFD < 0) return NO;
    return [self sendJSON:@{
        @"v": @1, @"type": @"action", @"id": @(actionID), @"action": action
    } socket:socketFD];
}

- (BOOL)sendTouchPhase:(NSString *)phase x:(double)x y:(double)y {
    if (![phase isKindOfClass:[NSString class]] ||
        !isfinite(x) || !isfinite(y) || x < 0 || x > 1 || y < 0 || y > 1) return NO;
    int socketFD;
    BOOL displayActive;
    @synchronized (self) {
        socketFD = _clientFD;
        displayActive = _displayActive && _displayPage == 1;
    }
    if (!displayActive || socketFD < 0) return NO;
    return [self sendJSON:@{
        @"type": @"touch", @"phase": phase, @"x": @(x), @"y": @(y)
    } socket:socketFD];
}

- (void)runServer {
    int serverFD = socket(AF_INET, SOCK_STREAM, 0);
    if (serverFD < 0) {
        [self setStatus:@"Socket 建立失敗" connected:NO];
        return;
    }

    @synchronized (self) {
        if (_backgrounded) {
            close(serverFD);
            return;
        }
        _serverFD = serverFD;
    }

    int yes = 1;
    setsockopt(serverFD, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
    struct sockaddr_in address;
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_ANY);
    address.sin_port = htons(kLegacyPort);

    if (bind(serverFD, (struct sockaddr *)&address, sizeof(address)) != 0 ||
        listen(serverFD, 2) != 0) {
        [self setStatus:[NSString stringWithFormat:@"無法監聽 %u", kLegacyPort] connected:NO];
        close(serverFD);
        @synchronized (self) {
            if (_serverFD == serverFD) _serverFD = -1;
        }
        return;
    }

    [self setStatus:[NSString stringWithFormat:@"等待 Mac（連接埠 %u）", kLegacyPort]
            connected:NO];

    for (;;) {
        @synchronized (self) {
            if (_backgrounded) break;
        }
        int socketFD = accept(serverFD, NULL, NULL);
        if (socketFD < 0) {
            if (errno == EINTR) continue;
            break;
        }
        @synchronized (self) {
            if (_backgrounded) {
                close(socketFD);
                break;
            }
            _clientFD = socketFD;
        }
        setsockopt(socketFD, IPPROTO_TCP, TCP_NODELAY, &yes, sizeof(yes));
        [self resetDecoder];
        [self setStatus:@"已連接 Mac" connected:YES];
        if ([self sendHello:socketFD]) {
            BOOL displayActive;
            NSInteger displayPage;
            @synchronized (self) {
                displayActive = _displayActive;
                displayPage = _displayPage;
            }
            [self sendJSON:@{ @"type": @"visible", @"v": @(displayActive),
                              @"page": @(displayPage) }
                    socket:socketFD];
            if (displayActive) [self requestKeyframe];
            [self receiveFrames:socketFD];
        }
        @synchronized (self) {
            if (_clientFD == socketFD) _clientFD = -1;
        }
        close(socketFD);
        [self setStatus:@"連線中斷，等待重新連接" connected:NO];
    }

    close(serverFD);
    BOOL restart;
    @synchronized (self) {
        if (_serverFD == serverFD) _serverFD = -1;
        restart = LegacyShouldRestartServer(_backgrounded);
    }
    if (restart) {
        dispatch_async(_queue, ^{
            @synchronized (self) {
                if (self->_backgrounded || self->_serverFD >= 0) return;
            }
            [self runServer];
        });
    }
}

- (NSString *)installID {
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    NSString *installID = [defaults stringForKey:@"QuietPanel.installID"];
    if (!installID) {
        installID = [[NSUUID UUID] UUIDString];
        [defaults setObject:installID forKey:@"QuietPanel.installID"];
        [defaults synchronize];
    }
    return installID;
}

- (BOOL)sendJSON:(NSDictionary *)message socket:(int)socketFD {
    NSError *error = nil;
    NSData *payload = [NSJSONSerialization dataWithJSONObject:message options:0 error:&error];
    if (!payload || payload.length > UINT32_MAX) return NO;
    uint32_t length = htonl((uint32_t)payload.length);
    @synchronized (self) {
        return LegacyWriteFully(socketFD, &length, sizeof(length)) &&
               LegacyWriteFully(socketFD, payload.bytes, payload.length);
    }
}

- (void)requestKeyframe {
    CFTimeInterval now = CACurrentMediaTime();
    int socketFD;
    @synchronized (self) {
        socketFD = _clientFD;
        if (socketFD < 0 ||
            (_lastKeyframeRequest > 0 && now - _lastKeyframeRequest < 1.0)) return;
        _lastKeyframeRequest = now;
    }
    [self sendJSON:@{@"type": @"kf"} socket:socketFD];
}

- (BOOL)sendHello:(int)socketFD {
    return [self sendJSON:@{
        @"type": @"hello",
        @"pixelsWide": @(_pixelsWide),
        @"pixelsHigh": @(_pixelsHigh),
        @"scale": @(_scale),
        @"device": @"iPad",
        @"id": [self installID]
    } socket:socketFD];
}

- (void)receiveFrames:(int)socketFD {
    for (;;) {
        @autoreleasepool {
            uint32_t networkLength = 0;
            if (!LegacyReadFully(socketFD, &networkLength, sizeof(networkLength))) return;
            uint32_t length = ntohl(networkLength);
            if (length == 0 || length > kMaximumPayload) return;

            NSMutableData *payload = [NSMutableData dataWithLength:length];
            if (!LegacyReadFully(socketFD, payload.mutableBytes, length)) return;
            [self handlePayload:payload socket:socketFD];
        }
    }
}

- (void)handlePayload:(NSData *)payload socket:(int)socketFD {
    BOOL displayActive;
    BOOL resetDecoder;
    @synchronized (self) {
        displayActive = _displayActive;
        resetDecoder = _decoderResetPending;
        _decoderResetPending = NO;
    }
    if (resetDecoder) [self resetDecoder];

    const uint8_t *bytes = payload.bytes;
    if (payload.length > 0 && bytes[0] == '{' &&
        !LegacyPayloadContainsVideo(bytes, payload.length)) {
        [self handleControl:payload socket:socketFD];
    } else if (LegacyShouldDecodeVideo(displayActive, bytes, payload.length)) {
        [self handleAnnexB:payload];
    }
}

- (void)handleControl:(NSData *)payload socket:(int)socketFD {
    NSDictionary *message = [NSJSONSerialization JSONObjectWithData:payload options:0 error:nil];
    if (![message isKindOfClass:[NSDictionary class]]) return;
    if ([[message objectForKey:@"type"] isEqual:@"ping"]) {
        NSNumber *timestamp = [message objectForKey:@"t"] ?: @0;
        NSTimeInterval milliseconds = [[NSDate date] timeIntervalSince1970] * 1000.0;
        [self sendJSON:@{@"type": @"pong", @"t": timestamp, @"mt": @(milliseconds)}
                 socket:socketFD];
    } else if ([[message objectForKey:@"type"] isEqual:@"quietState"]) {
        if (_metricsHandler) {
            NSDictionary *snapshot = message;
            dispatch_async(dispatch_get_main_queue(), ^{
                self->_metricsHandler(snapshot);
            });
        }
    } else if ([[message objectForKey:@"type"] isEqual:@"page_config"]) {
        NSArray *enabled = [message objectForKey:@"enabled"];
        if (_pageConfigHandler && [enabled isKindOfClass:[NSArray class]]) {
            dispatch_async(dispatch_get_main_queue(), ^{
                self->_pageConfigHandler(enabled);
            });
        }
    } else if ([[message objectForKey:@"type"] isEqual:@"nasa_state"]) {
        NSString *encoded = [message objectForKey:@"imageBase64"];
        if (_nasaHandler && [encoded isKindOfClass:[NSString class]] &&
            encoded.length > 0 && encoded.length <= 12 * 1024 * 1024) {
            NSData *imageData = [[NSData alloc] initWithBase64EncodedString:encoded options:0];
            if (imageData.length > 0 && imageData.length <= 8 * 1024 * 1024) {
                NSMutableDictionary *metadata = [message mutableCopy];
                [metadata removeObjectForKey:@"imageBase64"];
                dispatch_async(dispatch_get_main_queue(), ^{
                    self->_nasaHandler(metadata, imageData);
                });
            }
        }
    } else if ([[message objectForKey:@"type"] isEqual:@"weather_state"]) {
        NSDictionary *weather = [message objectForKey:@"weather"];
        if (_weatherHandler && [weather isKindOfClass:[NSDictionary class]]) {
            dispatch_async(dispatch_get_main_queue(), ^{
                self->_weatherHandler(weather);
            });
        }
    } else if ([[message objectForKey:@"type"] isEqual:@"action_result"]) {
        NSString *result = [message objectForKey:@"message"];
        BOOL ok = [[message objectForKey:@"ok"] boolValue];
        if (_actionResultHandler && [result isKindOfClass:[NSString class]]) {
            dispatch_async(dispatch_get_main_queue(), ^{
                self->_actionResultHandler(result, ok);
            });
        }
    } else if ([[message objectForKey:@"type"] isEqual:@"cursor"]) {
        BOOL visible = [[message objectForKey:@"v"] boolValue];
        CGFloat x = [[message objectForKey:@"x"] doubleValue];
        CGFloat y = [[message objectForKey:@"y"] doubleValue];
        BOOL displayActive;
        @synchronized (self) {
            displayActive = _displayActive;
        }
        dispatch_async(dispatch_get_main_queue(), ^{
            self->_cursorView.hidden = !visible || !displayActive;
            if (visible && displayActive) {
                self->_cursorView.layer.position = CGPointMake(
                    x * self->_displayLayer.bounds.size.width,
                    y * self->_displayLayer.bounds.size.height);
            }
        });
    } else if ([[message objectForKey:@"type"] isEqual:@"cursorImg"]) {
        NSString *encoded = [message objectForKey:@"png"];
        CGFloat nw = [[message objectForKey:@"nw"] doubleValue];
        CGFloat nh = [[message objectForKey:@"nh"] doubleValue];
        CGFloat ax = [[message objectForKey:@"ax"] doubleValue];
        CGFloat ay = [[message objectForKey:@"ay"] doubleValue];
        if (![encoded isKindOfClass:[NSString class]] || encoded.length > 100000 ||
            nw <= 0 || nh <= 0 || nw > 0.25 || nh > 0.25 ||
            ax < 0 || ax > 1 || ay < 0 || ay > 1) return;
        NSData *data = [[NSData alloc] initWithBase64EncodedString:encoded options:0];
        UIImage *image = data ? [UIImage imageWithData:data] : nil;
        if (!image) return;
        dispatch_async(dispatch_get_main_queue(), ^{
            CGSize displaySize = self->_displayLayer.bounds.size;
            self->_cursorView.bounds = CGRectMake(0, 0,
                nw * displaySize.width, nh * displaySize.height);
            self->_cursorView.layer.anchorPoint = CGPointMake(ax, ay);
            self->_cursorView.image = image;
        });
    }
}

- (void)resetDecoder {
    _sps = nil;
    _pps = nil;
    if (_formatDescription) {
        CFRelease(_formatDescription);
        _formatDescription = NULL;
    }
    dispatch_async(dispatch_get_main_queue(), ^{
        [self->_displayLayer flush];
    });
}

- (void)handleAnnexB:(NSData *)payload {
    const uint8_t *bytes = payload.bytes;
    LegacyNALRange ranges[16];
    size_t count = LegacyFindAnnexBNALUnits(bytes, payload.length, ranges, 16);
    LegacyNALRange videoRanges[16];
    size_t videoCount = 0;
    BOOL isKeyframe = NO;

    for (size_t i = 0; i < count; i++) {
        LegacyNALRange range = ranges[i];
        if (range.length == 0) continue;
        uint8_t type = bytes[range.offset] & 0x1f;
        if (type == 7) {
            NSData *nalu = [NSData dataWithBytes:bytes + range.offset length:range.length];
            if (![_sps isEqualToData:nalu]) {
                _sps = nalu;
                if (_formatDescription) {
                    CFRelease(_formatDescription);
                    _formatDescription = NULL;
                }
            }
        } else if (type == 8) {
            NSData *nalu = [NSData dataWithBytes:bytes + range.offset length:range.length];
            if (![_pps isEqualToData:nalu]) {
                _pps = nalu;
                if (_formatDescription) {
                    CFRelease(_formatDescription);
                    _formatDescription = NULL;
                }
            }
        } else if (type == 1 || type == 5) {
            videoRanges[videoCount++] = range;
            if (type == 5) isKeyframe = YES;
        }
    }

    if (!_formatDescription && _sps && _pps) [self buildFormatDescription];
    if (_formatDescription && videoCount > 0) {
        [self enqueueNALBytes:bytes ranges:videoRanges count:videoCount keyframe:isKeyframe];
    }
}

- (void)buildFormatDescription {
    const uint8_t *parameterSets[2] = {_sps.bytes, _pps.bytes};
    const size_t sizes[2] = {_sps.length, _pps.length};
    CMVideoFormatDescriptionRef description = NULL;
    OSStatus status = CMVideoFormatDescriptionCreateFromH264ParameterSets(
        kCFAllocatorDefault, 2, parameterSets, sizes, 4, &description);
    if (status != noErr || !description) return;

    _formatDescription = description;
    CMVideoDimensions dimensions = CMVideoFormatDescriptionGetDimensions(description);
    [self setStatus:[NSString stringWithFormat:@"接收 %dx%d", dimensions.width, dimensions.height]
            connected:YES];
}

- (void)enqueueNALBytes:(const uint8_t *)bytes
                 ranges:(const LegacyNALRange *)ranges
                  count:(size_t)count
               keyframe:(BOOL)isKeyframe {
    size_t avccLength = 0;
    for (size_t i = 0; i < count; i++) {
        if (ranges[i].length > UINT32_MAX ||
            ranges[i].length > SIZE_MAX - sizeof(uint32_t) - avccLength) return;
        avccLength += sizeof(uint32_t) + ranges[i].length;
    }

    CMBlockBufferRef blockBuffer = NULL;
    OSStatus status = CMBlockBufferCreateWithMemoryBlock(
        kCFAllocatorDefault, NULL, avccLength, kCFAllocatorDefault, NULL,
        0, avccLength, 0, &blockBuffer);
    if (status != kCMBlockBufferNoErr || !blockBuffer) return;

    size_t offset = 0;
    for (size_t i = 0; i < count; i++) {
        uint32_t length = htonl((uint32_t)ranges[i].length);
        status = CMBlockBufferReplaceDataBytes(&length, blockBuffer, offset, sizeof(length));
        if (status != kCMBlockBufferNoErr) break;
        offset += sizeof(length);
        status = CMBlockBufferReplaceDataBytes(bytes + ranges[i].offset,
                                               blockBuffer, offset, ranges[i].length);
        if (status != kCMBlockBufferNoErr) break;
        offset += ranges[i].length;
    }
    if (status != kCMBlockBufferNoErr) {
        CFRelease(blockBuffer);
        return;
    }

    CMSampleBufferRef sampleBuffer = NULL;
    size_t sampleSize = avccLength;
    status = CMSampleBufferCreateReady(
        kCFAllocatorDefault, blockBuffer, _formatDescription, 1,
        0, NULL, 1, &sampleSize, &sampleBuffer);
    CFRelease(blockBuffer);
    if (status != noErr || !sampleBuffer) return;

    CFArrayRef attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, true);
    if (attachments && CFArrayGetCount(attachments) > 0) {
        CFMutableDictionaryRef dictionary =
            (CFMutableDictionaryRef)CFArrayGetValueAtIndex(attachments, 0);
        CFDictionarySetValue(dictionary, kCMSampleAttachmentKey_DisplayImmediately, kCFBooleanTrue);
        if (!isKeyframe) {
            CFDictionarySetValue(dictionary, kCMSampleAttachmentKey_NotSync, kCFBooleanTrue);
        }
    }

    CFRetain(sampleBuffer);
    dispatch_async(dispatch_get_main_queue(), ^{
        if (self->_displayLayer.status == AVQueuedSampleBufferRenderingStatusFailed) {
            [self->_displayLayer flush];
            [self requestKeyframe];
            CFRelease(sampleBuffer);
            return;
        }
        [self->_displayLayer enqueueSampleBuffer:sampleBuffer];
        CFRelease(sampleBuffer);
    });
    CFRelease(sampleBuffer);
}

@end

@interface QuietHistoryView : UIView
- (void)addCPU:(double)cpu memory:(double)memory;
@end

@implementation QuietHistoryView {
    NSMutableArray *_cpuSamples;
    NSMutableArray *_memorySamples;
}

- (instancetype)initWithFrame:(CGRect)frame {
    self = [super initWithFrame:frame];
    if (self) {
        _cpuSamples = [NSMutableArray array];
        _memorySamples = [NSMutableArray array];
        self.backgroundColor = [UIColor colorWithWhite:1.0 alpha:0.045];
        self.layer.cornerRadius = 12.0;
        self.layer.masksToBounds = YES;
        self.contentMode = UIViewContentModeRedraw;
    }
    return self;
}

- (void)addCPU:(double)cpu memory:(double)memory {
    [_cpuSamples addObject:@(MIN(100.0, MAX(0.0, cpu)))];
    [_memorySamples addObject:@(MIN(100.0, MAX(0.0, memory)))];
    while (_cpuSamples.count > 120) [_cpuSamples removeObjectAtIndex:0];
    while (_memorySamples.count > 120) [_memorySamples removeObjectAtIndex:0];
    [self setNeedsDisplay];
}

- (void)drawSeries:(NSArray *)samples color:(UIColor *)color rect:(CGRect)rect {
    if (samples.count < 2) return;
    UIBezierPath *path = [UIBezierPath bezierPath];
    CGFloat step = rect.size.width / 119.0;
    NSInteger empty = 120 - samples.count;
    for (NSInteger i = 0; i < (NSInteger)samples.count; i++) {
        CGFloat x = rect.origin.x + (empty + i) * step;
        CGFloat y = CGRectGetMaxY(rect) - [samples[i] doubleValue] / 100.0 * rect.size.height;
        if (i == 0) [path moveToPoint:CGPointMake(x, y)];
        else [path addLineToPoint:CGPointMake(x, y)];
    }
    path.lineWidth = 2.2;
    [color setStroke];
    [path stroke];
}

- (void)drawRect:(CGRect)rect {
    CGRect graph = CGRectInset(self.bounds, 22, 34);
    graph.origin.y += 16;
    graph.size.height -= 16;
    [[UIColor colorWithWhite:1 alpha:0.08] setStroke];
    for (NSInteger i = 0; i <= 4; i++) {
        CGFloat y = graph.origin.y + graph.size.height * i / 4.0;
        UIBezierPath *line = [UIBezierPath bezierPath];
        [line moveToPoint:CGPointMake(graph.origin.x, y)];
        [line addLineToPoint:CGPointMake(CGRectGetMaxX(graph), y)];
        line.lineWidth = 1.0;
        [line stroke];
    }
    NSDictionary *caption = @{
        NSFontAttributeName: [UIFont boldSystemFontOfSize:13.0],
        NSForegroundColorAttributeName: [UIColor colorWithWhite:0.62 alpha:1.0]
    };
    [@"即時趨勢 · 最近 2 分鐘" drawAtPoint:CGPointMake(20, 10) withAttributes:caption];
    NSDictionary *cpuStyle = @{
        NSFontAttributeName: [UIFont systemFontOfSize:12.0],
        NSForegroundColorAttributeName: [UIColor colorWithRed:1.0 green:0.72 blue:0.2 alpha:1.0]
    };
    NSDictionary *memoryStyle = @{
        NSFontAttributeName: [UIFont systemFontOfSize:12.0],
        NSForegroundColorAttributeName: [UIColor colorWithRed:0.62 green:0.48 blue:1.0 alpha:1.0]
    };
    [@"CPU" drawAtPoint:CGPointMake(CGRectGetMaxX(graph) - 116, 10) withAttributes:cpuStyle];
    [@"MEMORY" drawAtPoint:CGPointMake(CGRectGetMaxX(graph) - 70, 10) withAttributes:memoryStyle];
    [self drawSeries:_cpuSamples
               color:[UIColor colorWithRed:1.0 green:0.72 blue:0.2 alpha:1.0]
                rect:graph];
    [self drawSeries:_memorySamples
               color:[UIColor colorWithRed:0.62 green:0.48 blue:1.0 alpha:1.0]
                rect:graph];
}

@end

@interface QuietAlbumPickerController : UITableViewController
@property (nonatomic, copy) void (^applyHandler)(NSArray *identifiers);
- (instancetype)initWithSelectedIdentifiers:(NSArray *)identifiers;
@end

@implementation QuietAlbumPickerController {
    NSArray *_collections;
    NSArray *_photoCounts;
    NSMutableSet *_selectedIdentifiers;
}

- (instancetype)initWithSelectedIdentifiers:(NSArray *)identifiers {
    self = [super initWithStyle:UITableViewStyleGrouped];
    if (self) {
        _selectedIdentifiers = [NSMutableSet set];
        for (id identifier in identifiers) {
            if ([identifier isKindOfClass:[NSString class]]) {
                [_selectedIdentifiers addObject:identifier];
            }
        }
        if (_selectedIdentifiers.count == 0) {
            [_selectedIdentifiers addObject:kQuietAllPhotosIdentifier];
        }
        [self loadAlbums];
    }
    return self;
}

- (void)loadAlbums {
    PHFetchOptions *imageOptions = [[PHFetchOptions alloc] init];
    imageOptions.predicate = [NSPredicate predicateWithFormat:@"mediaType == %d",
                              PHAssetMediaTypeImage];
    PHFetchResult *result = [PHAssetCollection fetchAssetCollectionsWithType:
        PHAssetCollectionTypeAlbum subtype:PHAssetCollectionSubtypeAny options:nil];
    NSMutableArray *collections = [NSMutableArray array];
    NSMutableDictionary *counts = [NSMutableDictionary dictionary];
    [result enumerateObjectsUsingBlock:^(PHAssetCollection *collection,
                                         NSUInteger index, BOOL *stop) {
        (void)index;
        (void)stop;
        NSUInteger count = [PHAsset fetchAssetsInAssetCollection:collection
                                                          options:imageOptions].count;
        if (count == 0) return;
        [collections addObject:collection];
        [counts setObject:@(count) forKey:collection.localIdentifier];
    }];
    [collections sortUsingComparator:^NSComparisonResult(PHAssetCollection *left,
                                                           PHAssetCollection *right) {
        NSString *a = left.localizedTitle ?: @"";
        NSString *b = right.localizedTitle ?: @"";
        return [a localizedCaseInsensitiveCompare:b];
    }];
    NSMutableArray *orderedCounts = [NSMutableArray arrayWithCapacity:collections.count];
    for (PHAssetCollection *collection in collections) {
        [orderedCounts addObject:[counts objectForKey:collection.localIdentifier] ?: @0];
    }
    _collections = [collections copy];
    _photoCounts = [orderedCounts copy];
}

- (void)viewDidLoad {
    [super viewDidLoad];
    self.title = @"選擇相簿";
    self.preferredContentSize = CGSizeMake(620, 620);
    self.tableView.rowHeight = 54.0;
    self.navigationItem.leftBarButtonItem = [[UIBarButtonItem alloc]
        initWithBarButtonSystemItem:UIBarButtonSystemItemCancel
        target:self action:@selector(cancelTapped)];
    self.navigationItem.rightBarButtonItem = [[UIBarButtonItem alloc]
        initWithBarButtonSystemItem:UIBarButtonSystemItemDone
        target:self action:@selector(applyTapped)];
}

- (NSInteger)tableView:(UITableView *)tableView numberOfRowsInSection:(NSInteger)section {
    (void)tableView;
    (void)section;
    return (NSInteger)_collections.count + 1;
}

- (NSString *)tableView:(UITableView *)tableView titleForHeaderInSection:(NSInteger)section {
    (void)tableView;
    (void)section;
    return @"可複選；包含由 Mac／iTunes 同步到 iPad 的相簿";
}

- (UITableViewCell *)tableView:(UITableView *)tableView
         cellForRowAtIndexPath:(NSIndexPath *)indexPath {
    static NSString *identifier = @"QuietAlbumCell";
    UITableViewCell *cell = [tableView dequeueReusableCellWithIdentifier:identifier];
    if (!cell) {
        cell = [[UITableViewCell alloc] initWithStyle:UITableViewCellStyleSubtitle
                                       reuseIdentifier:identifier];
    }
    NSString *albumID;
    if (indexPath.row == 0) {
        albumID = kQuietAllPhotosIdentifier;
        cell.textLabel.text = @"所有照片";
        PHFetchOptions *options = [[PHFetchOptions alloc] init];
        options.predicate = [NSPredicate predicateWithFormat:@"mediaType == %d",
                             PHAssetMediaTypeImage];
        NSUInteger count = [PHAsset fetchAssetsWithOptions:options].count;
        cell.detailTextLabel.text = [NSString stringWithFormat:@"%lu 張",
                                     (unsigned long)count];
    } else {
        PHAssetCollection *collection = _collections[indexPath.row - 1];
        albumID = collection.localIdentifier;
        cell.textLabel.text = collection.localizedTitle ?: @"未命名相簿";
        NSString *prefix = QuietIsSyncedAlbum(collection) ? @"已同步 · " : @"";
        cell.detailTextLabel.text = [NSString stringWithFormat:@"%@%lu 張", prefix,
            (unsigned long)[_photoCounts[indexPath.row - 1] unsignedIntegerValue]];
    }
    cell.accessoryType = [_selectedIdentifiers containsObject:albumID]
        ? UITableViewCellAccessoryCheckmark : UITableViewCellAccessoryNone;
    return cell;
}

- (void)tableView:(UITableView *)tableView didSelectRowAtIndexPath:(NSIndexPath *)indexPath {
    [tableView deselectRowAtIndexPath:indexPath animated:YES];
    NSString *albumID = indexPath.row == 0
        ? kQuietAllPhotosIdentifier
        : [_collections[indexPath.row - 1] localIdentifier];
    if ([albumID isEqual:kQuietAllPhotosIdentifier]) {
        [_selectedIdentifiers removeAllObjects];
        [_selectedIdentifiers addObject:kQuietAllPhotosIdentifier];
    } else {
        [_selectedIdentifiers removeObject:kQuietAllPhotosIdentifier];
        if ([_selectedIdentifiers containsObject:albumID]) {
            [_selectedIdentifiers removeObject:albumID];
        } else {
            [_selectedIdentifiers addObject:albumID];
        }
        if (_selectedIdentifiers.count == 0) {
            [_selectedIdentifiers addObject:kQuietAllPhotosIdentifier];
        }
    }
    [tableView reloadData];
}

- (void)cancelTapped {
    [self dismissViewControllerAnimated:YES completion:nil];
}

- (void)applyTapped {
    NSArray *identifiers = [[_selectedIdentifiers allObjects]
        sortedArrayUsingSelector:@selector(compare:)];
    if (_applyHandler) _applyHandler(identifiers);
    [self dismissViewControllerAnimated:YES completion:nil];
}

@end

@interface LegacyViewController : UIViewController <UIGestureRecognizerDelegate>
- (void)startPendingAlbumImports;
- (void)importNextPendingAlbum;
- (void)beginAlbumImportAtDirectory:(NSString *)directory;
- (void)importAlbumBatchAtDirectory:(NSString *)directory
                              files:(NSArray *)files
                              album:(PHAssetCollection *)album
                          nextIndex:(NSUInteger)nextIndex
                         totalBytes:(unsigned long long)totalBytes;
@end

@implementation LegacyViewController {
    UIView *_dashboardView;
    LegacyVideoView *_videoView;
    UIView *_photoView;
    UIView *_nasaView;
    UILabel *_statusLabel;
    UILabel *_dashboardStatusLabel;
    UILabel *_systemDetailLabel;
    UILabel *_clockLabel;
    UILabel *_dateLabel;
    QuietWeatherIconView *_dashboardWeatherIconView;
    UILabel *_dashboardWeatherLabel;
    UILabel *_cpuValueLabel;
    UILabel *_memoryValueLabel;
    UILabel *_networkValueLabel;
    UILabel *_diskValueLabel;
    QuietHistoryView *_historyView;
    UILabel *_pageIndicator;
    UIImageView *_cursorView;
    LegacyReceiver *_receiver;
    NSDateFormatter *_timeFormatter;
    NSDateFormatter *_dateFormatter;
    NSDateFormatter *_photoTimeFormatter;
    NSDateFormatter *_photoDateFormatter;
    NSDateFormatter *_sunTimeFormatter;
    NSTimer *_clockTimer;
    NSTimer *_photoTimer;
    UIImageView *_photoImageView;
    UIButton *_photoSettingsButton;
    UIView *_photoClockPanel;
    UIView *_photoClockBackgroundView;
    UIView *_photoToolControls;
    UILabel *_photoToolStatusLabel;
    NSArray *_photoToolActions;
    UIPanGestureRecognizer *_photoClockPan;
    UILongPressGestureRecognizer *_photoClockDrag;
    UIPinchGestureRecognizer *_photoClockPinch;
    UISwipeGestureRecognizer *_pageSwipeLeft;
    UISwipeGestureRecognizer *_pageSwipeRight;
    CGPoint _photoClockPanStartCenter;
    CGPoint _photoClockDragStartPoint;
    CGFloat _photoClockPinchStartScale;
    CGFloat _photoClockScale;
    UILabel *_photoTimeLabel;
    UILabel *_photoDateLabel;
    UIView *_photoWeatherRow;
    QuietWeatherIconView *_photoWeatherIconView;
    UILabel *_photoWeatherTemperatureLabel;
    UILabel *_photoWeatherLocationLabel;
    UILabel *_photoDaylightLabel;
    UIProgressView *_photoDaylightProgress;
    UILabel *_photoStatusLabel;
    NSArray *_photoAssets;
    NSUInteger _photoIndex;
    NSDictionary *_weatherState;
    UIImageView *_nasaImageView;
    UILabel *_nasaTitleLabel;
    UILabel *_nasaMetaLabel;
    UITextView *_nasaExplanationView;
    UILabel *_nasaStatusLabel;
    NSInteger _currentPage;
    uint8_t _pageEnabled[QuietPageCount];
    BOOL _displayConnected;
    NSString *_displayStatus;
    CGSize _displayPixelSize;
    BOOL _albumImportRunning;
    NSMutableArray *_albumImportDirectories;
}

- (BOOL)prefersStatusBarHidden { return YES; }
- (BOOL)shouldAutorotate { return YES; }
- (UIInterfaceOrientationMask)supportedInterfaceOrientations {
    return UIInterfaceOrientationMaskLandscape;
}

- (NSString *)albumImportRootPath {
    NSString *documents = [NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory, NSUserDomainMask, YES) firstObject];
    return [documents stringByAppendingPathComponent:kQuietAlbumImportRoot];
}

- (void)writeAlbumImportState:(NSString *)phase
                    directory:(NSString *)directory
               albumIdentifier:(NSString *)albumIdentifier
                    nextIndex:(NSUInteger)nextIndex
                 expectedCount:(NSUInteger)expectedCount
                    totalBytes:(unsigned long long)totalBytes
                         error:(NSString *)error {
    NSString *albumName = directory.lastPathComponent ?: @"";
    NSMutableDictionary *state = [@{
        @"phase": phase ?: @"error",
        @"album": albumName,
        @"album_identifier": albumIdentifier ?: @"",
        @"imported_count": @(nextIndex),
        @"expected_count": @(expectedCount),
        @"total_bytes": @(totalBytes),
        @"updated_at": @([[NSDate date] timeIntervalSince1970])
    } mutableCopy];
    if (error.length > 0) [state setObject:error forKey:@"error"];
    if (directory.length > 0) {
        [state writeToFile:[directory stringByAppendingPathComponent:
            kQuietAlbumImportState] atomically:YES];
    }
    [state writeToFile:[[self albumImportRootPath]
        stringByAppendingPathComponent:@"status.plist"] atomically:YES];

    if ([phase isEqual:@"error"]) {
        _photoStatusLabel.hidden = NO;
        _photoStatusLabel.text = [NSString stringWithFormat:@"相簿匯入失敗\n%@",
            error ?: @"未知錯誤"];
    } else if ([phase isEqual:@"complete"]) {
        _photoStatusLabel.hidden = NO;
        _photoStatusLabel.text = [NSString stringWithFormat:@"%@ 已完成 · %lu 張",
            albumName, (unsigned long)expectedCount];
    } else {
        _photoStatusLabel.hidden = NO;
        _photoStatusLabel.text = [NSString stringWithFormat:@"正在匯入 %@ · %lu/%lu",
            albumName, (unsigned long)nextIndex, (unsigned long)expectedCount];
    }
}

- (NSArray *)importFilesAtDirectory:(NSString *)directory
                         totalBytes:(unsigned long long *)totalBytes
                              error:(NSError **)error {
    NSFileManager *manager = [NSFileManager defaultManager];
    NSArray *entries = [manager contentsOfDirectoryAtPath:directory error:error];
    if (!entries) return nil;
    NSMutableArray *files = [NSMutableArray array];
    unsigned long long bytes = 0;
    for (NSString *name in entries) {
        if ([name isEqual:kQuietAlbumImportReady] ||
            [name isEqual:kQuietAlbumImportState]) continue;
        NSString *path = [directory stringByAppendingPathComponent:name];
        NSDictionary *attributes = [manager attributesOfItemAtPath:path error:error];
        if (!attributes) return nil;
        NSString *extension = name.pathExtension.lowercaseString;
        BOOL supported = [extension isEqual:@"jpg"] || [extension isEqual:@"jpeg"] ||
                         [extension isEqual:@"png"];
        if (![[attributes objectForKey:NSFileType] isEqual:NSFileTypeRegular] || !supported) {
            if (error) *error = [NSError errorWithDomain:@"QuietPanelAlbumImport"
                code:1 userInfo:@{NSLocalizedDescriptionKey:
                    [NSString stringWithFormat:@"不支援的匯入項目：%@", name]}];
            return nil;
        }
        bytes += [[attributes objectForKey:NSFileSize] unsignedLongLongValue];
        [files addObject:name];
    }
    [files sortUsingComparator:^NSComparisonResult(NSString *left, NSString *right) {
        return [left compare:right options:NSCaseInsensitiveSearch | NSNumericSearch];
    }];
    if (files.count == 0) {
        if (error) *error = [NSError errorWithDomain:@"QuietPanelAlbumImport"
            code:2 userInfo:@{NSLocalizedDescriptionKey:@"匯入資料夾沒有支援的圖片"}];
        return nil;
    }
    if (totalBytes) *totalBytes = bytes;
    return files;
}

- (NSArray *)albumsNamed:(NSString *)name {
    PHFetchResult *collections = [PHAssetCollection
        fetchAssetCollectionsWithType:PHAssetCollectionTypeAlbum
        subtype:PHAssetCollectionSubtypeAny options:nil];
    NSMutableArray *matches = [NSMutableArray array];
    [collections enumerateObjectsUsingBlock:^(PHAssetCollection *collection,
                                               NSUInteger index, BOOL *stop) {
        (void)index;
        (void)stop;
        if ([collection.localizedTitle isEqualToString:name]) [matches addObject:collection];
    }];
    return matches;
}

- (void)failAlbumImportAtDirectory:(NSString *)directory
                             error:(NSString *)message
                     expectedCount:(NSUInteger)expectedCount
                        totalBytes:(unsigned long long)totalBytes {
    _albumImportRunning = NO;
    NSDictionary *existing = [NSDictionary dictionaryWithContentsOfFile:
        [directory stringByAppendingPathComponent:kQuietAlbumImportState]];
    NSString *albumIdentifier = [existing objectForKey:@"album_identifier"];
    NSUInteger nextIndex = [[existing objectForKey:@"imported_count"] unsignedIntegerValue];
    if (expectedCount == 0) {
        expectedCount = [[existing objectForKey:@"expected_count"] unsignedIntegerValue];
    }
    if (totalBytes == 0) {
        totalBytes = [[existing objectForKey:@"total_bytes"] unsignedLongLongValue];
    }
    [self writeAlbumImportState:@"error" directory:directory
        albumIdentifier:albumIdentifier nextIndex:nextIndex
        expectedCount:expectedCount totalBytes:totalBytes error:message];
}

- (void)startPendingAlbumImports {
    if (_albumImportRunning) return;
    NSFileManager *manager = [NSFileManager defaultManager];
    NSString *root = [self albumImportRootPath];
    NSArray *entries = [manager contentsOfDirectoryAtPath:root error:nil];
    NSMutableArray *pending = [NSMutableArray array];
    for (NSString *name in entries) {
        if ([name hasPrefix:@"."] || [name isEqual:@"status.plist"]) continue;
        NSString *directory = [root stringByAppendingPathComponent:name];
        BOOL isDirectory = NO;
        if (![manager fileExistsAtPath:directory isDirectory:&isDirectory] || !isDirectory ||
            ![manager fileExistsAtPath:[directory stringByAppendingPathComponent:
                kQuietAlbumImportReady]]) continue;
        NSDictionary *state = [NSDictionary dictionaryWithContentsOfFile:
            [directory stringByAppendingPathComponent:kQuietAlbumImportState]];
        if ([[state objectForKey:@"phase"] isEqual:@"complete"]) continue;
        [pending addObject:directory];
    }
    [pending sortUsingComparator:^NSComparisonResult(NSString *left, NSString *right) {
        return [left.lastPathComponent compare:right.lastPathComponent];
    }];
    if (pending.count == 0) return;

    PHAuthorizationStatus authorization = [PHPhotoLibrary authorizationStatus];
    if (authorization == PHAuthorizationStatusNotDetermined) {
        _albumImportRunning = YES;
        __weak LegacyViewController *weakSelf = self;
        [PHPhotoLibrary requestAuthorization:^(PHAuthorizationStatus result) {
            dispatch_async(dispatch_get_main_queue(), ^{
                LegacyViewController *strongSelf = weakSelf;
                if (!strongSelf) return;
                strongSelf->_albumImportRunning = NO;
                if (result == PHAuthorizationStatusAuthorized) {
                    [strongSelf startPendingAlbumImports];
                } else {
                    [strongSelf failAlbumImportAtDirectory:[pending firstObject]
                        error:@"QuietPanel 沒有照片權限" expectedCount:0 totalBytes:0];
                }
            });
        }];
        return;
    }
    if (authorization != PHAuthorizationStatusAuthorized) {
        [self failAlbumImportAtDirectory:[pending firstObject]
            error:@"QuietPanel 沒有照片權限" expectedCount:0 totalBytes:0];
        return;
    }
    _albumImportDirectories = pending;
    _albumImportRunning = YES;
    [self importNextPendingAlbum];
}

- (void)importNextPendingAlbum {
    if (_albumImportDirectories.count == 0) {
        _albumImportRunning = NO;
        _photoAssets = nil;
        if (_currentPage == 2 || _currentPage == 3) [self loadPhotoCatalog];
        return;
    }
    [self beginAlbumImportAtDirectory:[_albumImportDirectories firstObject]];
}

- (void)beginAlbumImportAtDirectory:(NSString *)directory {
    NSError *fileError = nil;
    unsigned long long totalBytes = 0;
    NSArray *files = [self importFilesAtDirectory:directory totalBytes:&totalBytes
        error:&fileError];
    if (!files) {
        [self failAlbumImportAtDirectory:directory error:fileError.localizedDescription
            expectedCount:0 totalBytes:totalBytes];
        return;
    }
    NSString *name = directory.lastPathComponent;
    NSString *statePath = [directory stringByAppendingPathComponent:kQuietAlbumImportState];
    NSDictionary *state = [NSDictionary dictionaryWithContentsOfFile:statePath];
    NSUInteger stateExpectedCount = [[state objectForKey:@"expected_count"] unsignedIntegerValue];
    if (stateExpectedCount > 0 && (stateExpectedCount != files.count ||
                  [[state objectForKey:@"total_bytes"] unsignedLongLongValue] != totalBytes)) {
        [self failAlbumImportAtDirectory:directory
            error:@"暫存圖片與先前匯入狀態不一致，已停止以避免漏圖或重複"
            expectedCount:files.count totalBytes:totalBytes];
        return;
    }

    NSString *albumIdentifier = [state objectForKey:@"album_identifier"];
    PHAssetCollection *album = nil;
    if (albumIdentifier.length > 0) {
        PHFetchResult *result = [PHAssetCollection
            fetchAssetCollectionsWithLocalIdentifiers:@[albumIdentifier] options:nil];
        if (result.count == 1) album = [result objectAtIndex:0];
        if (!album || ![album.localizedTitle isEqualToString:name]) {
            [self failAlbumImportAtDirectory:directory
                error:@"匯入用相簿已被移除或改名，已停止以避免重複"
                expectedCount:files.count totalBytes:totalBytes];
            return;
        }
    } else {
        NSArray *matches = [self albumsNamed:name];
        if (matches.count > 1) {
            [self failAlbumImportAtDirectory:directory
                error:@"照片中已有多個同名相簿，無法安全判定目標"
                expectedCount:files.count totalBytes:totalBytes];
            return;
        }
        if (matches.count == 1) {
            album = [matches firstObject];
            NSUInteger existingCount = [PHAsset fetchAssetsInAssetCollection:album
                options:nil].count;
            if (existingCount > 0) {
                [self failAlbumImportAtDirectory:directory
                    error:@"照片中已有非空白同名相簿，已停止以避免混入或重複"
                    expectedCount:files.count totalBytes:totalBytes];
                return;
            }
            albumIdentifier = album.localIdentifier;
        } else {
            __block NSString *createdIdentifier = nil;
            __weak LegacyViewController *weakSelf = self;
            [[PHPhotoLibrary sharedPhotoLibrary] performChanges:^{
                PHAssetCollectionChangeRequest *request = [PHAssetCollectionChangeRequest
                    creationRequestForAssetCollectionWithTitle:name];
                createdIdentifier = [request.placeholderForCreatedAssetCollection.localIdentifier copy];
            } completionHandler:^(BOOL success, NSError *error) {
                dispatch_async(dispatch_get_main_queue(), ^{
                    LegacyViewController *strongSelf = weakSelf;
                    if (!strongSelf) return;
                    if (!success || createdIdentifier.length == 0) {
                        [strongSelf failAlbumImportAtDirectory:directory
                            error:error.localizedDescription ?: @"無法建立相簿"
                            expectedCount:files.count totalBytes:totalBytes];
                        return;
                    }
                    [strongSelf writeAlbumImportState:@"importing" directory:directory
                        albumIdentifier:createdIdentifier nextIndex:0
                        expectedCount:files.count totalBytes:totalBytes error:nil];
                    [strongSelf beginAlbumImportAtDirectory:directory];
                });
            }];
            return;
        }
    }

    NSUInteger albumCount = [PHAsset fetchAssetsInAssetCollection:album options:nil].count;
    if (albumCount > files.count) {
        [self failAlbumImportAtDirectory:directory
            error:@"相簿照片數超過來源檔案數，已停止以避免錯誤"
            expectedCount:files.count totalBytes:totalBytes];
        return;
    }
    [self writeAlbumImportState:albumCount == files.count ? @"complete" : @"importing"
        directory:directory albumIdentifier:album.localIdentifier nextIndex:albumCount
        expectedCount:files.count totalBytes:totalBytes error:nil];
    if (albumCount == files.count) {
        [_albumImportDirectories removeObjectAtIndex:0];
        [self importNextPendingAlbum];
        return;
    }
    [self importAlbumBatchAtDirectory:directory files:files album:album
        nextIndex:albumCount totalBytes:totalBytes];
}

- (void)importAlbumBatchAtDirectory:(NSString *)directory
                              files:(NSArray *)files
                              album:(PHAssetCollection *)album
                          nextIndex:(NSUInteger)nextIndex
                         totalBytes:(unsigned long long)totalBytes {
    NSUInteger batchCount = QuietAlbumImportBatchCount(files.count - nextIndex);
    NSArray *batch = [files subarrayWithRange:NSMakeRange(nextIndex, batchCount)];
    __weak LegacyViewController *weakSelf = self;
    [[PHPhotoLibrary sharedPhotoLibrary] performChanges:^{
        NSMutableArray *placeholders = [NSMutableArray arrayWithCapacity:batch.count];
        for (NSString *filename in batch) {
            PHAssetCreationRequest *request = [PHAssetCreationRequest creationRequestForAsset];
            PHAssetResourceCreationOptions *options = [[PHAssetResourceCreationOptions alloc] init];
            options.originalFilename = filename;
            options.shouldMoveFile = NO;
            NSURL *url = [NSURL fileURLWithPath:
                [directory stringByAppendingPathComponent:filename]];
            [request addResourceWithType:PHAssetResourceTypePhoto fileURL:url options:options];
            [placeholders addObject:request.placeholderForCreatedAsset];
        }
        PHAssetCollectionChangeRequest *albumRequest = [PHAssetCollectionChangeRequest
            changeRequestForAssetCollection:album];
        [albumRequest addAssets:placeholders];
    } completionHandler:^(BOOL success, NSError *error) {
        dispatch_async(dispatch_get_main_queue(), ^{
            LegacyViewController *strongSelf = weakSelf;
            if (!strongSelf) return;
            if (!success) {
                [strongSelf failAlbumImportAtDirectory:directory
                    error:error.localizedDescription ?: @"照片批次寫入失敗"
                    expectedCount:files.count totalBytes:totalBytes];
                return;
            }
            [strongSelf beginAlbumImportAtDirectory:directory];
        });
    }];
}

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = [UIColor blackColor];
    memset(_pageEnabled, 1, sizeof(_pageEnabled));

    _timeFormatter = [[NSDateFormatter alloc] init];
    _timeFormatter.locale = [[NSLocale alloc] initWithLocaleIdentifier:@"zh_TW"];
    _timeFormatter.dateFormat = @"HH:mm:ss";
    _dateFormatter = [[NSDateFormatter alloc] init];
    _dateFormatter.locale = [[NSLocale alloc] initWithLocaleIdentifier:@"zh_TW"];
    _dateFormatter.dateFormat = @"yyyy年 M月 d日 EEEE";
    _photoTimeFormatter = [[NSDateFormatter alloc] init];
    _photoTimeFormatter.dateFormat = @"HH:mm";
    _photoDateFormatter = [[NSDateFormatter alloc] init];
    _photoDateFormatter.locale = [[NSLocale alloc] initWithLocaleIdentifier:@"zh_TW"];
    _photoDateFormatter.dateFormat = @"M月d日 EEEE";
    _sunTimeFormatter = [[NSDateFormatter alloc] init];
    _sunTimeFormatter.dateFormat = @"HH:mm";

    [self buildDashboard];

    _videoView = [[LegacyVideoView alloc] initWithFrame:self.view.bounds];
    _videoView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    _videoView.backgroundColor = [UIColor blackColor];
    [self.view addSubview:_videoView];
    UITapGestureRecognizer *displayTap = [[UITapGestureRecognizer alloc]
        initWithTarget:self action:@selector(displayTapped:)];
    [_videoView addGestureRecognizer:displayTap];

    _cursorView = [[UIImageView alloc] initWithFrame:CGRectZero];
    _cursorView.contentMode = UIViewContentModeScaleAspectFit;
    _cursorView.hidden = YES;
    [self.view addSubview:_cursorView];

    [self buildPhotoPage];
    [self buildNASAPage];
    [self applyPhotoFontSettings];
    [self updatePhotoAlbumButton];
    [self loadWeatherCache];

    _statusLabel = [[UILabel alloc] initWithFrame:CGRectMake(
        20, self.view.bounds.size.height - 82, self.view.bounds.size.width - 40, 36)];
    _statusLabel.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleTopMargin;
    _statusLabel.backgroundColor = [UIColor colorWithWhite:0 alpha:0.55];
    _statusLabel.textColor = [UIColor whiteColor];
    _statusLabel.textAlignment = NSTextAlignmentCenter;
    _statusLabel.font = [UIFont systemFontOfSize:15.0];
    _statusLabel.layer.cornerRadius = 9.0;
    _statusLabel.layer.masksToBounds = YES;
    _statusLabel.text = @"正在啟動";
    [self.view addSubview:_statusLabel];

    _pageIndicator = [[UILabel alloc] initWithFrame:CGRectMake(
        0, 0, MIN(520.0, self.view.bounds.size.width - 40), 28)];
    _pageIndicator.autoresizingMask = UIViewAutoresizingFlexibleLeftMargin |
                                     UIViewAutoresizingFlexibleRightMargin |
                                     UIViewAutoresizingFlexibleTopMargin;
    _pageIndicator.center = CGPointMake(CGRectGetMidX(self.view.bounds),
                                        CGRectGetMaxY(self.view.bounds) - 18);
    _pageIndicator.backgroundColor = [UIColor colorWithWhite:0 alpha:0.48];
    _pageIndicator.textColor = [UIColor whiteColor];
    _pageIndicator.textAlignment = NSTextAlignmentCenter;
    _pageIndicator.font = [UIFont systemFontOfSize:13.0];
    _pageIndicator.layer.cornerRadius = 9.0;
    _pageIndicator.layer.masksToBounds = YES;
    [self.view addSubview:_pageIndicator];

    _pageSwipeLeft = [[UISwipeGestureRecognizer alloc]
        initWithTarget:self action:@selector(showNextPage)];
    _pageSwipeLeft.direction = UISwipeGestureRecognizerDirectionLeft;
    _pageSwipeLeft.delegate = self;
    [_pageSwipeLeft requireGestureRecognizerToFail:_photoClockDrag];
    [self.view addGestureRecognizer:_pageSwipeLeft];
    _pageSwipeRight = [[UISwipeGestureRecognizer alloc]
        initWithTarget:self action:@selector(showPreviousPage)];
    _pageSwipeRight.direction = UISwipeGestureRecognizerDirectionRight;
    _pageSwipeRight.delegate = self;
    [_pageSwipeRight requireGestureRecognizerToFail:_photoClockDrag];
    [self.view addGestureRecognizer:_pageSwipeRight];

    _displayStatus = @"正在啟動";
    [self loadNASACache];
    [self showPage:0];
    [UIApplication sharedApplication].idleTimerDisabled = YES;
    [[NSNotificationCenter defaultCenter] addObserver:self
        selector:@selector(applicationDidBecomeActive:)
        name:UIApplicationDidBecomeActiveNotification object:nil];
    [[NSNotificationCenter defaultCenter] addObserver:self
        selector:@selector(applicationDidEnterBackground:)
        name:UIApplicationDidEnterBackgroundNotification object:nil];
    [self performSelector:@selector(startPendingAlbumImports)
               withObject:nil afterDelay:1.0];
}

- (void)buildDashboard {
    _dashboardView = [[UIView alloc] initWithFrame:self.view.bounds];
    _dashboardView.autoresizingMask = UIViewAutoresizingFlexibleWidth |
                                     UIViewAutoresizingFlexibleHeight;
    _dashboardView.backgroundColor = [UIColor colorWithRed:7.0 / 255.0
                                                     green:12.0 / 255.0
                                                      blue:22.0 / 255.0
                                                     alpha:1.0];
    [self.view addSubview:_dashboardView];

    UILabel *title = [[UILabel alloc] initWithFrame:CGRectMake(32, 20, 420, 42)];
    title.text = @"QUIETPANEL";
    title.textColor = [UIColor colorWithRed:0.35 green:0.85 blue:1.0 alpha:1.0];
    title.font = [UIFont boldSystemFontOfSize:26.0];
    [_dashboardView addSubview:title];

    UILabel *version = [[UILabel alloc] initWithFrame:CGRectMake(32, 55, 460, 24)];
    version.text = @"Mac 即時狀態 · 五合一工作面板 · 0.6.0";
    version.textColor = [UIColor colorWithWhite:0.55 alpha:1.0];
    version.font = [UIFont systemFontOfSize:13.0];
    [_dashboardView addSubview:version];

    _clockLabel = [[UILabel alloc] initWithFrame:CGRectMake(
        self.view.bounds.size.width - 390, 14, 358, 50)];
    _clockLabel.autoresizingMask = UIViewAutoresizingFlexibleLeftMargin;
    _clockLabel.textAlignment = NSTextAlignmentRight;
    _clockLabel.textColor = [UIColor whiteColor];
    _clockLabel.font = [UIFont systemFontOfSize:38.0 weight:UIFontWeightLight];
    [_dashboardView addSubview:_clockLabel];

    _dateLabel = [[UILabel alloc] initWithFrame:CGRectMake(
        self.view.bounds.size.width - 390, 61, 358, 22)];
    _dateLabel.autoresizingMask = UIViewAutoresizingFlexibleLeftMargin;
    _dateLabel.textAlignment = NSTextAlignmentRight;
    _dateLabel.textColor = [UIColor colorWithWhite:0.72 alpha:1.0];
    _dateLabel.font = [UIFont systemFontOfSize:14.0];
    [_dashboardView addSubview:_dateLabel];

    _dashboardWeatherIconView = [[QuietWeatherIconView alloc]
        initWithFrame:CGRectMake(self.view.bounds.size.width - 164, 78, 20, 20)];
    _dashboardWeatherIconView.autoresizingMask = UIViewAutoresizingFlexibleLeftMargin;
    _dashboardWeatherIconView.hidden = YES;
    [_dashboardView addSubview:_dashboardWeatherIconView];

    _dashboardWeatherLabel = [[UILabel alloc] initWithFrame:CGRectMake(
        self.view.bounds.size.width - 140, 80, 108, 18)];
    _dashboardWeatherLabel.autoresizingMask = UIViewAutoresizingFlexibleLeftMargin;
    _dashboardWeatherLabel.textAlignment = NSTextAlignmentRight;
    _dashboardWeatherLabel.textColor = [UIColor colorWithRed:0.35 green:0.85 blue:1.0 alpha:1.0];
    _dashboardWeatherLabel.font = [UIFont systemFontOfSize:13.0];
    _dashboardWeatherLabel.hidden = YES;
    [_dashboardView addSubview:_dashboardWeatherLabel];

    _historyView = [[QuietHistoryView alloc] initWithFrame:CGRectMake(
        32, 98, self.view.bounds.size.width - 64, 256)];
    _historyView.autoresizingMask = UIViewAutoresizingFlexibleWidth;
    [_dashboardView addSubview:_historyView];

    CGFloat margin = 32.0;
    CGFloat gap = 12.0;
    CGFloat cardWidth = (self.view.bounds.size.width - margin * 2.0 - gap * 3.0) / 4.0;
    _cpuValueLabel = [self addMetricCard:@"CPU" frame:CGRectMake(
        margin, 370, cardWidth, 132)];
    _memoryValueLabel = [self addMetricCard:@"MEMORY" frame:CGRectMake(
        margin + (cardWidth + gap), 370, cardWidth, 132)];
    _networkValueLabel = [self addMetricCard:@"NETWORK" frame:CGRectMake(
        margin + (cardWidth + gap) * 2, 370, cardWidth, 132)];
    _networkValueLabel.font = [UIFont systemFontOfSize:17.0 weight:UIFontWeightLight];
    _diskValueLabel = [self addMetricCard:@"SYSTEM DISK" frame:CGRectMake(
        margin + (cardWidth + gap) * 3, 370, cardWidth, 132)];
    _diskValueLabel.font = [UIFont systemFontOfSize:31.0 weight:UIFontWeightLight];

    _systemDetailLabel = [[UILabel alloc] initWithFrame:CGRectMake(
        32, 518, self.view.bounds.size.width - 64, 74)];
    _systemDetailLabel.autoresizingMask = UIViewAutoresizingFlexibleWidth;
    _systemDetailLabel.backgroundColor = [UIColor colorWithWhite:1.0 alpha:0.045];
    _systemDetailLabel.textColor = [UIColor colorWithWhite:0.72 alpha:1.0];
    _systemDetailLabel.textAlignment = NSTextAlignmentCenter;
    _systemDetailLabel.font = [UIFont systemFontOfSize:14.0];
    _systemDetailLabel.numberOfLines = 2;
    _systemDetailLabel.text = @"等待 Mac 系統資訊";
    _systemDetailLabel.layer.cornerRadius = 10.0;
    _systemDetailLabel.layer.masksToBounds = YES;
    [_dashboardView addSubview:_systemDetailLabel];

    _dashboardStatusLabel = [[UILabel alloc] initWithFrame:CGRectMake(
        150, 608, self.view.bounds.size.width - 300, 48)];
    _dashboardStatusLabel.autoresizingMask = UIViewAutoresizingFlexibleWidth;
    _dashboardStatusLabel.backgroundColor = [UIColor colorWithWhite:1.0 alpha:0.055];
    _dashboardStatusLabel.textColor = [UIColor colorWithWhite:0.8 alpha:1.0];
    _dashboardStatusLabel.textAlignment = NSTextAlignmentCenter;
    _dashboardStatusLabel.font = [UIFont systemFontOfSize:15.0];
    _dashboardStatusLabel.layer.cornerRadius = 12.0;
    _dashboardStatusLabel.layer.masksToBounds = YES;
    [_dashboardView addSubview:_dashboardStatusLabel];
}

- (UILabel *)addMetricCard:(NSString *)title frame:(CGRect)frame {
    UIView *card = [[UIView alloc] initWithFrame:frame];
    card.backgroundColor = [UIColor colorWithWhite:1.0 alpha:0.055];
    card.layer.cornerRadius = 12.0;
    [_dashboardView addSubview:card];

    UILabel *titleLabel = [[UILabel alloc] initWithFrame:CGRectMake(16, 10,
        frame.size.width - 32, 24)];
    titleLabel.text = title;
    titleLabel.textColor = [UIColor colorWithWhite:0.48 alpha:1.0];
    titleLabel.font = [UIFont boldSystemFontOfSize:13.0];
    [card addSubview:titleLabel];

    UILabel *value = [[UILabel alloc] initWithFrame:CGRectMake(16, 35,
        frame.size.width - 32, 86)];
    value.text = @"--";
    value.textColor = [UIColor whiteColor];
    value.font = [UIFont systemFontOfSize:38.0 weight:UIFontWeightLight];
    value.numberOfLines = 2;
    value.adjustsFontSizeToFitWidth = YES;
    value.minimumScaleFactor = 0.65;
    [card addSubview:value];
    return value;
}

- (void)buildPhotoPage {
    _photoView = [[UIView alloc] initWithFrame:self.view.bounds];
    _photoView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    _photoView.backgroundColor = [UIColor blackColor];
    [self.view addSubview:_photoView];

    _photoImageView = [[UIImageView alloc] initWithFrame:_photoView.bounds];
    _photoImageView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    _photoImageView.contentMode = UIViewContentModeScaleAspectFill;
    _photoImageView.clipsToBounds = YES;
    _photoImageView.userInteractionEnabled = YES;
    [_photoView addSubview:_photoImageView];

    _photoSettingsButton = [UIButton buttonWithType:UIButtonTypeSystem];
    _photoSettingsButton.frame = CGRectMake(24, 22, 420, 40);
    _photoSettingsButton.backgroundColor = [UIColor colorWithWhite:0 alpha:0.48];
    _photoSettingsButton.tintColor = [UIColor whiteColor];
    _photoSettingsButton.titleLabel.font = [UIFont systemFontOfSize:14.0];
    _photoSettingsButton.contentHorizontalAlignment = UIControlContentHorizontalAlignmentLeft;
    _photoSettingsButton.contentEdgeInsets = UIEdgeInsetsMake(0, 14, 0, 14);
    _photoSettingsButton.layer.cornerRadius = 9.0;
    _photoSettingsButton.alpha = 0.0;
    _photoSettingsButton.hidden = YES;
    [_photoSettingsButton addTarget:self action:@selector(photoSettingsTapped)
                   forControlEvents:UIControlEventTouchUpInside];
    [_photoView addSubview:_photoSettingsButton];

    _photoClockPanel = [[UIView alloc] initWithFrame:CGRectMake(
        self.view.bounds.size.width - 452, 22, 420, 220)];
    _photoClockPanel.backgroundColor = [UIColor clearColor];
    [_photoView addSubview:_photoClockPanel];

    _photoClockBackgroundView = [[UIView alloc]
        initWithFrame:CGRectMake(140, 0, 280, 220)];
    _photoClockBackgroundView.backgroundColor = [UIColor colorWithWhite:0 alpha:0.42];
    _photoClockBackgroundView.layer.cornerRadius = 14.0;
    _photoClockBackgroundView.userInteractionEnabled = NO;
    [_photoClockPanel addSubview:_photoClockBackgroundView];

    _photoClockPan = [[UIPanGestureRecognizer alloc]
        initWithTarget:self action:@selector(photoClockPanned:)];
    _photoClockPan.minimumNumberOfTouches = 2;
    _photoClockPan.maximumNumberOfTouches = 2;
    _photoClockPan.delegate = self;
    [_photoClockPanel addGestureRecognizer:_photoClockPan];
    _photoClockDrag = [[UILongPressGestureRecognizer alloc]
        initWithTarget:self action:@selector(photoClockDragged:)];
    _photoClockDrag.minimumPressDuration = 0.35;
    _photoClockDrag.allowableMovement = 12.0;
    _photoClockDrag.numberOfTouchesRequired = 1;
    [_photoClockPanel addGestureRecognizer:_photoClockDrag];
    _photoClockPinch = [[UIPinchGestureRecognizer alloc]
        initWithTarget:self action:@selector(photoClockPinched:)];
    _photoClockPinch.delegate = self;
    [_photoClockPanel addGestureRecognizer:_photoClockPinch];

    _photoTimeLabel = [[UILabel alloc] initWithFrame:CGRectMake(18, 0, 384, 96)];
    _photoTimeLabel.textAlignment = NSTextAlignmentRight;
    _photoTimeLabel.textColor = [UIColor whiteColor];
    _photoTimeLabel.font = [UIFont systemFontOfSize:78.0 weight:UIFontWeightThin];
    _photoTimeLabel.adjustsFontSizeToFitWidth = YES;
    _photoTimeLabel.minimumScaleFactor = 0.72;
    _photoTimeLabel.shadowColor = [UIColor blackColor];
    _photoTimeLabel.shadowOffset = CGSizeMake(1, 2);
    [_photoClockPanel addSubview:_photoTimeLabel];

    _photoDateLabel = [[UILabel alloc] initWithFrame:CGRectMake(18, 92, 384, 38)];
    _photoDateLabel.textAlignment = NSTextAlignmentRight;
    _photoDateLabel.textColor = [UIColor whiteColor];
    _photoDateLabel.font = [UIFont systemFontOfSize:22.0];
    _photoDateLabel.adjustsFontSizeToFitWidth = YES;
    _photoDateLabel.minimumScaleFactor = 0.72;
    _photoDateLabel.shadowColor = [UIColor blackColor];
    _photoDateLabel.shadowOffset = CGSizeMake(1, 1);
    [_photoClockPanel addSubview:_photoDateLabel];

    _photoWeatherRow = [[UIView alloc] initWithFrame:CGRectMake(18, 132, 384, 46)];
    _photoWeatherIconView = [[QuietWeatherIconView alloc]
        initWithFrame:CGRectMake(0, 0, 44, 44)];
    [_photoWeatherRow addSubview:_photoWeatherIconView];

    _photoWeatherTemperatureLabel = [[UILabel alloc] initWithFrame:CGRectMake(52, 0, 102, 44)];
    _photoWeatherTemperatureLabel.textColor = [UIColor whiteColor];
    _photoWeatherTemperatureLabel.font = [UIFont systemFontOfSize:28.0 weight:UIFontWeightLight];
    _photoWeatherTemperatureLabel.adjustsFontSizeToFitWidth = YES;
    _photoWeatherTemperatureLabel.minimumScaleFactor = 0.72;
    _photoWeatherTemperatureLabel.shadowColor = [UIColor blackColor];
    _photoWeatherTemperatureLabel.shadowOffset = CGSizeMake(0, 1);
    [_photoWeatherRow addSubview:_photoWeatherTemperatureLabel];

    _photoWeatherLocationLabel = [[UILabel alloc] initWithFrame:CGRectMake(156, 2, 228, 40)];
    _photoWeatherLocationLabel.textColor = [UIColor colorWithWhite:0.82 alpha:1.0];
    _photoWeatherLocationLabel.font = [UIFont systemFontOfSize:16.0];
    _photoWeatherLocationLabel.textAlignment = NSTextAlignmentRight;
    _photoWeatherLocationLabel.adjustsFontSizeToFitWidth = YES;
    _photoWeatherLocationLabel.minimumScaleFactor = 0.7;
    _photoWeatherLocationLabel.shadowColor = [UIColor blackColor];
    _photoWeatherLocationLabel.shadowOffset = CGSizeMake(0, 1);
    [_photoWeatherRow addSubview:_photoWeatherLocationLabel];
    _photoWeatherRow.hidden = YES;
    [_photoClockPanel addSubview:_photoWeatherRow];

    _photoDaylightLabel = [[UILabel alloc] initWithFrame:CGRectMake(193, 181, 209, 22)];
    _photoDaylightLabel.textColor = [UIColor colorWithWhite:0.84 alpha:1.0];
    _photoDaylightLabel.font = [UIFont systemFontOfSize:12.0];
    _photoDaylightLabel.textAlignment = NSTextAlignmentRight;
    _photoDaylightLabel.hidden = YES;
    [_photoClockPanel addSubview:_photoDaylightLabel];

    _photoDaylightProgress = [[UIProgressView alloc] initWithProgressViewStyle:
        UIProgressViewStyleDefault];
    _photoDaylightProgress.frame = CGRectMake(193, 211, 209, 3);
    _photoDaylightProgress.progressTintColor = [UIColor colorWithRed:1.0 green:0.75 blue:0.2 alpha:1.0];
    _photoDaylightProgress.trackTintColor = [UIColor colorWithWhite:1.0 alpha:0.18];
    _photoDaylightProgress.hidden = YES;
    [_photoClockPanel addSubview:_photoDaylightProgress];

    _photoStatusLabel = [[UILabel alloc] initWithFrame:CGRectMake(
        170, self.view.bounds.size.height / 2.0 - 30, self.view.bounds.size.width - 340, 60)];
    _photoStatusLabel.autoresizingMask = UIViewAutoresizingFlexibleWidth |
                                         UIViewAutoresizingFlexibleTopMargin |
                                         UIViewAutoresizingFlexibleBottomMargin;
    _photoStatusLabel.backgroundColor = [UIColor colorWithWhite:0 alpha:0.55];
    _photoStatusLabel.textColor = [UIColor whiteColor];
    _photoStatusLabel.textAlignment = NSTextAlignmentCenter;
    _photoStatusLabel.font = [UIFont systemFontOfSize:16.0];
    _photoStatusLabel.numberOfLines = 2;
    _photoStatusLabel.layer.cornerRadius = 12.0;
    _photoStatusLabel.layer.masksToBounds = YES;
    _photoStatusLabel.text = @"首次開啟時會詢問相片權限";
    [_photoView addSubview:_photoStatusLabel];

    _photoToolControls = [[UIView alloc] initWithFrame:CGRectMake(
        24, self.view.bounds.size.height - 258, 190, 234)];
    _photoToolControls.autoresizingMask = UIViewAutoresizingFlexibleTopMargin |
                                          UIViewAutoresizingFlexibleRightMargin;

    _photoToolActions = @[@"open_youtube", @"screenshot_all", @"paste"];
    NSArray *toolTitles = @[@"YouTube", @"全螢幕截圖", @"貼上"];
    for (NSInteger index = 0; index < (NSInteger)toolTitles.count; index++) {
        UIButton *button = [UIButton buttonWithType:UIButtonTypeSystem];
        button.frame = CGRectMake(0, index * 66, 190, 58);
        button.tag = index;
        button.backgroundColor = [UIColor colorWithWhite:0.05 alpha:0.68];
        button.tintColor = [UIColor whiteColor];
        button.titleLabel.font = [UIFont systemFontOfSize:16.0 weight:UIFontWeightSemibold];
        button.layer.cornerRadius = 10.0;
        button.layer.borderColor = [UIColor colorWithRed:0.35 green:0.85 blue:1.0 alpha:0.28].CGColor;
        button.layer.borderWidth = 1.0;
        [button setTitle:toolTitles[index] forState:UIControlStateNormal];
        [button addTarget:self action:@selector(photoToolTapped:)
            forControlEvents:UIControlEventTouchUpInside];
        [_photoToolControls addSubview:button];
    }
    _photoToolStatusLabel = [[UILabel alloc] initWithFrame:CGRectMake(0, 202, 190, 32)];
    _photoToolStatusLabel.backgroundColor = [UIColor colorWithWhite:0 alpha:0.48];
    _photoToolStatusLabel.textColor = [UIColor colorWithWhite:0.86 alpha:1.0];
    _photoToolStatusLabel.textAlignment = NSTextAlignmentCenter;
    _photoToolStatusLabel.font = [UIFont systemFontOfSize:12.0];
    _photoToolStatusLabel.text = nil;
    _photoToolStatusLabel.layer.cornerRadius = 8.0;
    _photoToolStatusLabel.layer.masksToBounds = YES;
    _photoToolStatusLabel.hidden = YES;
    [_photoToolControls addSubview:_photoToolStatusLabel];
    _photoToolControls.hidden = YES;
    [_photoView addSubview:_photoToolControls];

    UITapGestureRecognizer *tap = [[UITapGestureRecognizer alloc]
        initWithTarget:self action:@selector(photoSurfaceTapped)];
    [_photoImageView addGestureRecognizer:tap];

    [self applyPhotoClockSettings];
}

- (void)buildNASAPage {
    _nasaView = [[UIView alloc] initWithFrame:self.view.bounds];
    _nasaView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    _nasaView.backgroundColor = [UIColor colorWithRed:5.0 / 255.0
                                                green:10.0 / 255.0
                                                 blue:20.0 / 255.0 alpha:1.0];
    [self.view addSubview:_nasaView];

    UILabel *heading = [[UILabel alloc] initWithFrame:CGRectMake(26, 18, 760, 42)];
    heading.text = @"NASA · ASTRONOMY PICTURE OF THE DAY";
    heading.textColor = [UIColor colorWithRed:0.35 green:0.85 blue:1.0 alpha:1.0];
    heading.font = [UIFont boldSystemFontOfSize:22.0];
    [_nasaView addSubview:heading];

    UIView *imagePanel = [[UIView alloc] initWithFrame:CGRectMake(24, 72, 604, 630)];
    imagePanel.backgroundColor = [UIColor colorWithWhite:1.0 alpha:0.05];
    imagePanel.layer.cornerRadius = 12.0;
    imagePanel.layer.masksToBounds = YES;
    [_nasaView addSubview:imagePanel];

    _nasaImageView = [[UIImageView alloc] initWithFrame:CGRectMake(10, 10, 584, 574)];
    _nasaImageView.contentMode = UIViewContentModeScaleAspectFit;
    _nasaImageView.backgroundColor = [UIColor blackColor];
    [imagePanel addSubview:_nasaImageView];

    _nasaStatusLabel = [[UILabel alloc] initWithFrame:CGRectMake(14, 590, 576, 28)];
    _nasaStatusLabel.text = @"等待 Mac 下載 NASA 每日天文圖片";
    _nasaStatusLabel.textColor = [UIColor colorWithWhite:0.62 alpha:1.0];
    _nasaStatusLabel.textAlignment = NSTextAlignmentCenter;
    _nasaStatusLabel.font = [UIFont systemFontOfSize:12.0];
    [imagePanel addSubview:_nasaStatusLabel];

    UIView *infoPanel = [[UIView alloc] initWithFrame:CGRectMake(644, 72, 356, 630)];
    infoPanel.autoresizingMask = UIViewAutoresizingFlexibleLeftMargin;
    infoPanel.backgroundColor = [UIColor colorWithWhite:1.0 alpha:0.05];
    infoPanel.layer.cornerRadius = 12.0;
    infoPanel.layer.masksToBounds = YES;
    [_nasaView addSubview:infoPanel];

    _nasaTitleLabel = [[UILabel alloc] initWithFrame:CGRectMake(18, 18, 320, 100)];
    _nasaTitleLabel.text = @"每日天文圖片";
    _nasaTitleLabel.textColor = [UIColor whiteColor];
    _nasaTitleLabel.font = [UIFont boldSystemFontOfSize:22.0];
    _nasaTitleLabel.numberOfLines = 3;
    [infoPanel addSubview:_nasaTitleLabel];

    _nasaMetaLabel = [[UILabel alloc] initWithFrame:CGRectMake(18, 122, 320, 54)];
    _nasaMetaLabel.text = @"Mac 每六小時檢查一次更新";
    _nasaMetaLabel.textColor = [UIColor colorWithRed:0.35 green:0.85 blue:1.0 alpha:1.0];
    _nasaMetaLabel.font = [UIFont systemFontOfSize:13.0];
    _nasaMetaLabel.numberOfLines = 2;
    [infoPanel addSubview:_nasaMetaLabel];

    _nasaExplanationView = [[UITextView alloc] initWithFrame:CGRectMake(12, 182, 332, 430)];
    _nasaExplanationView.backgroundColor = [UIColor clearColor];
    _nasaExplanationView.textColor = [UIColor colorWithWhite:0.78 alpha:1.0];
    _nasaExplanationView.font = [UIFont systemFontOfSize:15.0];
    _nasaExplanationView.editable = NO;
    _nasaExplanationView.selectable = NO;
    _nasaExplanationView.text = @"圖片會由 Mac 下載並透過 USB 傳送；NASA 暫時無法連線時會沿用快取。";
    [infoPanel addSubview:_nasaExplanationView];
}

- (void)updateClock {
    NSDate *now = [NSDate date];
    if (_currentPage == 0) {
        _clockLabel.text = [_timeFormatter stringFromDate:now];
        _dateLabel.text = [_dateFormatter stringFromDate:now];
    } else if (_currentPage == 2 || _currentPage == 3) {
        _photoTimeLabel.text = [_photoTimeFormatter stringFromDate:now];
        _photoDateLabel.text = [_photoDateFormatter stringFromDate:now];
        [self updateDaylightProgress];
    }
}

- (void)updateClockTimerForCurrentPage {
    [_clockTimer invalidate];
    _clockTimer = nil;
    if (_currentPage != 0 && _currentPage != 2 && _currentPage != 3) return;

    [self updateClock];
    NSTimeInterval interval = _currentPage == 0 ? 1.0 : 60.0;
    _clockTimer = [NSTimer scheduledTimerWithTimeInterval:interval
        target:self selector:@selector(updateClock) userInfo:nil repeats:YES];
    if (_currentPage == 0) {
        _clockTimer.tolerance = 0.05;
    } else {
        // The photo clock has minute precision. Align its 60-second timer to
        // the next minute boundary instead of formatting four labels each second.
        NSTimeInterval now = [[NSDate date] timeIntervalSince1970];
        NSTimeInterval delay = 60.02 - fmod(now, 60.0);
        _clockTimer.fireDate = [NSDate dateWithTimeIntervalSinceNow:delay];
        _clockTimer.tolerance = 0.15;
    }
}

- (BOOL)quietBoolForKey:(NSString *)key defaultValue:(BOOL)defaultValue {
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    return [defaults objectForKey:key] ? [defaults boolForKey:key] : defaultValue;
}

- (void)layoutPhotoClockContent {
    if (!_photoClockPanel) return;
    _photoDaylightLabel.frame = CGRectMake(193, 181, 209, 22);
    _photoDaylightProgress.frame = CGRectMake(193, 211, 209, 3);
    _photoTimeLabel.frame = CGRectMake(18, 0, 384, 96);
    _photoDateLabel.frame = CGRectMake(18, 92, 384, 38);
    _photoWeatherRow.frame = CGRectMake(18, 132, 384, 46);
    _photoWeatherLocationLabel.textAlignment = NSTextAlignmentLeft;
    _photoWeatherLocationLabel.numberOfLines = 1;

    CGSize temperatureSize = [_photoWeatherTemperatureLabel
        sizeThatFits:CGSizeMake(116, 44)];
    CGFloat iconWidth = 44.0;
    CGFloat temperatureWidth = MIN(116.0,
        MAX(72.0, ceil(temperatureSize.width) + 4.0));
    CGFloat locationWidth = 0.0;
    BOOL showLocation = !_photoWeatherLocationLabel.hidden &&
        _photoWeatherLocationLabel.text.length > 0;
    if (showLocation) {
        CGSize locationSize = [_photoWeatherLocationLabel
            sizeThatFits:CGSizeMake(220.0, 34.0)];
        locationWidth = MIN(220.0, MAX(24.0, ceil(locationSize.width) + 2.0));
        locationWidth = MIN(locationWidth,
            MAX(0.0, 384.0 - iconWidth - temperatureWidth - 18.0));
    }
    CGFloat contentWidth = iconWidth + 8.0 + temperatureWidth +
        (locationWidth > 0.0 ? 10.0 + locationWidth : 0.0);
    CGFloat iconX = MAX(0.0, 384.0 - contentWidth);
    CGFloat temperatureX = iconX + iconWidth + 8.0;
    _photoWeatherIconView.frame = CGRectMake(iconX, 0, iconWidth, 44);
    _photoWeatherTemperatureLabel.frame = CGRectMake(
        temperatureX, 0, temperatureWidth, 44);
    _photoWeatherLocationLabel.frame = CGRectMake(
        temperatureX + temperatureWidth + 10.0, 10.0,
        locationWidth, 34.0);
}

- (void)applyPhotoClockScale:(CGFloat)requestedScale {
    CGFloat widthFit = (self.view.bounds.size.width - 24.0) / 420.0;
    CGFloat heightFit = (self.view.bounds.size.height - 24.0) / 220.0;
    CGFloat fittedMaximum = MIN(2.5, MIN(widthFit, heightFit));
    _photoClockScale = (CGFloat)QuietClampClockScale(requestedScale);
    _photoClockScale = MIN(_photoClockScale, MAX(0.75, fittedMaximum));
    _photoClockPanel.transform = CGAffineTransformMakeScale(
        _photoClockScale, _photoClockScale);
}

- (void)refreshPhotoClockRenderingScale {
    CGFloat contentsScale = (CGFloat)QuietRenderingScale(
        [UIScreen mainScreen].scale, _photoClockScale);
    QuietApplyContentsScale(_photoClockPanel, contentsScale);
}

- (CGRect)photoClockCenterRangeAllowingOverflow:(BOOL)allowOverflow {
    CGFloat width = CGRectGetWidth(_photoClockPanel.bounds) * _photoClockScale;
    CGFloat height = CGRectGetHeight(_photoClockPanel.bounds) * _photoClockScale;
    CGFloat overflow = allowOverflow ? 0.2 : 0.0;
    QuietScalarRange x = QuietClockCenterRange(
        self.view.bounds.size.width, width, overflow);
    QuietScalarRange y = QuietClockCenterRange(
        self.view.bounds.size.height, height, overflow);
    return CGRectMake(x.minimum, y.minimum,
                      x.maximum - x.minimum, y.maximum - y.minimum);
}

- (void)constrainPhotoClock {
    if (!_photoClockPanel) return;
    CGRect range = [self photoClockCenterRangeAllowingOverflow:YES];
    CGPoint center = _photoClockPanel.center;
    center.x = MIN(CGRectGetMaxX(range), MAX(CGRectGetMinX(range), center.x));
    center.y = MIN(CGRectGetMaxY(range), MAX(CGRectGetMinY(range), center.y));
    CGFloat screenScale = MAX(1.0, [UIScreen mainScreen].scale);
    center.x = round(center.x * screenScale) / screenScale;
    center.y = round(center.y * screenScale) / screenScale;
    _photoClockPanel.center = center;
}

- (void)restorePhotoClockPosition {
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    if (![defaults boolForKey:kQuietClockPositionKey]) {
        _photoClockPanel.center = CGPointMake(
            self.view.bounds.size.width - CGRectGetWidth(_photoClockPanel.frame) / 2.0 - 32.0,
            CGRectGetHeight(_photoClockPanel.frame) / 2.0 + 22.0);
        [self constrainPhotoClock];
        return;
    }
    BOOL usesOverflowRange = [defaults integerForKey:kQuietClockPositionRangeKey] >= 1;
    CGRect range = [self photoClockCenterRangeAllowingOverflow:usesOverflowRange];
    CGFloat xRatio = MIN(1.0, MAX(0.0, [defaults doubleForKey:kQuietClockXRatioKey]));
    CGFloat yRatio = MIN(1.0, MAX(0.0, [defaults doubleForKey:kQuietClockYRatioKey]));
    _photoClockPanel.center = CGPointMake(
        CGRectGetMinX(range) + CGRectGetWidth(range) * xRatio,
        CGRectGetMinY(range) + CGRectGetHeight(range) * yRatio);
    [self constrainPhotoClock];
}

- (void)savePhotoClockPosition {
    CGRect range = [self photoClockCenterRangeAllowingOverflow:YES];
    CGFloat width = MAX(1.0, CGRectGetWidth(range));
    CGFloat height = MAX(1.0, CGRectGetHeight(range));
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    [defaults setDouble:MIN(1.0, MAX(0.0,
        (_photoClockPanel.center.x - CGRectGetMinX(range)) / width)) forKey:kQuietClockXRatioKey];
    [defaults setDouble:MIN(1.0, MAX(0.0,
        (_photoClockPanel.center.y - CGRectGetMinY(range)) / height)) forKey:kQuietClockYRatioKey];
    [defaults setBool:YES forKey:kQuietClockPositionKey];
    [defaults setInteger:1 forKey:kQuietClockPositionRangeKey];
    [defaults synchronize];
}

- (void)applyPhotoClockSettings {
    if (!_photoClockPanel) return;
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    BOOL background = [self quietBoolForKey:kQuietClockBackgroundKey defaultValue:YES];
    _photoClockBackgroundView.backgroundColor = background
        ? [UIColor colorWithWhite:0 alpha:0.42] : [UIColor clearColor];
    _photoClockBackgroundView.layer.borderColor =
        [UIColor colorWithWhite:1.0 alpha:0.14].CGColor;
    _photoClockBackgroundView.layer.borderWidth = background ? 1.0 : 0.0;
    [self layoutPhotoClockContent];
    CGFloat scale = [defaults objectForKey:kQuietClockScaleKey]
        ? [defaults doubleForKey:kQuietClockScaleKey] : 1.0;
    [self applyPhotoClockScale:scale];
    [self restorePhotoClockPosition];
    [self refreshPhotoClockRenderingScale];
}

- (void)resetPhotoClockLayout {
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    [defaults removeObjectForKey:kQuietClockScaleKey];
    [defaults removeObjectForKey:kQuietClockPositionKey];
    [defaults removeObjectForKey:kQuietClockXRatioKey];
    [defaults removeObjectForKey:kQuietClockYRatioKey];
    [defaults removeObjectForKey:kQuietClockPositionRangeKey];
    [defaults synchronize];
    [self applyPhotoClockSettings];
}

- (void)photoClockPanned:(UIPanGestureRecognizer *)gesture {
    if (_currentPage != 2) return;
    [self showPhotoSettingsButton];
    if (gesture.state == UIGestureRecognizerStateBegan) {
        _photoClockPanStartCenter = _photoClockPanel.center;
    } else if (gesture.state == UIGestureRecognizerStateChanged) {
        CGPoint translation = [gesture translationInView:_photoView];
        _photoClockPanel.center = CGPointMake(
            _photoClockPanStartCenter.x + translation.x,
            _photoClockPanStartCenter.y + translation.y);
        [self constrainPhotoClock];
    } else if (gesture.state == UIGestureRecognizerStateEnded ||
               gesture.state == UIGestureRecognizerStateCancelled) {
        [self savePhotoClockPosition];
    }
}

- (void)photoClockDragged:(UILongPressGestureRecognizer *)gesture {
    if (_currentPage != 2) return;
    [self showPhotoSettingsButton];
    CGPoint point = [gesture locationInView:_photoView];
    if (gesture.state == UIGestureRecognizerStateBegan) {
        _photoClockPanStartCenter = _photoClockPanel.center;
        _photoClockDragStartPoint = point;
    } else if (gesture.state == UIGestureRecognizerStateChanged) {
        _photoClockPanel.center = CGPointMake(
            _photoClockPanStartCenter.x + point.x - _photoClockDragStartPoint.x,
            _photoClockPanStartCenter.y + point.y - _photoClockDragStartPoint.y);
        [self constrainPhotoClock];
    } else if (gesture.state == UIGestureRecognizerStateEnded ||
               gesture.state == UIGestureRecognizerStateCancelled) {
        [self savePhotoClockPosition];
    }
}

- (void)displayTapped:(UITapGestureRecognizer *)gesture {
    if (gesture.state != UIGestureRecognizerStateRecognized ||
        _currentPage != 1 || !_displayConnected || !_receiver) return;
    CGPoint point = [gesture locationInView:_videoView];
    CGSize viewSize = _videoView.bounds.size;
    double x, y;
    if (!QuietNormalizeVideoPoint(point.x, point.y,
                                  viewSize.width, viewSize.height,
                                  _displayPixelSize.width, _displayPixelSize.height,
                                  &x, &y)) return;
    if ([_receiver sendTouchPhase:@"began" x:x y:y]) {
        [_receiver sendTouchPhase:@"ended" x:x y:y];
    }
}

- (void)photoClockPinched:(UIPinchGestureRecognizer *)gesture {
    if (_currentPage != 2) return;
    [self showPhotoSettingsButton];
    if (gesture.state == UIGestureRecognizerStateBegan) {
        _photoClockPinchStartScale = _photoClockScale;
    } else if (gesture.state == UIGestureRecognizerStateChanged) {
        [self applyPhotoClockScale:_photoClockPinchStartScale * gesture.scale];
        [self constrainPhotoClock];
    } else if (gesture.state == UIGestureRecognizerStateEnded ||
               gesture.state == UIGestureRecognizerStateCancelled) {
        [self refreshPhotoClockRenderingScale];
        NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
        [defaults setDouble:_photoClockScale forKey:kQuietClockScaleKey];
        [defaults synchronize];
        [self savePhotoClockPosition];
    }
}

- (BOOL)gestureRecognizer:(UIGestureRecognizer *)gesture
        shouldRecognizeSimultaneouslyWithGestureRecognizer:(UIGestureRecognizer *)other {
    return (gesture == _photoClockPan && other == _photoClockPinch) ||
           (gesture == _photoClockPinch && other == _photoClockPan);
}

- (void)showPhotoSettingsButton {
    if (_currentPage != 2) return;
    [NSObject cancelPreviousPerformRequestsWithTarget:self
        selector:@selector(hidePhotoSettingsButton) object:nil];
    _photoSettingsButton.hidden = NO;
    [UIView animateWithDuration:0.18 animations:^{
        self->_photoSettingsButton.alpha = 0.88;
    }];
    [self performSelector:@selector(hidePhotoSettingsButton)
               withObject:nil afterDelay:5.0];
}

- (void)hidePhotoSettingsButton {
    [NSObject cancelPreviousPerformRequestsWithTarget:self
        selector:@selector(hidePhotoSettingsButton) object:nil];
    if (_photoSettingsButton.hidden) return;
    [UIView animateWithDuration:0.22 animations:^{
        self->_photoSettingsButton.alpha = 0.0;
    } completion:^(BOOL finished) {
        if (finished) self->_photoSettingsButton.hidden = YES;
    }];
}

- (void)photoSurfaceTapped {
    if (_currentPage == 2) [self showPhotoSettingsButton];
}

- (void)photoToolTapped:(UIButton *)button {
    if (_currentPage != 3 || button.tag < 0 ||
        button.tag >= (NSInteger)_photoToolActions.count) return;
    NSString *action = _photoToolActions[button.tag];
    BOOL sent = [_receiver sendAction:action];
    [NSObject cancelPreviousPerformRequestsWithTarget:self
        selector:@selector(hidePhotoToolStatus) object:nil];
    _photoToolStatusLabel.hidden = NO;
    _photoToolStatusLabel.textColor = sent
        ? [UIColor colorWithWhite:0.86 alpha:1.0]
        : [UIColor colorWithRed:1.0 green:0.45 blue:0.35 alpha:1.0];
    _photoToolStatusLabel.text = sent ? @"指令已送出" : @"Mac 尚未連線";
    [self performSelector:@selector(hidePhotoToolStatus)
               withObject:nil afterDelay:2.0];
}

- (void)hidePhotoToolStatus {
    _photoToolStatusLabel.hidden = YES;
}

- (NSArray *)selectedPhotoAlbumIdentifiers {
    NSArray *stored = [[NSUserDefaults standardUserDefaults]
        arrayForKey:kQuietPhotoAlbumIDsKey];
    NSMutableArray *identifiers = [NSMutableArray array];
    for (id value in stored) {
        if ([value isKindOfClass:[NSString class]] && [value length] > 0) {
            [identifiers addObject:value];
        }
    }
    return identifiers.count > 0 ? identifiers : @[kQuietAllPhotosIdentifier];
}

- (void)updatePhotoAlbumButton {
    NSArray *identifiers = [self selectedPhotoAlbumIdentifiers];
    NSString *summary = @"所有照片";
    if (![identifiers containsObject:kQuietAllPhotosIdentifier]) {
        PHFetchResult *result = [PHAssetCollection
            fetchAssetCollectionsWithLocalIdentifiers:identifiers options:nil];
        NSMutableArray *names = [NSMutableArray array];
        [result enumerateObjectsUsingBlock:^(PHAssetCollection *collection,
                                             NSUInteger index, BOOL *stop) {
            (void)index;
            (void)stop;
            [names addObject:collection.localizedTitle ?: @"未命名相簿"];
        }];
        if (names.count == 1) summary = [names firstObject];
        else if (names.count > 1) {
            summary = [NSString stringWithFormat:@"%@ 等 %lu 個相簿",
                [names firstObject], (unsigned long)names.count];
        } else {
            summary = [NSString stringWithFormat:@"%lu 個相簿",
                (unsigned long)identifiers.count];
        }
    }
    [_photoSettingsButton setTitle:
        [NSString stringWithFormat:@"相簿：%@　·　顯示設定", summary]
        forState:UIControlStateNormal];
}

- (void)photoSettingsTapped {
    [self showPhotoSettingsButton];
    UIAlertController *menu = [UIAlertController alertControllerWithTitle:@"相簿時鐘"
        message:@"設定只儲存在這台 iPad"
        preferredStyle:UIAlertControllerStyleActionSheet];
    [menu addAction:[UIAlertAction actionWithTitle:@"選擇相簿"
        style:UIAlertActionStyleDefault handler:^(UIAlertAction *action) {
            (void)action;
            [self presentAlbumPicker];
        }]];
    [menu addAction:[UIAlertAction actionWithTitle:
        [NSString stringWithFormat:@"輪播速度：%@", [self photoSlideshowIntervalText]]
        style:UIAlertActionStyleDefault handler:^(UIAlertAction *action) {
            (void)action;
            [self performSelector:@selector(presentPhotoIntervalPicker)
                       withObject:nil afterDelay:0.35];
        }]];
    [menu addAction:[UIAlertAction actionWithTitle:@"時間字形"
        style:UIAlertActionStyleDefault handler:^(UIAlertAction *action) {
            (void)action;
            [self presentFontPickerForKey:kQuietTimeFontKey title:@"時間字形"];
        }]];
    [menu addAction:[UIAlertAction actionWithTitle:@"日期字形"
        style:UIAlertActionStyleDefault handler:^(UIAlertAction *action) {
            (void)action;
            [self presentFontPickerForKey:kQuietDateFontKey title:@"日期字形"];
        }]];
    [menu addAction:[UIAlertAction actionWithTitle:@"天氣字形"
        style:UIAlertActionStyleDefault handler:^(UIAlertAction *action) {
            (void)action;
            [self presentFontPickerForKey:kQuietWeatherFontKey title:@"天氣字形"];
        }]];

    BOOL background = [self quietBoolForKey:kQuietClockBackgroundKey defaultValue:YES];
    BOOL weather = [self quietBoolForKey:kQuietWeatherEnabledKey defaultValue:YES];
    BOOL location = [self quietBoolForKey:kQuietWeatherLocationKey defaultValue:NO];
    BOOL daylight = [self quietBoolForKey:kQuietWeatherDaylightKey defaultValue:YES];
    [menu addAction:[UIAlertAction actionWithTitle:
        [NSString stringWithFormat:@"%@ 半透明底板", background ? @"✓" : @"○"]
        style:UIAlertActionStyleDefault handler:^(UIAlertAction *action) {
            (void)action;
            [self togglePhotoPreferenceKey:kQuietClockBackgroundKey defaultValue:YES];
        }]];
    [menu addAction:[UIAlertAction actionWithTitle:@"重設時鐘位置與大小"
        style:UIAlertActionStyleDefault handler:^(UIAlertAction *action) {
            (void)action;
            [self resetPhotoClockLayout];
        }]];
    [menu addAction:[UIAlertAction actionWithTitle:
        [NSString stringWithFormat:@"%@ 天氣", weather ? @"✓" : @"○"]
        style:UIAlertActionStyleDefault handler:^(UIAlertAction *action) {
            (void)action;
            [self togglePhotoPreferenceKey:kQuietWeatherEnabledKey defaultValue:YES];
        }]];
    [menu addAction:[UIAlertAction actionWithTitle:
        [NSString stringWithFormat:@"%@ 地點名稱", location ? @"✓" : @"○"]
        style:UIAlertActionStyleDefault handler:^(UIAlertAction *action) {
            (void)action;
            [self togglePhotoPreferenceKey:kQuietWeatherLocationKey defaultValue:NO];
        }]];
    [menu addAction:[UIAlertAction actionWithTitle:
        [NSString stringWithFormat:@"%@ 日照進度", daylight ? @"✓" : @"○"]
        style:UIAlertActionStyleDefault handler:^(UIAlertAction *action) {
            (void)action;
            [self togglePhotoPreferenceKey:kQuietWeatherDaylightKey defaultValue:YES];
        }]];
    [menu addAction:[UIAlertAction actionWithTitle:@"取消"
        style:UIAlertActionStyleCancel handler:nil]];
    UIPopoverPresentationController *popover = menu.popoverPresentationController;
    popover.sourceView = _photoSettingsButton;
    popover.sourceRect = _photoSettingsButton.bounds;
    [self presentViewController:menu animated:YES completion:nil];
}

- (NSTimeInterval)photoSlideshowInterval {
    NSTimeInterval interval = [[NSUserDefaults standardUserDefaults]
        doubleForKey:kQuietPhotoIntervalKey];
    return interval >= 5.0 ? interval : 45.0;
}

- (NSString *)photoSlideshowIntervalText {
    NSInteger seconds = (NSInteger)([self photoSlideshowInterval] + 0.5);
    if (seconds >= 60 && seconds % 60 == 0) {
        return [NSString stringWithFormat:@"%ld 分鐘", (long)(seconds / 60)];
    }
    return [NSString stringWithFormat:@"%ld 秒", (long)seconds];
}

- (void)presentPhotoIntervalPicker {
    UIAlertController *picker = [UIAlertController alertControllerWithTitle:@"相簿輪播速度"
        message:@"選擇每張照片停留的時間"
        preferredStyle:UIAlertControllerStyleActionSheet];
    NSInteger current = (NSInteger)([self photoSlideshowInterval] + 0.5);
    NSArray *choices = @[@10, @30, @45, @60, @180];
    for (NSNumber *choice in choices) {
        NSInteger seconds = [choice integerValue];
        NSString *name = seconds >= 60
            ? [NSString stringWithFormat:@"%ld 分鐘", (long)(seconds / 60)]
            : [NSString stringWithFormat:@"%ld 秒", (long)seconds];
        NSString *title = [NSString stringWithFormat:@"%@%@",
            seconds == current ? @"✓ " : @"", name];
        [picker addAction:[UIAlertAction actionWithTitle:title
            style:UIAlertActionStyleDefault handler:^(UIAlertAction *action) {
                (void)action;
                NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
                [defaults setDouble:[choice doubleValue] forKey:kQuietPhotoIntervalKey];
                [defaults synchronize];
                [self schedulePhotoTimer];
            }]];
    }
    [picker addAction:[UIAlertAction actionWithTitle:@"取消"
        style:UIAlertActionStyleCancel handler:nil]];
    UIPopoverPresentationController *popover = picker.popoverPresentationController;
    popover.sourceView = _photoSettingsButton;
    popover.sourceRect = _photoSettingsButton.bounds;
    [self presentViewController:picker animated:YES completion:nil];
}

- (void)presentAlbumPicker {
    PHAuthorizationStatus status = [PHPhotoLibrary authorizationStatus];
    if (status == PHAuthorizationStatusNotDetermined) {
        __weak LegacyViewController *weakSelf = self;
        [PHPhotoLibrary requestAuthorization:^(PHAuthorizationStatus result) {
            dispatch_async(dispatch_get_main_queue(), ^{
                LegacyViewController *strongSelf = weakSelf;
                if (!strongSelf) return;
                if (result == PHAuthorizationStatusAuthorized) {
                    [strongSelf presentAlbumPicker];
                } else {
                    [strongSelf startPhotoSlideshow];
                }
            });
        }];
        return;
    }
    if (status != PHAuthorizationStatusAuthorized) {
        _photoStatusLabel.hidden = NO;
        _photoStatusLabel.text = @"請到「設定 > 隱私權 > 照片」允許 QuietPanel 讀取相簿";
        return;
    }

    QuietAlbumPickerController *picker = [[QuietAlbumPickerController alloc]
        initWithSelectedIdentifiers:[self selectedPhotoAlbumIdentifiers]];
    __weak LegacyViewController *weakSelf = self;
    picker.applyHandler = ^(NSArray *identifiers) {
        LegacyViewController *strongSelf = weakSelf;
        if (!strongSelf) return;
        NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
        [defaults setObject:identifiers forKey:kQuietPhotoAlbumIDsKey];
        [defaults synchronize];
        strongSelf->_photoAssets = nil;
        strongSelf->_photoImageView.image = nil;
        [strongSelf updatePhotoAlbumButton];
        if (strongSelf->_currentPage == 2) [strongSelf loadPhotoCatalog];
    };
    UINavigationController *navigation = [[UINavigationController alloc]
        initWithRootViewController:picker];
    navigation.modalPresentationStyle = UIModalPresentationFormSheet;
    [self presentViewController:navigation animated:YES completion:nil];
}

- (void)presentFontPickerForKey:(NSString *)key title:(NSString *)title {
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    NSInteger selected = QuietNormalizedFontIndex([defaults integerForKey:key]);
    UIAlertController *picker = [UIAlertController alertControllerWithTitle:title
        message:@"與 Android 版使用相同的開放字形"
        preferredStyle:UIAlertControllerStyleActionSheet];
    NSArray *fontNames = QuietFontNames();
    for (NSInteger index = 0; index < (NSInteger)fontNames.count; index++) {
        NSString *name = fontNames[index];
        NSString *label = index == selected
            ? [NSString stringWithFormat:@"✓ %@", name] : name;
        [picker addAction:[UIAlertAction actionWithTitle:label
            style:UIAlertActionStyleDefault handler:^(UIAlertAction *action) {
                (void)action;
                [defaults setInteger:index forKey:key];
                [defaults synchronize];
                [self applyPhotoFontSettings];
            }]];
    }
    [picker addAction:[UIAlertAction actionWithTitle:@"取消"
        style:UIAlertActionStyleCancel handler:nil]];
    UIPopoverPresentationController *popover = picker.popoverPresentationController;
    popover.sourceView = _photoSettingsButton;
    popover.sourceRect = _photoSettingsButton.bounds;
    [self presentViewController:picker animated:YES completion:nil];
}

- (void)togglePhotoPreferenceKey:(NSString *)key defaultValue:(BOOL)defaultValue {
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    [defaults setBool:![self quietBoolForKey:key defaultValue:defaultValue] forKey:key];
    [defaults synchronize];
    if ([key isEqual:kQuietClockBackgroundKey]) {
        [self applyPhotoClockSettings];
        return;
    }
    if (_weatherState) [self applyWeather:_weatherState];
    else [self hideWeather];
}

- (void)applyPhotoFontSettings {
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    NSInteger timeIndex = QuietNormalizedFontIndex([defaults integerForKey:kQuietTimeFontKey]);
    NSInteger dateIndex = QuietNormalizedFontIndex([defaults integerForKey:kQuietDateFontKey]);
    NSInteger weatherIndex = QuietNormalizedFontIndex([defaults integerForKey:kQuietWeatherFontKey]);
    _photoTimeLabel.font = QuietFontAtIndex(timeIndex, 78.0,
        [UIFont systemFontOfSize:78.0 weight:UIFontWeightThin]);
    _photoDateLabel.font = QuietFontAtIndex(dateIndex, 22.0,
        [UIFont systemFontOfSize:22.0]);
    _photoWeatherTemperatureLabel.font = QuietFontAtIndex(weatherIndex, 28.0,
        [UIFont systemFontOfSize:28.0 weight:UIFontWeightLight]);
    _photoWeatherLocationLabel.font = QuietFontAtIndex(weatherIndex, 16.0,
        [UIFont systemFontOfSize:16.0]);
    _photoDaylightLabel.font = QuietFontAtIndex(weatherIndex, 12.0,
        [UIFont systemFontOfSize:12.0]);
    for (UIView *view in _photoToolControls.subviews) {
        if ([view isKindOfClass:[UIButton class]]) {
            ((UIButton *)view).titleLabel.font =
                [UIFont systemFontOfSize:16.0 weight:UIFontWeightSemibold];
        }
    }

    if (QuietFontUsesEnglishDate(dateIndex)) {
        _photoDateFormatter.locale = [[NSLocale alloc] initWithLocaleIdentifier:@"en_US_POSIX"];
        _photoDateFormatter.dateFormat = @"EEE, MMM d";
    } else {
        _photoDateFormatter.locale = [[NSLocale alloc] initWithLocaleIdentifier:@"zh_TW"];
        _photoDateFormatter.dateFormat = @"M月d日 EEEE";
    }
    [self updateClock];
    if (_weatherState) [self applyWeather:_weatherState];
    else [self layoutPhotoClockContent];
}

- (void)loadWeatherCache {
    NSDictionary *weather = [[NSUserDefaults standardUserDefaults]
        dictionaryForKey:kQuietWeatherCacheKey];
    if ([weather isKindOfClass:[NSDictionary class]]) [self applyWeather:weather];
}

- (void)hideWeather {
    _photoWeatherRow.hidden = YES;
    _photoDaylightLabel.hidden = YES;
    _photoDaylightProgress.hidden = YES;
    _dashboardWeatherIconView.hidden = YES;
    _dashboardWeatherLabel.hidden = YES;
    [self layoutPhotoClockContent];
}

- (void)applyWeather:(NSDictionary *)weather {
    NSNumber *temperature = [weather objectForKey:@"temperature_c"];
    NSNumber *code = [weather objectForKey:@"code"];
    NSNumber *updatedAt = [weather objectForKey:@"updated_at"];
    if (![temperature isKindOfClass:[NSNumber class]] ||
        ![code isKindOfClass:[NSNumber class]] ||
        ![updatedAt isKindOfClass:[NSNumber class]]) {
        [self hideWeather];
        return;
    }
    double temperatureValue = [temperature doubleValue];
    int codeValue = [code intValue];
    NSTimeInterval updatedValue = [updatedAt doubleValue];
    if (!isfinite(temperatureValue) || temperatureValue < -100.0 || temperatureValue > 100.0 ||
        codeValue < 0 || codeValue > 99 || !isfinite(updatedValue) || updatedValue <= 0) {
        [self hideWeather];
        return;
    }

    _weatherState = [weather copy];
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    [defaults setObject:_weatherState forKey:kQuietWeatherCacheKey];
    [defaults synchronize];
    NSTimeInterval age = [[NSDate date] timeIntervalSince1970] - updatedValue;
    BOOL stale = [[weather objectForKey:@"stale"] boolValue] || age > 6.0 * 60.0 * 60.0;
    if (![self quietBoolForKey:kQuietWeatherEnabledKey defaultValue:YES] || stale) {
        [self hideWeather];
        return;
    }

    BOOL isDay = ![weather objectForKey:@"is_day"] ||
                 [[weather objectForKey:@"is_day"] boolValue];
    NSString *temperatureText = [NSString stringWithFormat:@"%.0f°C", temperatureValue];
    NSString *location = [weather objectForKey:@"location"];
    if (![location isKindOfClass:[NSString class]]) location = @"";
    BOOL showLocation = [self quietBoolForKey:kQuietWeatherLocationKey defaultValue:NO];

    [_photoWeatherIconView setWeatherCode:codeValue daytime:isDay];
    _photoWeatherTemperatureLabel.text = temperatureText;
    _photoWeatherLocationLabel.text = showLocation ? location : @"";
    _photoWeatherLocationLabel.hidden = !showLocation;
    _photoWeatherRow.hidden = NO;
    [self layoutPhotoClockContent];
    [_dashboardWeatherIconView setWeatherCode:codeValue daytime:isDay];
    _dashboardWeatherLabel.text = showLocation && location.length > 0
        ? [NSString stringWithFormat:@"%@  %@", temperatureText, location]
        : temperatureText;
    CGFloat dashboardTextWidth = MIN(480.0, MAX(44.0,
        ceil([_dashboardWeatherLabel sizeThatFits:CGSizeMake(480, 18)].width)));
    CGFloat dashboardRight = self.view.bounds.size.width - 32.0;
    _dashboardWeatherLabel.frame = CGRectMake(
        dashboardRight - dashboardTextWidth, 80, dashboardTextWidth, 18);
    _dashboardWeatherIconView.frame = CGRectMake(
        dashboardRight - dashboardTextWidth - 24.0, 78, 20, 20);
    _dashboardWeatherIconView.hidden = NO;
    _dashboardWeatherLabel.hidden = NO;

    NSNumber *sunrise = [weather objectForKey:@"sunrise_at_ms"];
    NSNumber *sunset = [weather objectForKey:@"sunset_at_ms"];
    double sunriseValue = [sunrise isKindOfClass:[NSNumber class]] ? [sunrise doubleValue] : -1;
    double sunsetValue = [sunset isKindOfClass:[NSNumber class]] ? [sunset doubleValue] : -1;
    BOOL showDaylight = [self quietBoolForKey:kQuietWeatherDaylightKey defaultValue:YES] &&
        isfinite(sunriseValue) && isfinite(sunsetValue) && sunriseValue > 0 &&
        sunsetValue > sunriseValue;
    if (!showDaylight) {
        _photoDaylightLabel.hidden = YES;
        _photoDaylightProgress.hidden = YES;
        return;
    }
    NSDate *sunriseDate = [NSDate dateWithTimeIntervalSince1970:sunriseValue / 1000.0];
    NSDate *sunsetDate = [NSDate dateWithTimeIntervalSince1970:sunsetValue / 1000.0];
    _photoDaylightLabel.text = [NSString stringWithFormat:@"日出 %@　日落 %@",
        [_sunTimeFormatter stringFromDate:sunriseDate],
        [_sunTimeFormatter stringFromDate:sunsetDate]];
    _photoDaylightLabel.hidden = NO;
    _photoDaylightProgress.hidden = NO;
    [self updateDaylightProgress];
}

- (void)updateDaylightProgress {
    if (!_weatherState) return;
    double updatedAt = [[_weatherState objectForKey:@"updated_at"] doubleValue];
    if (updatedAt > 0 && [[NSDate date] timeIntervalSince1970] - updatedAt >
        6.0 * 60.0 * 60.0) {
        [self hideWeather];
        return;
    }
    if (_photoDaylightProgress.hidden) return;
    double sunrise = [[_weatherState objectForKey:@"sunrise_at_ms"] doubleValue];
    double sunset = [[_weatherState objectForKey:@"sunset_at_ms"] doubleValue];
    if (sunrise <= 0 || sunset <= sunrise) return;
    double now = [[NSDate date] timeIntervalSince1970] * 1000.0;
    double progress = (now - sunrise) / (sunset - sunrise);
    [_photoDaylightProgress setProgress:(float)MIN(1.0, MAX(0.0, progress)) animated:NO];
}

- (NSString *)rateText:(NSNumber *)number {
    if (![number isKindOfClass:[NSNumber class]]) return @"--";
    double bytes = MAX(0.0, [number doubleValue]);
    if (bytes >= 1000000.0) return [NSString stringWithFormat:@"%.1f MB/s", bytes / 1000000.0];
    if (bytes >= 1000.0) return [NSString stringWithFormat:@"%.0f KB/s", bytes / 1000.0];
    return [NSString stringWithFormat:@"%.0f B/s", bytes];
}

- (NSString *)sizeText:(NSNumber *)number {
    if (![number isKindOfClass:[NSNumber class]]) return @"--";
    double bytes = MAX(0.0, [number doubleValue]);
    if (bytes >= 1099511627776.0) return [NSString stringWithFormat:@"%.1f TB", bytes / 1099511627776.0];
    if (bytes >= 1073741824.0) return [NSString stringWithFormat:@"%.1f GB", bytes / 1073741824.0];
    if (bytes >= 1048576.0) return [NSString stringWithFormat:@"%.0f MB", bytes / 1048576.0];
    return [NSString stringWithFormat:@"%.0f KB", bytes / 1024.0];
}

- (NSString *)uptimeText:(NSNumber *)number {
    if (![number isKindOfClass:[NSNumber class]]) return @"--";
    NSInteger seconds = MAX(0, [number integerValue]);
    NSInteger days = seconds / 86400;
    NSInteger hours = (seconds % 86400) / 3600;
    if (days > 0) return [NSString stringWithFormat:@"%ld天 %ld小時", (long)days, (long)hours];
    return [NSString stringWithFormat:@"%ld小時", (long)hours];
}

- (void)applyMetrics:(NSDictionary *)metrics {
    NSNumber *cpu = [metrics objectForKey:@"cpu"];
    NSNumber *memory = [metrics objectForKey:@"memory"];
    NSNumber *disk = [metrics objectForKey:@"disk"];
    double cpuValue = [cpu isKindOfClass:[NSNumber class]] ? [cpu doubleValue] : 0;
    double memoryValue = [memory isKindOfClass:[NSNumber class]] ? [memory doubleValue] : 0;
    if ([cpu isKindOfClass:[NSNumber class]]) {
        _cpuValueLabel.text = [NSString stringWithFormat:@"%.0f %%",
            MIN(100.0, MAX(0.0, cpuValue))];
        _cpuValueLabel.textColor = cpuValue >= 85.0
            ? [UIColor colorWithRed:1.0 green:0.42 blue:0.28 alpha:1.0]
            : [UIColor whiteColor];
    }
    if ([memory isKindOfClass:[NSNumber class]]) {
        _memoryValueLabel.text = [NSString stringWithFormat:@"%.0f %%",
            MIN(100.0, MAX(0.0, memoryValue))];
    }
    if ([disk isKindOfClass:[NSNumber class]]) {
        double diskValue = MIN(100.0, MAX(0.0, [disk doubleValue]));
        _diskValueLabel.text = [NSString stringWithFormat:@"%.0f %%", diskValue];
        _diskValueLabel.textColor = diskValue >= 90.0
            ? [UIColor colorWithRed:1.0 green:0.42 blue:0.28 alpha:1.0]
            : [UIColor whiteColor];
    }
    _networkValueLabel.text = [NSString stringWithFormat:@"↓ %@\n↑ %@",
        [self rateText:[metrics objectForKey:@"downBps"]],
        [self rateText:[metrics objectForKey:@"upBps"]]];
    [_historyView addCPU:cpuValue memory:memoryValue];

    NSString *host = [metrics objectForKey:@"hostName"];
    if (![host isKindOfClass:[NSString class]] || host.length == 0) host = @"Mac";
    NSNumber *load = [metrics objectForKey:@"load1"];
    NSString *loadText = [load isKindOfClass:[NSNumber class]]
        ? [NSString stringWithFormat:@"%.2f", [load doubleValue]] : @"--";
    _systemDetailLabel.text = [NSString stringWithFormat:
        @"%@  ·  記憶體 %@ / %@  ·  磁碟可用 %@ / %@\n運作時間 %@  ·  系統負載 %@",
        host,
        [self sizeText:[metrics objectForKey:@"memoryUsedBytes"]],
        [self sizeText:[metrics objectForKey:@"memoryTotalBytes"]],
        [self sizeText:[metrics objectForKey:@"diskFreeBytes"]],
        [self sizeText:[metrics objectForKey:@"diskTotalBytes"]],
        [self uptimeText:[metrics objectForKey:@"uptimeSeconds"]], loadText];
}

- (void)startPhotoSlideshow {
    PHAuthorizationStatus status = [PHPhotoLibrary authorizationStatus];
    if (status == PHAuthorizationStatusNotDetermined) {
        _photoStatusLabel.hidden = NO;
        _photoStatusLabel.text = @"正在詢問相片權限…";
        __weak LegacyViewController *weakSelf = self;
        [PHPhotoLibrary requestAuthorization:^(PHAuthorizationStatus result) {
            dispatch_async(dispatch_get_main_queue(), ^{
                LegacyViewController *strongSelf = weakSelf;
                if (strongSelf && result == PHAuthorizationStatusAuthorized) {
                    [strongSelf loadPhotoCatalog];
                } else if (strongSelf) {
                    strongSelf->_photoStatusLabel.text = @"請到「設定 > 隱私權 > 照片」允許 QuietPanel 讀取相簿";
                }
            });
        }];
        return;
    }
    if (status != PHAuthorizationStatusAuthorized) {
        _photoStatusLabel.hidden = NO;
        _photoStatusLabel.text = @"請到「設定 > 隱私權 > 照片」允許 QuietPanel 讀取相簿";
        return;
    }
    if (!_photoAssets) [self loadPhotoCatalog];
    else if (!_photoImageView.image) [self showNextPhoto];
    [self schedulePhotoTimer];
}

- (void)loadPhotoCatalog {
    PHFetchOptions *options = [[PHFetchOptions alloc] init];
    options.predicate = [NSPredicate predicateWithFormat:@"mediaType == %d",
                         PHAssetMediaTypeImage];
    options.sortDescriptors = @[[NSSortDescriptor sortDescriptorWithKey:@"creationDate" ascending:NO]];
    NSArray *identifiers = [self selectedPhotoAlbumIdentifiers];
    NSMutableArray *assets = [NSMutableArray array];
    NSMutableSet *seen = [NSMutableSet set];
    if ([identifiers containsObject:kQuietAllPhotosIdentifier]) {
        PHFetchResult *result = [PHAsset fetchAssetsWithOptions:options];
        [result enumerateObjectsUsingBlock:^(PHAsset *asset, NSUInteger index, BOOL *stop) {
            (void)index;
            (void)stop;
            [assets addObject:asset];
        }];
    } else {
        PHFetchResult *collections = [PHAssetCollection
            fetchAssetCollectionsWithLocalIdentifiers:identifiers options:nil];
        [collections enumerateObjectsUsingBlock:^(PHAssetCollection *collection,
                                                  NSUInteger index, BOOL *stop) {
            (void)index;
            (void)stop;
            PHFetchResult *result = [PHAsset fetchAssetsInAssetCollection:collection
                                                                   options:options];
            [result enumerateObjectsUsingBlock:^(PHAsset *asset,
                                                  NSUInteger assetIndex, BOOL *assetStop) {
                (void)assetIndex;
                (void)assetStop;
                if ([seen containsObject:asset.localIdentifier]) return;
                [seen addObject:asset.localIdentifier];
                [assets addObject:asset];
            }];
        }];
        [assets sortUsingComparator:^NSComparisonResult(PHAsset *left, PHAsset *right) {
            NSDate *a = left.creationDate ?: [NSDate distantPast];
            NSDate *b = right.creationDate ?: [NSDate distantPast];
            return [b compare:a];
        }];
    }
    _photoAssets = [assets copy];
    _photoIndex = 0;
    [self updatePhotoAlbumButton];
    if (_photoAssets.count == 0) {
        _photoStatusLabel.hidden = NO;
        _photoStatusLabel.text = @"所選相簿中沒有可在本機讀取的照片";
        return;
    }
    [self showNextPhoto];
    [self schedulePhotoTimer];
}

- (void)schedulePhotoTimer {
    [_photoTimer invalidate];
    _photoTimer = nil;
    if ((_currentPage != 2 && _currentPage != 3) || _photoAssets.count == 0) return;
    _photoTimer = [NSTimer scheduledTimerWithTimeInterval:[self photoSlideshowInterval]
        target:self selector:@selector(showNextPhoto) userInfo:nil repeats:YES];
    _photoTimer.tolerance = MIN(1.0, [self photoSlideshowInterval] * 0.05);
}

- (void)showNextPhoto {
    if (_photoAssets.count == 0) return;
    PHAsset *asset = [_photoAssets objectAtIndex:(_photoIndex % _photoAssets.count)];
    _photoIndex = (_photoIndex + 1) % _photoAssets.count;
    PHImageRequestOptions *options = [[PHImageRequestOptions alloc] init];
    options.deliveryMode = PHImageRequestOptionsDeliveryModeHighQualityFormat;
    options.resizeMode = PHImageRequestOptionsResizeModeFast;
    options.networkAccessAllowed = NO;
    CGFloat screenScale = [UIScreen mainScreen].scale;
    CGSize viewSize = _photoImageView.bounds.size;
    CGSize targetSize = CGSizeMake(MAX(1.0, viewSize.width * screenScale),
                                   MAX(1.0, viewSize.height * screenScale));
    __weak LegacyViewController *weakSelf = self;
    [[PHImageManager defaultManager] requestImageForAsset:asset
        targetSize:targetSize
        contentMode:PHImageContentModeAspectFill
        options:options
        resultHandler:^(UIImage *result, NSDictionary *info) {
            LegacyViewController *strongSelf = weakSelf;
            if (!strongSelf || !result || [[info objectForKey:PHImageCancelledKey] boolValue]) return;
            dispatch_async(dispatch_get_main_queue(), ^{
                LegacyViewController *mainSelf = weakSelf;
                if (!mainSelf) return;
                mainSelf->_photoStatusLabel.hidden = YES;
                [UIView transitionWithView:mainSelf->_photoImageView
                    duration:0.7 options:UIViewAnimationOptionTransitionCrossDissolve
                    animations:^{ mainSelf->_photoImageView.image = result; }
                    completion:nil];
            });
        }];
}

- (NSString *)nasaCacheDirectory {
    NSString *root = [NSSearchPathForDirectoriesInDomains(
        NSCachesDirectory, NSUserDomainMask, YES) firstObject];
    return [root stringByAppendingPathComponent:@"QuietPanelNASA"];
}

- (void)loadNASACache {
    NSString *directory = [self nasaCacheDirectory];
    NSData *metadataData = [NSData dataWithContentsOfFile:
        [directory stringByAppendingPathComponent:@"metadata.json"]];
    NSData *imageData = [NSData dataWithContentsOfFile:
        [directory stringByAppendingPathComponent:@"image.jpg"]];
    NSDictionary *metadata = metadataData
        ? [NSJSONSerialization JSONObjectWithData:metadataData options:0 error:nil] : nil;
    if ([metadata isKindOfClass:[NSDictionary class]] && imageData.length > 0) {
        [self displayNASA:metadata imageData:imageData cache:NO];
        _nasaStatusLabel.text = @"顯示上次快取 · 等待 Mac 檢查更新";
    }
}

- (void)displayNASA:(NSDictionary *)metadata imageData:(NSData *)imageData cache:(BOOL)cache {
    UIImage *image = [UIImage imageWithData:imageData];
    if (!image) return;
    _nasaImageView.image = image;
    NSString *title = [metadata objectForKey:@"title"];
    NSString *date = [metadata objectForKey:@"date"];
    NSString *copyright = [metadata objectForKey:@"copyright"];
    NSString *explanation = [metadata objectForKey:@"explanation"];
    NSString *mediaType = [metadata objectForKey:@"mediaType"];
    _nasaTitleLabel.text = [title isKindOfClass:[NSString class]] && title.length > 0
        ? title : @"NASA 每日天文圖片";
    if (![date isKindOfClass:[NSString class]]) date = @"";
    if (![copyright isKindOfClass:[NSString class]] || copyright.length == 0) copyright = @"NASA APOD";
    NSString *videoNote = [mediaType isEqual:@"video"] ? @" · 影片縮圖" : @"";
    _nasaMetaLabel.text = [NSString stringWithFormat:@"%@\n© %@%@", date, copyright, videoNote];
    _nasaExplanationView.text = [explanation isKindOfClass:[NSString class]]
        ? explanation : @"";
    _nasaStatusLabel.text = @"由 Mac 下載並透過 USB 傳送";
    if (!cache) return;

    NSMutableDictionary *stored = [metadata mutableCopy];
    [stored removeObjectForKey:@"type"];
    NSData *storedMetadata = [NSJSONSerialization dataWithJSONObject:stored options:0 error:nil];
    NSString *directory = [self nasaCacheDirectory];
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        [[NSFileManager defaultManager] createDirectoryAtPath:directory
            withIntermediateDirectories:YES attributes:nil error:nil];
        [storedMetadata writeToFile:[directory stringByAppendingPathComponent:@"metadata.json"]
                            atomically:YES];
        [imageData writeToFile:[directory stringByAppendingPathComponent:@"image.jpg"]
                       atomically:YES];
    });
}

- (void)applyPageConfiguration:(NSArray *)enabledPages {
    int requested[32];
    size_t count = 0;
    for (id value in enabledPages) {
        if (count == 32) break;
        if ([value isKindOfClass:[NSNumber class]]) requested[count++] = [value intValue];
    }
    uint8_t normalized[QuietPageCount];
    if (QuietNormalizePages(requested, count, normalized) == 0) return;
    memcpy(_pageEnabled, normalized, QuietPageCount);
    if (!_pageEnabled[_currentPage]) [self showPage:[self firstEnabledPage]];
    else [self updatePageIndicator];
}

- (NSInteger)firstEnabledPage {
    for (NSInteger page = 0; page < QuietPageCount; page++) {
        if (_pageEnabled[page]) return page;
    }
    return 0;
}

- (void)showNextPage {
    for (NSInteger page = _currentPage + 1; page < QuietPageCount; page++) {
        if (_pageEnabled[page]) { [self showPage:page]; return; }
    }
}

- (void)showPreviousPage {
    for (NSInteger page = _currentPage - 1; page >= 0; page--) {
        if (_pageEnabled[page]) { [self showPage:page]; return; }
    }
}

- (void)updatePageIndicator {
    NSArray *names = @[@"系統", @"延伸螢幕", @"相簿時鐘",
                       @"快捷工具", @"NASA"];
    NSMutableArray *dots = [NSMutableArray array];
    for (NSInteger page = 0; page < QuietPageCount; page++) {
        if (_pageEnabled[page]) [dots addObject:page == _currentPage ? @"●" : @"○"];
    }
    _pageIndicator.text = [NSString stringWithFormat:@"%@     %@",
        [dots componentsJoinedByString:@"   "], names[_currentPage]];
}

- (void)showPage:(NSInteger)page {
    if (page < 0 || page >= QuietPageCount || !_pageEnabled[page]) {
        page = [self firstEnabledPage];
    }
    BOOL wasPhotoPage = _currentPage == 2 || _currentPage == 3;
    _currentPage = page;
    _dashboardView.hidden = page != 0;
    _videoView.hidden = page != 1;
    _photoView.hidden = page != 2 && page != 3;
    _photoToolControls.hidden = page != 3;
    _photoClockPan.enabled = page == 2;
    _photoClockDrag.enabled = page == 2;
    _photoClockPinch.enabled = page == 2;
    _nasaView.hidden = page != 4;
    BOOL displayPage = page == 1;
    _cursorView.hidden = !displayPage;
    _statusLabel.hidden = !displayPage || _displayConnected;
    _dashboardStatusLabel.text = [NSString stringWithFormat:@"延伸螢幕：%@",
                                  _displayStatus ?: @"尚未啟動"];
    if (page == 2 || page == 3) {
        if (!wasPhotoPage) {
            [self applyPhotoClockSettings];
            [self startPhotoSlideshow];
        }
    }
    else {
        [_photoTimer invalidate];
        _photoTimer = nil;
    }
    if (page != 2) [self hidePhotoSettingsButton];
    [self updateClockTimerForCurrentPage];
    [self updatePageIndicator];
    [self.view bringSubviewToFront:_cursorView];
    [self.view bringSubviewToFront:_statusLabel];
    [self.view bringSubviewToFront:_pageIndicator];
    [_receiver setDisplayActive:displayPage page:page];
}

- (void)applicationDidBecomeActive:(NSNotification *)notification {
    (void)notification;
    [_receiver resumeAfterBackground];
}

- (void)applicationDidEnterBackground:(NSNotification *)notification {
    (void)notification;
    [_receiver suspendForBackground];
}

- (void)viewDidAppear:(BOOL)animated {
    [super viewDidAppear:animated];
    if (_receiver) return;

    UIScreen *screen = [UIScreen mainScreen];
    CGFloat scale = screen.scale;
    CGSize size = screen.bounds.size;
    NSInteger pixelsWide = (NSInteger)lrint(size.width * scale);
    NSInteger pixelsHigh = (NSInteger)lrint(size.height * scale);
    _displayPixelSize = CGSizeMake(pixelsWide, pixelsHigh);
    __weak LegacyViewController *weakSelf = self;
    _receiver = [[LegacyReceiver alloc]
        initWithDisplayLayer:_videoView.displayLayer
        cursorView:_cursorView
        pixelsWide:pixelsWide
        pixelsHigh:pixelsHigh
        scale:scale
        metricsHandler:^(NSDictionary *metrics) {
            LegacyViewController *strongSelf = weakSelf;
            if (strongSelf) [strongSelf applyMetrics:metrics];
        }
        pageConfigHandler:^(NSArray *enabledPages) {
            LegacyViewController *strongSelf = weakSelf;
            if (strongSelf) [strongSelf applyPageConfiguration:enabledPages];
        }
        nasaHandler:^(NSDictionary *metadata, NSData *imageData) {
            LegacyViewController *strongSelf = weakSelf;
            if (strongSelf) [strongSelf displayNASA:metadata imageData:imageData cache:YES];
        }
        weatherHandler:^(NSDictionary *weather) {
            LegacyViewController *strongSelf = weakSelf;
            if (strongSelf) [strongSelf applyWeather:weather];
        }
        actionResultHandler:^(NSString *message, BOOL ok) {
            LegacyViewController *strongSelf = weakSelf;
            if (!strongSelf) return;
            strongSelf->_photoToolStatusLabel.text = message;
            strongSelf->_photoToolStatusLabel.textColor = ok
                ? [UIColor colorWithRed:0.45 green:0.9 blue:0.65 alpha:1.0]
                : [UIColor colorWithRed:1.0 green:0.45 blue:0.35 alpha:1.0];
        }
        statusHandler:^(NSString *status, BOOL connected) {
            LegacyViewController *strongSelf = weakSelf;
            if (!strongSelf) return;
            strongSelf->_displayStatus = status;
            strongSelf->_displayConnected = connected;
            strongSelf->_statusLabel.text = status;
            strongSelf->_statusLabel.hidden = strongSelf->_currentPage != 1 || connected;
            strongSelf->_dashboardStatusLabel.text = [NSString
                stringWithFormat:@"延伸螢幕：%@", status];
        }];
    [_receiver start];
    [_receiver setDisplayActive:_currentPage == 1 page:_currentPage];
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
    [_clockTimer invalidate];
    [_photoTimer invalidate];
}

@end

@interface LegacyAppDelegate : UIResponder <UIApplicationDelegate>
@property (nonatomic, strong) UIWindow *window;
@end

@implementation LegacyAppDelegate
- (BOOL)application:(UIApplication *)application
        didFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
    (void)application;
    (void)launchOptions;
    self.window = [[UIWindow alloc] initWithFrame:[UIScreen mainScreen].bounds];
    self.window.rootViewController = [[LegacyViewController alloc] init];
    self.window.backgroundColor = [UIColor blackColor];
    [self.window makeKeyAndVisible];
    return YES;
}
@end

int main(int argc, char *argv[]) {
    @autoreleasepool {
        return UIApplicationMain(argc, argv, nil, NSStringFromClass([LegacyAppDelegate class]));
    }
}

#endif
