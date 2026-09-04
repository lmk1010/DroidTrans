// 开发辅助：打印本机许可证的存放位置和设备 ID。
// 装一份测试许可证做端到端验证时用，不参与发布。
package main

import (
	"fmt"

	"droidtrans/internal/license"
)

func main() {
	fmt.Println(license.Path(""))
	if d, err := license.DeviceID(""); err == nil {
		fmt.Println(d)
	}
}
