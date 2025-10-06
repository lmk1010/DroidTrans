#!/bin/bash
# WiFi模式API测试脚本

SERVER_URL="http://localhost:9500"

echo "================================"
echo "WiFi模式API测试"
echo "================================"
echo ""

# 1. 测试获取服务器信息
echo "1. 测试获取服务器信息..."
curl -s "${SERVER_URL}/api/wifi/info" | python3 -m json.tool
echo ""
echo ""

# 2. 测试上传照片列表
echo "2. 测试上传照片列表..."
curl -s -X POST "${SERVER_URL}/api/wifi/upload_photo_list" \
  -H "Content-Type: application/json" \
  -d '{
    "device_id": "test_device_001",
    "photos": [
      {
        "path": "/storage/emulated/0/DCIM/Camera/test_photo1.jpg",
        "name": "test_photo1.jpg",
        "size": 2048576,
        "size_mb": 1.95,
        "mtime": 1696142096,
        "date": "2023-10-01 12:34:56"
      },
      {
        "path": "/storage/emulated/0/DCIM/Camera/test_photo2.jpg",
        "name": "test_photo2.jpg",
        "size": 3145728,
        "size_mb": 3.0,
        "mtime": 1696228496,
        "date": "2023-10-02 23:45:67"
      }
    ]
  }' | python3 -m json.tool
echo ""
echo ""

# 3. 测试获取WiFi状态
echo "3. 测试获取WiFi状态..."
curl -s "${SERVER_URL}/api/wifi/status" | python3 -m json.tool
echo ""
echo ""

echo "================================"
echo "测试完成！"
echo "================================"
echo ""
echo "💡 提示："
echo "  - 如需测试文件上传，请手动创建测试图片并使用以下命令："
echo "    curl -X POST ${SERVER_URL}/api/wifi/upload_photo \\"
echo "      -F 'file=@/path/to/your/photo.jpg' \\"
echo "      -F 'relative_path=DCIM/Camera/photo.jpg'"
echo ""

