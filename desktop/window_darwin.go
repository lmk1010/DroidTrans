//go:build darwin

package main

import (
	"runtime"
	"unsafe"
)

/*
#cgo CFLAGS: -x objective-c -fobjc-arc
#cgo LDFLAGS: -framework Cocoa -framework WebKit
#include <stdlib.h>
#import <Cocoa/Cocoa.h>
#import <WebKit/WebKit.h>
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

@interface DTApp : NSObject <NSApplicationDelegate>
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

  [self.window makeKeyAndOrderFront:nil];
  [NSApp activateIgnoringOtherApps:YES];
}
- (BOOL)applicationShouldTerminateAfterLastWindowClosed:(NSApplication *)app {
  return YES;
}
@end

void DTRunWindow(const char *url) {
  @autoreleasepool {
    gURL = [NSString stringWithUTF8String:url];
    [NSApplication sharedApplication];
    [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
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
