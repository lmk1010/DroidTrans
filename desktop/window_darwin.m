// 由 window_darwin.go 的 cgo 前导代码拆分而来：
// 同一个文件里既有 //export 又有 C 实现时，cgo 会把实现编译两遍导致重复符号。
#import "window_darwin.h"
#include "menubar_icon.h"

#include <stdlib.h>
#import <Cocoa/Cocoa.h>
#import <WebKit/WebKit.h>
#import <UserNotifications/UserNotifications.h>

// 由 Go 侧实现（//export），菜单每次弹出时问一遍当前状态。
// 返回的是 Go 分配的 C 字符串，用完要 free —— 下面两个包装函数负责这件事。
extern char *dtStatusLine(void);
extern char *dtAddressLine(void);
extern void dtPickFilesFromMenu(void);
extern void dtOpenOutputFolder(void);

static NSString *DTTakeGoString(char *s) {
  if (s == NULL) return @"";
  NSString *out = [NSString stringWithUTF8String:s];
  free(s);
  return out ?: @"";
}
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

@interface DTApp : NSObject <NSApplicationDelegate, NSWindowDelegate, UNUserNotificationCenterDelegate, NSMenuDelegate>
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
  self.statusItem = [[NSStatusBar systemStatusBar] statusItemWithLength:NSVariableStatusItemLength];

  // 菜单栏图标要用模板图：系统按深浅色自动着色，和旁边的系统图标一个调子。
  //
  // 之前直接拿 App 图标缩到 18×18 —— 那张图是满幅圆角方块，
  // 缩完四周还剩一圈留白，视觉重量比邻居轻一截，看着就是「小」。
  // 后来换成 SF Symbol 的 arrow.triangle.2.circlepath，大小对了，
  // 但那是系统的通用刷新符号，不是我们的 logo。
  //
  // 现在用 logo 里的双箭头本身（menubar_icon.h，已去掉圆角方形底并裁掉留白），
  // 形状是自己的，重量和邻居一致。
  NSImage *img = nil;
  NSData *iconData =
      [[NSData alloc] initWithBase64EncodedString:@(kMenuBarIconPNGBase64)
                                          options:0];
  if (iconData != nil) {
    img = [[NSImage alloc] initWithData:iconData];
    // 位图是 36px，按 18pt 摆，retina 上正好 1:1。
    img.size = NSMakeSize(18, 18);
  }
  if (img == nil) {
    // 内嵌数据坏了才会走到这里，退回系统符号总比没有图标强。
    if (@available(macOS 11.0, *)) {
      img = [NSImage imageWithSystemSymbolName:@"arrow.triangle.2.circlepath"
                      accessibilityDescription:@"DroidTrans"];
      NSImageSymbolConfiguration *cfg =
          [NSImageSymbolConfiguration configurationWithPointSize:16
                                                          weight:NSFontWeightSemibold
                                                           scale:NSImageSymbolScaleLarge];
      img = [img imageWithSymbolConfiguration:cfg];
    } else {
      img = [[NSApp applicationIconImage] copy];
      img.size = NSMakeSize(18, 18);
    }
  }
  img.accessibilityDescription = @"DroidTrans";
  img.template = YES;
  self.statusItem.button.image = img;
  self.statusItem.button.imagePosition = NSImageLeft;
  self.statusItem.button.toolTip = @"DroidTrans 卓传";

  NSMenu *menu = [NSMenu new];
  menu.delegate = self;   // 每次打开前刷新状态，见 menuNeedsUpdate:
  self.statusItem.menu = menu;
  [self rebuildMenu];
}

/// 菜单每次弹出前重建。
///
/// 状态（连了几台手机、有多少件待取）是会变的，
/// 建一次就不管的话，用户看到的是打开 App 那一刻的快照。
- (void)menuNeedsUpdate:(NSMenu *)menu {
  [self rebuildMenu];
}

- (void)rebuildMenu {
  NSMenu *menu = self.statusItem.menu;
  [menu removeAllItems];

  // 顶部一行只读状态。让人瞟一眼菜单栏就知道现在通不通，
  // 不用为了确认「手机连上没有」去把窗口翻出来。
  NSString *summary = DTTakeGoString(dtStatusLine());
  NSMenuItem *status = [[NSMenuItem alloc] initWithTitle:(summary ?: @"") action:nil keyEquivalent:@""];
  status.enabled = NO;
  [menu addItem:status];

  NSString *addr = DTTakeGoString(dtAddressLine());
  if (addr.length > 0) {
    NSMenuItem *addrItem = [[NSMenuItem alloc] initWithTitle:addr action:@selector(copyAddress) keyEquivalent:@""];
    addrItem.target = self;
    addrItem.toolTip = @"点一下复制地址";
    [menu addItem:addrItem];
  }

  [menu addItem:[NSMenuItem separatorItem]];

  NSMenuItem *open = [[NSMenuItem alloc] initWithTitle:@"打开卓传" action:@selector(showMainWindow) keyEquivalent:@"o"];
  open.target = self;
  [menu addItem:open];

  NSMenuItem *send = [[NSMenuItem alloc] initWithTitle:@"发文件到手机…" action:@selector(pickFilesFromMenu) keyEquivalent:@"f"];
  send.target = self;
  [menu addItem:send];

  NSMenuItem *folder = [[NSMenuItem alloc] initWithTitle:@"打开保存位置" action:@selector(openOutputFolder) keyEquivalent:@""];
  folder.target = self;
  [menu addItem:folder];

  [menu addItem:[NSMenuItem separatorItem]];

  NSMenuItem *quit = [[NSMenuItem alloc] initWithTitle:@"退出卓传" action:@selector(quitApp) keyEquivalent:@"q"];
  quit.target = self;
  [menu addItem:quit];
}

- (void)copyAddress {
  NSString *addr = DTTakeGoString(dtAddressLine());
  if (addr.length == 0) return;
  NSPasteboard *pb = [NSPasteboard generalPasteboard];
  [pb clearContents];
  [pb setString:addr forType:NSPasteboardTypeString];
}

- (void)pickFilesFromMenu {
  dtPickFilesFromMenu();
}

- (void)openOutputFolder {
  dtOpenOutputFolder();
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
