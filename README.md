# khw_hub — ESP32-S3-CAM 智能产品平台

本地相机应用:配合 **ESP32-S3-CAM 摄像头**(GC2145,ESP32-S3-EYE 风格引脚),在同一 WiFi 下即可查看实时画面、截图、录像。无账号、无云服务,全部在局域网内完成。

本仓库含 M1 完整交付:**固件**(配网 + 发现 + 实时流 + 设置持久化)+ **Android App**(Jetpack Compose 多模块,以"卡槽插件"架构为后续产品留位)+ 使用/测试文档。

## 目录结构

```
├── esp32-s3-cam/         固件(Arduino / PlatformIO 可直接打开)
│   ├── PLAN.md           完整设计(架构、契约、里程碑、风险)
│   └── firmware/
│       └── board-camera/ 摄像头固件 board-camera.ino
└── khw_lab/              Android App 工程(AGP 9 + Kotlin + Compose)
    ├── :core             契约层:Device / CardPlugin / CardRegistry / DeviceClient / 发现
    ├── :feature-camera   摄像头卡片实现(Tile / Detail / Settings),唯一产品插件
    ├── :app              宿主:配网流程 + mDNS/UDP 发现 + 卡片容器 + 视频渲染
    ├── USAGE.md          使用文档(面向最终用户)
    └── TESTING.md        测试文档(T01–T12 硬件验收,全部通过)
```

## 功能(M1)

- **配网**:设备无凭据时开热点 `ESP32-CAM-Setup`,App 下发家庭 WiFi 凭据后设备自动入网;开机长按 BOOT 约 4 秒可清除凭据重新配网。
- **发现**:mDNS(`_esp32cam._tcp`)为主,UDP 3333 兜底,双通道。
- **实时画面**:MJPEG 流(`/stream`),App 单连接共享流驱动预览 + 录像。
- **截图 / 录像**:存相册 `Pictures/khw_hub` / `Movies/khw_hub`(MP4,无音频——板子无麦克风)。
- **设置**:分辨率(320×240 ~ 1600×1200)、画质、垂直翻转/水平镜像,即存即生效,断电不丢。
- **重命名 / 删除设备**,删除后可重新配网找回。

## 工具链

- 固件:Arduino IDE(ESP32-S3 Dev Module,16MB flash,OPI PSRAM,USB CDC Off,115200)。
- App:AGP 9.1.1 + Gradle 9.3.1 + compileSdk 36 + JDK 21;AGP 9 内置 Kotlin 2.2.10(勿 apply `kotlin-android`)。构建:`cd khw_lab && ./gradlew.bat --no-daemon :app:assembleDebug`。
- 固件语法校验:`esp32-s3-cam/firmware/board-camera/_check_syntax.sh`。

## 验收状态

- **M1 里程碑已完成**(2026-09-06):固件 **0.2.6**(含长按 BOOT 清凭据修复 + `/unpair` 失配端点),App 端到端真机验收 **T01–T12 全部通过**,详见 `khw_lab/TESTING.md`。
- **安全提示**:M1 为局域网明文、无鉴权(未加访问令牌),请只在可信网络使用;M2 计划加入共享令牌鉴权。

## 许可

© 2026 Sumonge。保留所有权利。
