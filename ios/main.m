#include <stdint.h>
#include <stddef.h>

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
    puts("protocol parser: ok");
    return 0;
}

#else

#import <UIKit/UIKit.h>
#import <AVFoundation/AVFoundation.h>
#import <CoreMedia/CoreMedia.h>
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

@interface LegacyReceiver : NSObject
- (instancetype)initWithDisplayLayer:(AVSampleBufferDisplayLayer *)displayLayer
                          cursorView:(UIImageView *)cursorView
                          pixelsWide:(NSInteger)pixelsWide
                          pixelsHigh:(NSInteger)pixelsHigh
                               scale:(CGFloat)scale
                      metricsHandler:(void (^)(NSDictionary *metrics))metricsHandler
                       statusHandler:(void (^)(NSString *status, BOOL connected))statusHandler;
- (void)start;
- (void)setDisplayActive:(BOOL)active;
@end

@implementation LegacyReceiver {
    AVSampleBufferDisplayLayer *_displayLayer;
    UIImageView *_cursorView;
    NSInteger _pixelsWide;
    NSInteger _pixelsHigh;
    CGFloat _scale;
    void (^_metricsHandler)(NSDictionary *);
    void (^_statusHandler)(NSString *, BOOL);
    dispatch_queue_t _queue;
    int _serverFD;
    int _clientFD;
    BOOL _started;
    NSData *_sps;
    NSData *_pps;
    CMVideoFormatDescriptionRef _formatDescription;
    CFTimeInterval _lastKeyframeRequest;
    BOOL _displayActive;
    BOOL _decoderResetPending;
}

- (instancetype)initWithDisplayLayer:(AVSampleBufferDisplayLayer *)displayLayer
                          cursorView:(UIImageView *)cursorView
                          pixelsWide:(NSInteger)pixelsWide
                          pixelsHigh:(NSInteger)pixelsHigh
                               scale:(CGFloat)scale
                      metricsHandler:(void (^)(NSDictionary *))metricsHandler
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
        _statusHandler = [statusHandler copy];
        _queue = dispatch_queue_create("tw.codex.quietpanel.receiver", DISPATCH_QUEUE_SERIAL);
        _serverFD = -1;
        _clientFD = -1;
        _displayActive = NO;
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

- (void)setDisplayActive:(BOOL)active {
    int socketFD;
    @synchronized (self) {
        if (_displayActive == active) return;
        _displayActive = active;
        _decoderResetPending = YES;
        socketFD = _clientFD;
    }
    dispatch_async(dispatch_get_main_queue(), ^{
        self->_cursorView.hidden = YES;
    });
    if (socketFD >= 0) {
        [self sendJSON:@{ @"type": @"visible", @"v": @(active) }
                socket:socketFD];
        if (active) [self requestKeyframe];
    }
}

- (void)runServer {
    _serverFD = socket(AF_INET, SOCK_STREAM, 0);
    if (_serverFD < 0) {
        [self setStatus:@"Socket 建立失敗" connected:NO];
        return;
    }

    int yes = 1;
    setsockopt(_serverFD, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
    struct sockaddr_in address;
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_ANY);
    address.sin_port = htons(kLegacyPort);

    if (bind(_serverFD, (struct sockaddr *)&address, sizeof(address)) != 0 ||
        listen(_serverFD, 2) != 0) {
        [self setStatus:[NSString stringWithFormat:@"無法監聽 %u", kLegacyPort] connected:NO];
        close(_serverFD);
        _serverFD = -1;
        return;
    }

    [self setStatus:[NSString stringWithFormat:@"等待 Mac（連接埠 %u）", kLegacyPort]
            connected:NO];

    for (;;) {
        int socketFD = accept(_serverFD, NULL, NULL);
        if (socketFD < 0) {
            if (errno == EINTR) continue;
            break;
        }
        @synchronized (self) {
            _clientFD = socketFD;
        }
        setsockopt(socketFD, IPPROTO_TCP, TCP_NODELAY, &yes, sizeof(yes));
        [self resetDecoder];
        [self setStatus:@"已連接 Mac" connected:YES];
        if ([self sendHello:socketFD]) {
            BOOL displayActive;
            @synchronized (self) {
                displayActive = _displayActive;
            }
            [self sendJSON:@{ @"type": @"visible", @"v": @(displayActive) }
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
    NSMutableArray *videoNALUs = [NSMutableArray arrayWithCapacity:count];
    BOOL isKeyframe = NO;

    for (size_t i = 0; i < count; i++) {
        LegacyNALRange range = ranges[i];
        NSData *nalu = [NSData dataWithBytes:bytes + range.offset length:range.length];
        if (nalu.length == 0) continue;
        uint8_t type = ((const uint8_t *)nalu.bytes)[0] & 0x1f;
        if (type == 7) {
            if (![_sps isEqualToData:nalu]) {
                _sps = nalu;
                if (_formatDescription) {
                    CFRelease(_formatDescription);
                    _formatDescription = NULL;
                }
            }
        } else if (type == 8) {
            if (![_pps isEqualToData:nalu]) {
                _pps = nalu;
                if (_formatDescription) {
                    CFRelease(_formatDescription);
                    _formatDescription = NULL;
                }
            }
        } else if (type == 1 || type == 5) {
            [videoNALUs addObject:nalu];
            if (type == 5) isKeyframe = YES;
        }
    }

    if (!_formatDescription && _sps && _pps) [self buildFormatDescription];
    if (_formatDescription && videoNALUs.count > 0) {
        [self enqueueNALUs:videoNALUs keyframe:isKeyframe];
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

- (void)enqueueNALUs:(NSArray *)nalus keyframe:(BOOL)isKeyframe {
    NSMutableData *avcc = [NSMutableData data];
    for (NSData *nalu in nalus) {
        uint32_t length = htonl((uint32_t)nalu.length);
        [avcc appendBytes:&length length:sizeof(length)];
        [avcc appendData:nalu];
    }

    CMBlockBufferRef blockBuffer = NULL;
    OSStatus status = CMBlockBufferCreateWithMemoryBlock(
        kCFAllocatorDefault, NULL, avcc.length, kCFAllocatorDefault, NULL,
        0, avcc.length, 0, &blockBuffer);
    if (status != kCMBlockBufferNoErr || !blockBuffer) return;

    status = CMBlockBufferReplaceDataBytes(avcc.bytes, blockBuffer, 0, avcc.length);
    if (status != kCMBlockBufferNoErr) {
        CFRelease(blockBuffer);
        return;
    }

    CMSampleBufferRef sampleBuffer = NULL;
    size_t sampleSize = avcc.length;
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

@interface LegacyViewController : UIViewController
@end

@implementation LegacyViewController {
    UIView *_dashboardView;
    LegacyVideoView *_videoView;
    UILabel *_statusLabel;
    UILabel *_dashboardStatusLabel;
    UILabel *_clockLabel;
    UILabel *_dateLabel;
    UILabel *_cpuValueLabel;
    UILabel *_memoryValueLabel;
    UILabel *_networkValueLabel;
    UILabel *_diskValueLabel;
    UILabel *_pageIndicator;
    UIImageView *_cursorView;
    LegacyReceiver *_receiver;
    NSDateFormatter *_timeFormatter;
    NSDateFormatter *_dateFormatter;
    NSTimer *_clockTimer;
    NSInteger _currentPage;
    BOOL _displayConnected;
    NSString *_displayStatus;
}

- (BOOL)prefersStatusBarHidden { return YES; }
- (BOOL)shouldAutorotate { return YES; }
- (UIInterfaceOrientationMask)supportedInterfaceOrientations {
    return UIInterfaceOrientationMaskLandscape;
}

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = [UIColor blackColor];

    [self buildDashboard];

    _videoView = [[LegacyVideoView alloc] initWithFrame:self.view.bounds];
    _videoView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    [self.view addSubview:_videoView];

    _cursorView = [[UIImageView alloc] initWithFrame:CGRectZero];
    _cursorView.contentMode = UIViewContentModeScaleAspectFit;
    _cursorView.hidden = YES;
    [self.view addSubview:_cursorView];

    _statusLabel = [[UILabel alloc] initWithFrame:CGRectMake(20, 20, self.view.bounds.size.width - 40, 44)];
    _statusLabel.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleTopMargin;
    _statusLabel.center = CGPointMake(CGRectGetMidX(self.view.bounds), CGRectGetMaxY(self.view.bounds) - 44);
    _statusLabel.textColor = [UIColor whiteColor];
    _statusLabel.textAlignment = NSTextAlignmentCenter;
    _statusLabel.font = [UIFont systemFontOfSize:15.0];
    _statusLabel.text = @"正在啟動";
    [self.view addSubview:_statusLabel];

    _pageIndicator = [[UILabel alloc] initWithFrame:CGRectMake(0, 0, 180, 28)];
    _pageIndicator.autoresizingMask = UIViewAutoresizingFlexibleLeftMargin |
                                     UIViewAutoresizingFlexibleRightMargin |
                                     UIViewAutoresizingFlexibleTopMargin;
    _pageIndicator.center = CGPointMake(CGRectGetMidX(self.view.bounds),
                                        CGRectGetMaxY(self.view.bounds) - 18);
    _pageIndicator.backgroundColor = [UIColor colorWithWhite:0 alpha:0.42];
    _pageIndicator.textColor = [UIColor whiteColor];
    _pageIndicator.textAlignment = NSTextAlignmentCenter;
    _pageIndicator.font = [UIFont systemFontOfSize:13.0];
    _pageIndicator.layer.cornerRadius = 9.0;
    _pageIndicator.layer.masksToBounds = YES;
    [self.view addSubview:_pageIndicator];

    UISwipeGestureRecognizer *left = [[UISwipeGestureRecognizer alloc]
        initWithTarget:self action:@selector(showDisplayPage)];
    left.direction = UISwipeGestureRecognizerDirectionLeft;
    [self.view addGestureRecognizer:left];
    UISwipeGestureRecognizer *right = [[UISwipeGestureRecognizer alloc]
        initWithTarget:self action:@selector(showDashboardPage)];
    right.direction = UISwipeGestureRecognizerDirectionRight;
    [self.view addGestureRecognizer:right];

    _displayStatus = @"正在啟動";
    [self showPage:0];

    [UIApplication sharedApplication].idleTimerDisabled = YES;
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

    UILabel *title = [[UILabel alloc] initWithFrame:CGRectMake(32, 24, 420, 44)];
    title.text = @"QUIETPANEL";
    title.textColor = [UIColor colorWithRed:0.35 green:0.85 blue:1.0 alpha:1.0];
    title.font = [UIFont boldSystemFontOfSize:26.0];
    [_dashboardView addSubview:title];

    UILabel *version = [[UILabel alloc] initWithFrame:CGRectMake(32, 58, 420, 26)];
    version.text = @"Mac 系統監控 + 延伸螢幕 · 0.1.1";
    version.textColor = [UIColor colorWithWhite:0.55 alpha:1.0];
    version.font = [UIFont systemFontOfSize:13.0];
    [_dashboardView addSubview:version];

    _clockLabel = [[UILabel alloc] initWithFrame:CGRectMake(
        self.view.bounds.size.width - 390, 18, 358, 52)];
    _clockLabel.autoresizingMask = UIViewAutoresizingFlexibleLeftMargin;
    _clockLabel.textAlignment = NSTextAlignmentRight;
    _clockLabel.textColor = [UIColor whiteColor];
    _clockLabel.font = [UIFont systemFontOfSize:40.0 weight:UIFontWeightLight];
    [_dashboardView addSubview:_clockLabel];

    _dateLabel = [[UILabel alloc] initWithFrame:CGRectMake(
        self.view.bounds.size.width - 390, 66, 358, 24)];
    _dateLabel.autoresizingMask = UIViewAutoresizingFlexibleLeftMargin;
    _dateLabel.textAlignment = NSTextAlignmentRight;
    _dateLabel.textColor = [UIColor colorWithWhite:0.72 alpha:1.0];
    _dateLabel.font = [UIFont systemFontOfSize:15.0];
    [_dashboardView addSubview:_dateLabel];

    CGFloat margin = 32.0;
    CGFloat gap = 16.0;
    CGFloat cardWidth = (self.view.bounds.size.width - margin * 2.0 - gap) / 2.0;
    _cpuValueLabel = [self addMetricCard:@"CPU" frame:CGRectMake(
        margin, 126, cardWidth, 142)];
    _memoryValueLabel = [self addMetricCard:@"MEMORY" frame:CGRectMake(
        margin + cardWidth + gap, 126, cardWidth, 142)];
    _networkValueLabel = [self addMetricCard:@"NETWORK" frame:CGRectMake(
        margin, 284, cardWidth, 142)];
    _networkValueLabel.font = [UIFont systemFontOfSize:25.0 weight:UIFontWeightLight];
    _diskValueLabel = [self addMetricCard:@"SYSTEM DISK" frame:CGRectMake(
        margin + cardWidth + gap, 284, cardWidth, 142)];

    _dashboardStatusLabel = [[UILabel alloc] initWithFrame:CGRectMake(120, 458,
        self.view.bounds.size.width - 240, 68)];
    _dashboardStatusLabel.autoresizingMask = UIViewAutoresizingFlexibleWidth;
    _dashboardStatusLabel.backgroundColor = [UIColor colorWithWhite:1.0 alpha:0.06];
    _dashboardStatusLabel.textColor = [UIColor colorWithWhite:0.8 alpha:1.0];
    _dashboardStatusLabel.textAlignment = NSTextAlignmentCenter;
    _dashboardStatusLabel.font = [UIFont systemFontOfSize:16.0];
    _dashboardStatusLabel.layer.cornerRadius = 12.0;
    _dashboardStatusLabel.layer.masksToBounds = YES;
    [_dashboardView addSubview:_dashboardStatusLabel];

    UILabel *hint = [[UILabel alloc] initWithFrame:CGRectMake(32, 544,
        self.view.bounds.size.width - 64, 36)];
    hint.autoresizingMask = UIViewAutoresizingFlexibleWidth;
    hint.text = @"向左滑動進入延伸螢幕";
    hint.textAlignment = NSTextAlignmentCenter;
    hint.textColor = [UIColor colorWithWhite:0.45 alpha:1.0];
    hint.font = [UIFont systemFontOfSize:15.0];
    [_dashboardView addSubview:hint];

    _timeFormatter = [[NSDateFormatter alloc] init];
    _timeFormatter.dateFormat = @"HH:mm:ss";
    _dateFormatter = [[NSDateFormatter alloc] init];
    _dateFormatter.dateFormat = @"yyyy年 M月 d日 EEEE";
    [self updateClock];
    _clockTimer = [NSTimer scheduledTimerWithTimeInterval:1.0
        target:self selector:@selector(updateClock) userInfo:nil repeats:YES];
}

- (UILabel *)addMetricCard:(NSString *)title frame:(CGRect)frame {
    UIView *card = [[UIView alloc] initWithFrame:frame];
    card.backgroundColor = [UIColor colorWithWhite:1.0 alpha:0.055];
    card.layer.cornerRadius = 12.0;
    [_dashboardView addSubview:card];

    UILabel *titleLabel = [[UILabel alloc] initWithFrame:CGRectMake(18, 12,
        frame.size.width - 36, 26)];
    titleLabel.text = title;
    titleLabel.textColor = [UIColor colorWithWhite:0.48 alpha:1.0];
    titleLabel.font = [UIFont boldSystemFontOfSize:14.0];
    [card addSubview:titleLabel];

    UILabel *value = [[UILabel alloc] initWithFrame:CGRectMake(18, 40,
        frame.size.width - 36, 88)];
    value.text = @"--";
    value.textColor = [UIColor whiteColor];
    value.font = [UIFont systemFontOfSize:44.0 weight:UIFontWeightLight];
    value.numberOfLines = 2;
    [card addSubview:value];
    return value;
}

- (void)updateClock {
    NSDate *now = [NSDate date];
    _clockLabel.text = [_timeFormatter stringFromDate:now];
    _dateLabel.text = [_dateFormatter stringFromDate:now];
}

- (NSString *)rateText:(NSNumber *)number {
    if (![number isKindOfClass:[NSNumber class]]) return @"--";
    double bytes = MAX(0.0, [number doubleValue]);
    if (bytes >= 1000000.0) return [NSString stringWithFormat:@"%.1f MB/s", bytes / 1000000.0];
    if (bytes >= 1000.0) return [NSString stringWithFormat:@"%.0f KB/s", bytes / 1000.0];
    return [NSString stringWithFormat:@"%.0f B/s", bytes];
}

- (void)applyMetrics:(NSDictionary *)metrics {
    NSNumber *cpu = [metrics objectForKey:@"cpu"];
    NSNumber *memory = [metrics objectForKey:@"memory"];
    NSNumber *disk = [metrics objectForKey:@"disk"];
    if ([cpu isKindOfClass:[NSNumber class]]) {
        _cpuValueLabel.text = [NSString stringWithFormat:@"%.0f %%",
            MIN(100.0, MAX(0.0, [cpu doubleValue]))];
    }
    if ([memory isKindOfClass:[NSNumber class]]) {
        _memoryValueLabel.text = [NSString stringWithFormat:@"%.0f %%",
            MIN(100.0, MAX(0.0, [memory doubleValue]))];
    }
    if ([disk isKindOfClass:[NSNumber class]]) {
        _diskValueLabel.text = [NSString stringWithFormat:@"%.0f %% used",
            MIN(100.0, MAX(0.0, [disk doubleValue]))];
    }
    _networkValueLabel.text = [NSString stringWithFormat:@"↓ %@\n↑ %@",
        [self rateText:[metrics objectForKey:@"downBps"]],
        [self rateText:[metrics objectForKey:@"upBps"]]];
}

- (void)showDashboardPage {
    [self showPage:0];
}

- (void)showDisplayPage {
    [self showPage:1];
}

- (void)showPage:(NSInteger)page {
    _currentPage = page == 1 ? 1 : 0;
    BOOL displayPage = _currentPage == 1;
    _dashboardView.hidden = displayPage;
    _videoView.hidden = !displayPage;
    _cursorView.hidden = !displayPage;
    _statusLabel.hidden = !displayPage || _displayConnected;
    _pageIndicator.text = displayPage ? @"○   ●  延伸螢幕" : @"●   ○  面板";
    _dashboardStatusLabel.text = [NSString stringWithFormat:@"延伸螢幕：%@",
                                  _displayStatus ?: @"尚未啟動"];
    [_receiver setDisplayActive:displayPage];
}

- (void)viewDidAppear:(BOOL)animated {
    [super viewDidAppear:animated];
    if (_receiver) return;

    UIScreen *screen = [UIScreen mainScreen];
    CGFloat scale = screen.scale;
    CGSize size = screen.bounds.size;
    NSInteger pixelsWide = (NSInteger)lrint(size.width * scale);
    NSInteger pixelsHigh = (NSInteger)lrint(size.height * scale);
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
    [_receiver setDisplayActive:_currentPage == 1];
}

- (void)dealloc {
    [_clockTimer invalidate];
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
