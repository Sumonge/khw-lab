# khw_hub — ESP32-S3-CAM 智能产品平台 App(M1)

配套固件在 `../esp32-s3-cam/firmware/board-camera/`。本 App 负责发现、连接、查看 ESP32-S3-CAM,并以"卡槽插件"架构为后续产品(运动/灯源/声量)留位。完整设计见 `../esp32-s3-cam/PLAN.md`。

## 结构

```
:core            契约层:Device / CardPlugin / CardRegistry / DeviceClient / 发现 / MJPEG
:feature-camera  摄像头卡片实现(Tile / Detail / Settings),唯一的产品插件
:app             宿主:配网流程 + mDNS 发现 + 卡片容器 + 视频渲染 + 注册表
```

- 包名 `com.khwlab.hub`(从模板占位的 `com.example.khw_lab` 改名)。
- 以后每加一个产品 = 加一个 `:feature-xxx` 兄弟模块,实现同一个 `CardPlugin`。

## 工具链(重要)

- AGP **9.1.1** + Gradle **9.3.1** + compileSdk **36**,JDK 21。
- AGP 9 **内置 Kotlin**(版本 **2.2.10**,与 `kotlin-gradle-plugin` 对齐),**不要**再 apply `org.jetbrains.kotlin.android`。
- Jetpack Compose 通过 `org.jetbrains.kotlin.plugin.compose`(版本 2.2.10)启用,见 `gradle/libs.versions.toml` 的 `compose-compiler`。

## 构建

1. 用 Android Studio 打开本目录(`khw_lab/`)。
2. 等 Gradle Sync 完成(首次会下载依赖)。
3. 插手机,Run `:app`。

命令行也行:`./gradlew :app:assembleDebug`(Windows 用 `gradlew.bat`)。

## 端到端流程(M1)

1. **烧录固件**:`board-camera.ino`(烧录坑见 `../esp32-s3-cam` 记忆 / PLAN)。
2. **配网**:设备无凭据时开热点 `ESP32-CAM-Setup`(192.168.4.1)。App 首页点 `+` → 扫描附近网络 → 填家庭 WiFi → App 用 `WifiNetworkSpecifier` 连热点 → `POST /config` 下发凭据 → 设备重启入网。
3. **发现**:设备连上局域网后广播 mDNS `_esp32cam._tcp`,App 的 `NsdManager` 解析出设备,首页出现卡片。
4. **看视频**:点卡片 → `:feature-camera` 拉 `/stream`(MJPEG)→ Compose 渲染。
5. **设置**:卡片详情右上角 ⚙ → 分辨率/画质/翻转/镜像(`GET/POST /settings`)。

## 注意事项

- **明文 HTTP**:设备只支持 HTTP,manifest 里开了 `usesCleartextTraffic="true"`(实验室内网用,M2 加令牌)。
- **权限**:mDNS/WiFi 扫描需 `ACCESS_FINE_LOCATION`(<33)或 `NEARBY_WIFI_DEVICES`(33+),首次启动会请求。
- **已知留白(M2)**:共享令牌鉴权(M1 无鉴权,局域网明文)、WebSocket 实时控制、新插件(运动/灯/声量)。UDP 广播兜底、重命名/删除设备、录像/截图均已在 M1 完成。
