#!/usr/bin/env bash
# board-lock(XIAO ESP32-C3, riscv32)语法校验:等价 Arduino 流水线 -fsyntax-only(不改任何文件)
# 用法: bash _check_syntax_c3.sh
# 模板: board-camera/_check_syntax.sh(ESP32-S3 xtensa)改造而来;C3 走 riscv32 工具链 + esp32c3-libs。
# 要点:
#   - 工具链: esp-rv32/2601/bin/riscv32-esp-elf-g++.exe(本机已验证存在)
#   - 头文件: 直接注入 $SDK/flags/includes(完整 -iwithprefixbefore 列表,含 freertos riscv port、
#     soc/esp32c3、esp_rom/esp32c3、riscv/include、bt/... esp32c3 等),绝不手写 xtensa 路径
#   - cpp_flags: 照搬 $SDK/flags/cpp_flags(含 -march=rv32imc_zicsr_zifencei 等架构必选)
#   - defines:  照搬 $SDK/flags/defines(单行,含 CHIP_HAVE_CONFIG_H 等)
# 该脚本只做语法校验,不产生二进制;.ino 内函数顺序由合并副本前向声明保证。
set -u

F="C:/Users/14919/Desktop/khw_lab/esp32-s3-cam/firmware/board-lock/board-lock.ino"
FW="C:/Users/14919/Desktop/khw_lab/esp32-s3-cam/firmware"
P="C:/Users/14919/AppData/Local/Arduino15/packages/esp32/hardware/esp32/3.3.11"
SDK="C:/Users/14919/AppData/Local/Arduino15/packages/esp32/tools/esp32c3-libs/3.3.11"
TOOL="C:/Users/14919/AppData/Local/Arduino15/packages/esp32/tools/esp-rv32/2601/bin/riscv32-esp-elf-g++.exe"
C="/tmp/board-lock-check.cpp"

# 合并成单个 .cpp(只做语法校验,不复刻 Arduino 原型生成;.ino 内函数顺序已人工核对)
cat "$F" > "$C"

# 模拟 Arduino 预处理器的自动原型生成 + Arduino.h 注入:
# 正常 Arduino 编译在 .ino 前自动 #include <Arduino.h>(内含 esp32-hal.h → FreeRTOS)。
# 校验副本是直接合并 .ino,需手动补这个 include;板级 FreeRTOS 任务入口等按需补原型。
sed -i '/^#include <errno.h>$/a #include <esp32-hal.h>\n' "$C"

# 追加 lib/common 骨架头(与 .ino 里 #include "lib_common.h" 路径对齐)
# (骨架头若存在即解析;.ino 已 include 时此追加无害)
printf '\n#include "lib_common.h"\n' >> "$C"

# 板级宏(XIAO ESP32-C3,对齐 boards.txt XIAO_ESP32C3 段 + platform.txt recipe):
#   build.cdc_on_boot=1、build.f_cpu=160000000L、build.flash_size=4MB、flash_mode=qio → memory_type=qio_qspi
#   ARDUINO_ARCH_ESP32(S3 脚本同款)、-DESP32C3(esp32-hal.h 第 92 行按 CONFIG_IDF_TARGET_ESP32C3 分派,sdkconfig 已定义)
DEFS=(
  -DESP32
  -DARDUINO=10607
  -DARDUINO_XIAO_ESP32C3
  -DARDUINO_ARCH_ESP32
  "-DARDUINO_BOARD=\"XIAO_ESP32C3\""
  "-DARDUINO_VARIANT=\"XIAO_ESP32C3\""
  -DARDUINO_PARTITION_default
  -DARDUINO_USB_CDC_ON_BOOT=1
  -DF_CPU=160000000L
  -DCORE_DEBUG_LEVEL=0
  -DESP32C3
)

# lib/common/*.cpp 独立编译所需的板级宏(等价 board-lock.ino 顶部 #define;
# 缺失会触发 lib_common.h 的 #error。这些文件不经过 .ino 合并,必须在此喂入)
COMMON_DEFS=(
  "${DEFS[@]}"
  -DKHW_BOARD_TYPE=\"lock\"
  -DKHW_DEVICE_NAME_PREFIX=\"KHW-LOCK\"
  -DKHW_SERVICE_TYPE=\"esp32lock\"
  -DKHW_IS_LOCK
  -DKHW_MODE_LED_WS2812
  -DKHW_MODE_LED_PIN=7
  -DKHW_BOOT_PIN=0
)

# 架构/编译器必选 cpp 标志(取自 $SDK/flags/cpp_flags 的核心子集 + S3 脚本同款宽松项)
ARGS=(
  -x c++
  -fsyntax-only
  -march=rv32imc_zicsr_zifencei
  -fno-rtti
  -std=gnu++2a
  -fexceptions
  -ffunction-sections
  -fdata-sections
  -Wno-error=unused-function
  -Wno-error=unused-variable
  -Wno-error=deprecated-declarations
  -Wno-unused-parameter
  -Wno-sign-compare
  -Wno-enum-conversion
  "-I$P/cores/esp32"
  "-I$P/variants/XIAO_ESP32C3"
  "-I$SDK/include"
  "-I$SDK/qio_qspi/include"
  "-I$P/libraries/WiFi/src"
  "-I$P/libraries/Network/src"
  "-I$P/libraries/WebServer/src"
  "-I$P/libraries/FS/src"
  "-I$P/libraries/ESPmDNS/src"
  "-I$P/libraries/Preferences/src"
  "-I$P/libraries/BLE/src"
  "-I$P/libraries/SimpleBLE/src"
  "-I$FW/lib/common"
  -iprefix "$SDK/include/"
)

# 直接注入 esp32c3-libs 完整 -iwithprefixbefore 列表(单行,read -r 整行读取按空格切分)
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

echo "== 1/2 board-lock.ino(+lib_common.h) =="
"$TOOL" "${ARGS[@]}" "${DEFS[@]}" "$C" 2>&1 | head -80 || true

echo "== 2/2 lib/common/*.cpp =="
LIBFAIL=0
for F in "$FW"/lib/common/*.cpp; do
  # 通过 COMMON_DEFS 供板级宏,无需预合并 .ino
  "$TOOL" "${ARGS[@]}" "${COMMON_DEFS[@]}" "$F" 2>&1 | head -40
  # bash 管道让 $? 取到的是 head 的退出码;改用 PIPESTATUS 判断编译结果
  rc=("${PIPESTATUS[@]}")
  if [ "${rc[0]}" -ne 0 ]; then LIBFAIL=1; fi
done

[ "$LIBFAIL" -eq 0 ] && exit 0
exit 1
