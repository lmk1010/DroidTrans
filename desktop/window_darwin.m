// 由 window_darwin.go 的 cgo 前导代码拆分而来：
// 同一个文件里既有 //export 又有 C 实现时，cgo 会把实现编译两遍导致重复符号。
#import "window_darwin.h"

#include <stdlib.h>
#import <Cocoa/Cocoa.h>
#import <WebKit/WebKit.h>
#import <UserNotifications/UserNotifications.h>
#import <dispatch/dispatch.h>

static NSString *gURL;

static BOOL dtCanNotify(void) {
  // 直接跑裸二进制（README 里的 ../dist/droidtrans）时没有 bundle，
  // 调 UNUserNotificationCenter 会抛 NSInternalInconsistencyException 把进程干掉。
  return [[NSBundle mainBundle] bundleIdentifier] != nil;
}


// Go 侧的回调：把用户拖进来/选中的文件路径交给 outbox
extern void dtFilesPicked(char *paths);

// DTDropView 铺在整个窗口上接住文件拖拽。
// 走原生拖拽是为了拿到真实路径：浏览器的 drag&drop 只能拿到文件内容，
// 几十 GB 的文件夹得先复制一份才能发，代价太大。
@interface DTDropView : NSView
@end
@implementation DTDropView
- (instancetype)initWithFrame:(NSRect)frame {
  self = [super initWithFrame:frame];
  if (self) {
    [self registerForDraggedTypes:@[NSPasteboardTypeFileURL]];
  }
  return self;
}
- (NSDragOperation)draggingEntered:(id<NSDraggingInfo>)sender { return NSDragOperationCopy; }
- (BOOL)prepareForDragOperation:(id<NSDraggingInfo>)sender { return YES; }
- (BOOL)performDragOperation:(id<NSDraggingInfo>)sender {
  NSArray<NSURL *> *urls = [sender.draggingPasteboard readObjectsForClasses:@[[NSURL class]]
                                                                   options:@{NSPasteboardURLReadingFileURLsOnlyKey: @YES}];
  if (urls.count == 0) {
    return NO;
  }
  NSMutableArray *paths = [NSMutableArray array];
  for (NSURL *u in urls) {
    if (u.path) [paths addObject:u.path];
  }
  NSString *joined = [paths componentsJoinedByString:@"\n"];
  dtFilesPicked((char *)[joined UTF8String]);
  return YES;
}
- (BOOL)mouseDownCanMoveWindow { return NO; }
- (NSView *)hitTest:(NSPoint)point { return nil; }   // 只接拖拽，不吃点击
@end

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

@interface DTApp : NSObject <NSApplicationDelegate, NSWindowDelegate, UNUserNotificationCenterDelegate>
@property(strong) NSWindow *window;
@property(strong) NSStatusItem *statusItem;
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
  self.window.delegate = self;
  [self.window center];

  NSVisualEffectView *fx = [[NSVisualEffectView alloc] initWithFrame:self.window.contentView.bounds];
  fx.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
  fx.material = NSVisualEffectMaterialUnderWindowBackground;
  fx.blendingMode = NSVisualEffectBlendingModeBehindWindow;
  fx.state = NSVisualEffectStateActive;
  self.window.contentView = fx;

  [self.window makeKeyAndOrderFront:nil];
  [NSApp activateIgnoringOtherApps:YES];
  [self setupStatusItem];

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

  DTDropView *drop = [[DTDropView alloc] initWithFrame:fx.bounds];
  drop.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
  [fx addSubview:drop positioned:NSWindowAbove relativeTo:nil];

  dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(2.5 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
    if (!dtCanNotify()) {
      return;
    }
    UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
    center.delegate = self;
    [center requestAuthorizationWithOptions:(UNAuthorizationOptionAlert | UNAuthorizationOptionSound)
                          completionHandler:^(BOOL granted, NSError *error) {}];
  });
}
- (void)setupStatusItem {
  self.statusItem = [[NSStatusBar systemStatusBar] statusItemWithLength:NSSquareStatusItemLength];
  NSImage *img = [[NSApp applicationIconImage] copy];
  img.size = NSMakeSize(18, 18);
  self.statusItem.button.image = img;
  self.statusItem.button.toolTip = @"DroidTrans";
  NSMenu *menu = [NSMenu new];
  NSMenuItem *open = [[NSMenuItem alloc] initWithTitle:@"打开窗口" action:@selector(showMainWindow) keyEquivalent:@""];
  open.target = self;
  [menu addItem:open];
  [menu addItem:[NSMenuItem separatorItem]];
  NSMenuItem *quit = [[NSMenuItem alloc] initWithTitle:@"退出卓传" action:@selector(quitApp) keyEquivalent:@"q"];
  quit.target = self;
  [menu addItem:quit];
  self.statusItem.menu = menu;
}
- (void)showMainWindow {
  [self.window makeKeyAndOrderFront:nil];
  [NSApp activateIgnoringOtherApps:YES];
}
- (void)quitApp {
  [NSApp terminate:nil];
}
- (BOOL)windowShouldClose:(NSWindow *)sender {
  [sender orderOut:nil];
  static BOOL told = NO;
  if (!told && dtCanNotify()) {
    told = YES;
    UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
    UNMutableNotificationContent *content = [UNMutableNotificationContent new];
    content.title = @"DroidTrans 仍在接收";
    content.body = @"窗口关了也会继续收文件。点菜单栏图标可再打开，退出请选「退出卓传」。";
    content.sound = [UNNotificationSound defaultSound];
    UNNotificationRequest *req = [UNNotificationRequest requestWithIdentifier:@"dt-bg" content:content trigger:nil];
    [center addNotificationRequest:req withCompletionHandler:nil];
  }
  return NO;
}
- (BOOL)applicationShouldTerminateAfterLastWindowClosed:(NSApplication *)app {
  return NO;
}
- (BOOL)applicationShouldHandleReopen:(NSApplication *)sender hasVisibleWindows:(BOOL)flag {
  [self showMainWindow];
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

void DTPickFiles(void) {
  dispatch_async(dispatch_get_main_queue(), ^{
    NSOpenPanel *panel = [NSOpenPanel openPanel];
    panel.canChooseFiles = YES;
    panel.canChooseDirectories = YES;
    panel.allowsMultipleSelection = YES;
    panel.message = @"选择要发到手机的文件或文件夹";
    panel.prompt = @"发到手机";
    if ([panel runModal] != NSModalResponseOK) {
      return;
    }
    NSMutableArray *paths = [NSMutableArray array];
    for (NSURL *u in panel.URLs) {
      if (u.path) [paths addObject:u.path];
    }
    if (paths.count == 0) {
      return;
    }
    NSString *joined = [paths componentsJoinedByString:@"\n"];
    dtFilesPicked((char *)[joined UTF8String]);
  });
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
    if (!dtCanNotify()) {
      return;
    }
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
