#!/usr/bin/env bash
# 固件 0.3.0 语法校验:等价 Arduino 流水线 -fsyntax-only(不改任何文件)
# 用法: bash _check_syntax.sh  (见 khwlab-project-status 记忆:已用本机 esp-x32 工具链复核过)
# 覆盖 board-camera(ESP32-S3, xtensa) 及 lib/common/*.cpp;
# board-lock(XIAO C3, riscv32) 用同目录 _check_syntax_c3.sh。
# 要点:
#   - 头文件: 直接注入 $SDK/flags/includes(完整 -iwithprefixbefore 列表,含 bt/... NimBLE、
#     freertos xtensa port、soc/esp32s3、esp_rom/esp32s3 等),绝不手写(M2 引入 BLE 后
#     手写列表必然漏 bt 路径 → esp_bt.h No such file;与 _check_syntax_c3.sh 同法)
#   - defines:  照搬 $SDK/flags/defines(单行,含 CHIP_HAVE_CONFIG_H 等),跳过含引号的 -D
set -u

F="C:/Users/14919/Desktop/khw_lab/esp32-s3-cam/firmware/board-camera/board-camera.ino"
FW="C:/Users/14919/Desktop/khw_lab/esp32-s3-cam/firmware"
P="C:/Users/14919/AppData/Local/Arduino15/packages/esp32/hardware/esp32/3.3.11"
SDK="C:/Users/14919/AppData/Local/Arduino15/packages/esp32/tools/esp32s3-libs/3.3.11"
TOOL="C:/Users/14919/AppData/Local/Arduino15/packages/esp32/tools/esp-x32/2601/bin/xtensa-esp-elf-g++.exe"
C="/tmp/board-camera-check.cpp"

# 合并成单个 .cpp(只做语法校验,不复刻 Arduino 原型生成;.ino 内函数顺序已人工核对)
cat "$F" > "$C"

# 模拟 Arduino 预处理器的自动原型生成:为所有函数生成前向声明。
# 校验副本里补原型(Arduino 流水线本来就会生成,这里等价复刻,不改 .ino)。
# esp32-hal.h:正常 Arduino 编译在 .ino 前自动 #include <Arduino.h>(内含 esp32-hal.h → FreeRTOS),
# 校验副本是直接合并 .ino,需手动补这个 include 才有 xTaskCreatePinnedToCore 声明。
# 0.3.0:摄像头板已删除独立 factoryResetTask(并入 provision 状态机),只留 streamWriteAll。
sed -i '/^#include <errno.h>$/a #include <esp32-hal.h>\nbool streamWriteAll(NetworkClient& c, const uint8_t* buf, size_t len, uint32_t budgetMs);' "$C"

# 追加 lib/common 骨架头(与 .ino 里 #include "lib_common.h" 路径对齐)
# (骨架头若存在即解析;.ino 已 include 时此追加无害)
printf '\n#include "lib_common.h"\n' >> "$C"

DEFS=(
  -DESP32
  -DARDUINO=10607
  -DARDUINO_ESP32S3_DEV
  -DARDUINO_ARCH_ESP32
  "-DARDUINO_BOARD=\"ESP32S3_DEV\""
  "-DARDUINO_VARIANT=\"esp32s3\""
  -DARDUINO_PARTITION_default
  -DARDUINO_USB_MODE=0
  -DARDUINO_USB_CDC_ON_BOOT=0
  -DBOARD_HAS_PSRAM
  -DARDUINO_RUNNING_CORE=1
  -DESP32S3
)

ARGS=(
  -x c++
  -fsyntax-only
  -mlongcalls
  -fno-rtti
  -fno-builtin-memcpy
  -fno-builtin-memset
  -std=gnu++2a
  -fexceptions
  -ffunction-sections
  -fdata-sections
  -Wno-error=unused-function
  -Wno-error=unused-variable
  -Wno-error=deprecated-declarations
  -Wno-unused-parameter
  -Wno-sign-compare
  "-I$P/cores/esp32"
  "-I$P/variants/esp32s3"
  "-I$SDK/include"
  "-I$SDK/qio_qspi/include"
  "-I$SDK/include/espressif__esp32-camera/driver/include"
  "-I$SDK/include/espressif__esp32-camera/conversions/include"
  "-I$P/libraries/WiFi/src"
  "-I$P/libraries/Network/src"
  "-I$P/libraries/WebServer/src"
  "-I$P/libraries/FS/src"
  "-I$P/libraries/ESPmDNS/src"
  "-I$P/libraries/Preferences/src"
  "-I$P/libraries/BLE/src"
  "-I$P/libraries/SimpleBLE/src"
  "-I$FW/lib/common"
  "-I$P/libraries/base64/src"
  -iprefix "$SDK/include/"
)

# 直接注入 esp32s3-libs 完整 -iwithprefixbefore 列表(单行,read -r 整行读取按空格切分;
# 含 bt/host/nimble、bt/include/esp32c3/include(esp_bt.h)、freertos xtensa port 等全量路径)
INCLUDE_ARGS=()
read -r -a INCLUDE_ARGS < "$SDK/flags/includes"
ARGS+=("${INCLUDE_ARGS[@]}")

# defines 单行直接注入(与 cpp_flags 相同来源,避免手写遗漏 CHIP_HAVE_CONFIG_H 等)
DEFINES_ARGS=()
read -r -a DEFINES_ARGS < "$SDK/flags/defines"
# 仅保留纯 -D 宏(丢弃任何带空格/引号的片段,那些会破坏命令行解析)
for d in "${DEFINES_ARGS[@]}"; do
  case "$d" in
    -D*\"*) :;;        # 跳过含引号的 -D(如版本字符串宏,会引发 <command-line> 警告)
    -D*)    ARGS+=("$d");;
    *)      :;;
  esac
done

# lib/common/*.cpp 独立编译所需的板级宏(等价 board-camera.ino 顶部 #define;
# 缺失会触发 lib_common.h 的 #error。这些文件不经过 .ino 合并,必须在此喂入)
COMMON_DEFS=(
  "${DEFS[@]}"
  -DKHW_BOARD_TYPE=\"camera\"
  -DKHW_DEVICE_NAME_PREFIX=\"KHW-CAM\"
  -DKHW_SERVICE_TYPE=\"esp32cam\"
  -DKHW_MODE_LED_GPIO
  -DKHW_MODE_LED_PIN=21
  -DKHW_BOOT_PIN=0
)

echo "== 1/2 board-camera.ino(+lib_common.h) =="
"$TOOL" "${ARGS[@]}" "${DEFS[@]}" "$C" 2>&1 | head -80 || true

echo "== 2/2 lib/common/*.cpp =="
LIBFAIL=0
for CF in "$FW"/lib/common/*.cpp; do
  # 通过 COMMON_DEFS 供板级宏,无需预合并 .ino
  "$TOOL" "${ARGS[@]}" "${COMMON_DEFS[@]}" "$CF" 2>&1 | head -40
  # bash 管道让 $? 取到的是 head 的退出码;改用 PIPESTATUS 判断编译结果
  rc=("${PIPESTATUS[@]}")
  if [ "${rc[0]}" -ne 0 ]; then LIBFAIL=1; fi
done

[ "$LIBFAIL" -eq 0 ] && exit 0
exit 1
