// 原生窗口层对 Go 暴露的接口
#ifndef DROIDTRANS_WINDOW_DARWIN_H
#define DROIDTRANS_WINDOW_DARWIN_H

void DTRunWindow(const char *url);
void DTRequestAttention(void);
void DTNotify(const char *title, const char *body);
void DTPickFiles(void);

// 由 Go 侧实现（//export dtFilesPicked）
void dtFilesPicked(char *paths);

#endif
