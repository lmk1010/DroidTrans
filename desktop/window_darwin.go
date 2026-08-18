//go:build darwin

package main

import (
	"runtime"
	"unsafe"
)

/*
#cgo CFLAGS: -x objective-c -fobjc-arc
#cgo LDFLAGS: -framework Cocoa -framework WebKit -framework UserNotifications
#include <stdlib.h>
#import <Cocoa/Cocoa.h>
#import <WebKit/WebKit.h>
#import <UserNotifications/UserNotifications.h>
#import <dispatch/dispatch.h>

static NSString *gURL;

@interface DTDragView : NSView
@end
@implementation DTDragView
- (void)mouseDown:(NSEvent *)event {
  [self.window performWindowDragWithEvent:event];
}
- (BOOL)acceptsFirstMouse:(NSEvent *)event { return YES; }
- (BOOL)mouseDownCanMoveWindow { return YES; }
- (BOOL)isOpaque { return NO; }
@end

@interface DTApp : NSObject <NSApplicationDelegate, UNUserNotificationCenterDelegate>
@property(strong) NSWindow *window;
@end

@implementation DTApp
- (void)applicationDidFinishLaunching:(NSNotification *)n {
  NSRect frame = NSMakeRect(0, 0, 1080, 700);
  NSUInteger style = NSWindowStyleMaskTitled | NSWindowStyleMaskClosable |
                     NSWindowStyleMaskMiniaturizable | NSWindowStyleMaskResizable |
                     NSWindowStyleMaskFullSizeContentView;
  self.window = [[NSWindow alloc] initWithContentRect:frame
                                            styleMask:style
                                              backing:NSBackingStoreBuffered
                                                defer:NO];
  self.window.title = @"DroidTrans";
  self.window.titlebarAppearsTransparent = YES;
  self.window.titleVisibility = NSWindowTitleHidden;
  self.window.opaque = NO;
  self.window.backgroundColor = [NSColor clearColor];
  self.window.movableByWindowBackground = YES;
  self.window.minSize = NSMakeSize(880, 600);
  [self.window center];

  NSVisualEffectView *fx = [[NSVisualEffectView alloc] initWithFrame:self.window.contentView.bounds];
  fx.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
  fx.material = NSVisualEffectMaterialUnderWindowBackground;
  fx.blendingMode = NSVisualEffectBlendingModeBehindWindow;
  fx.state = NSVisualEffectStateActive;
  self.window.contentView = fx;

  [self.window makeKeyAndOrderFront:nil];
  [NSApp activateIgnoringOtherApps:YES];

  WKWebViewConfiguration *cfg = [WKWebViewConfiguration new];
  WKWebView *web = [[WKWebView alloc] initWithFrame:fx.bounds configuration:cfg];
  web.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
  if (@available(macOS 12.0, *)) {
    web.underPageBackgroundColor = [NSColor clearColor];
  }
  [web setValue:@NO forKey:@"drawsBackground"];
  [fx addSubview:web];
  [web loadRequest:[NSURLRequest requestWithURL:[NSURL URLWithString:gURL]]];

  NSRect b = fx.bounds;
  DTDragView *drag = [[DTDragView alloc] initWithFrame:NSMakeRect(78, NSHeight(b) - 52, NSWidth(b) - 78, 52)];
  drag.autoresizingMask = NSViewWidthSizable | NSViewMinYMargin;
  drag.wantsLayer = YES;
  drag.layer.backgroundColor = [[NSColor clearColor] CGColor];
  [fx addSubview:drag positioned:NSWindowAbove relativeTo:web];

  dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(2.5 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
    UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
    center.delegate = self;
    [center requestAuthorizationWithOptions:(UNAuthorizationOptionAlert | UNAuthorizationOptionSound)
                          completionHandler:^(BOOL granted, NSError *error) {}];
  });
}
- (BOOL)applicationShouldTerminateAfterLastWindowClosed:(NSApplication *)app {
  return YES;
}
- (BOOL)applicationShouldHandleReopen:(NSApplication *)sender hasVisibleWindows:(BOOL)flag {
  if (self.window) {
    [self.window makeKeyAndOrderFront:nil];
  }
  [NSApp activateIgnoringOtherApps:YES];
  return YES;
}
- (void)userNotificationCenter:(UNUserNotificationCenter *)center
       willPresentNotification:(UNNotification *)notification
         withCompletionHandler:(void (^)(UNNotificationPresentationOptions options))completionHandler {
  completionHandler(UNNotificationPresentationOptionBanner | UNNotificationPresentationOptionSound);
}
@end

void DTRunWindow(const char *url) {
  @autoreleasepool {
    gURL = [NSString stringWithUTF8String:url];
    [NSApplication sharedApplication];
    [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
    [NSApp activateIgnoringOtherApps:YES];
    DTApp *del = [DTApp new];
    NSApp.delegate = del;
    [NSApp run];
  }
}

void DTRequestAttention(void) {
  dispatch_async(dispatch_get_main_queue(), ^{
    [NSApp requestUserAttention:NSInformationalRequest];
  });
}

void DTNotify(const char *title, const char *body) {
  NSString *t = [NSString stringWithUTF8String:title ? title : ""];
  NSString *b = [NSString stringWithUTF8String:body ? body : ""];
  dispatch_async(dispatch_get_main_queue(), ^{
    UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
    UNMutableNotificationContent *content = [UNMutableNotificationContent new];
    content.title = t;
    content.body = b;
    content.sound = [UNNotificationSound defaultSound];
    NSString *ident = [[NSUUID UUID] UUIDString];
    UNNotificationRequest *req = [UNNotificationRequest requestWithIdentifier:ident content:content trigger:nil];
    [center addNotificationRequest:req withCompletionHandler:nil];
  });
}
*/
import "C"

func runNativeWindow(url string) {
	runtime.LockOSThread()
	cs := C.CString(url)
	defer C.free(unsafe.Pointer(cs))
	C.DTRunWindow(cs)
}

func requestAttention() {
	C.DTRequestAttention()
}

func notifyUser(title, body string) {
	ct := C.CString(title)
	cb := C.CString(body)
	defer C.free(unsafe.Pointer(ct))
	defer C.free(unsafe.Pointer(cb))
	C.DTNotify(ct, cb)
}
