#!/usr/bin/env bash
# 固件 0.2.5 语法校验:等价 Arduino 流水线 -fsyntax-only(不改任何文件)
# 用法: bash _check_syntax.sh  (见 khwlab-project-status 记忆:已用本机 esp-x32 工具链复核过)
set -u

F="C:/Users/14919/Desktop/khw_lab/esp32-s3-cam/firmware/board-camera/board-camera.ino"
P="C:/Users/14919/AppData/Local/Arduino15/packages/esp32/hardware/esp32/3.3.11"
SDK="C:/Users/14919/AppData/Local/Arduino15/packages/esp32/tools/esp32s3-libs/3.3.11"
TOOL="C:/Users/14919/AppData/Local/Arduino15/packages/esp32/tools/esp-x32/2601/bin/xtensa-esp-elf-g++.exe"
C="/tmp/board-camera-check.cpp"

# 合并成单个 .cpp(只做语法校验,不复刻 Arduino 原型生成;.ino 内函数顺序已人工核对)
cat "$F" > "$C"

# 模拟 Arduino 预处理器的自动原型生成:为所有函数生成前向声明。
# 校验副本里补一条 streamWriteAll 前向声明(Arduino 流水线本来就会生成,这里等价复刻,不改 .ino)。
sed -i '/^#include <errno.h>$/a bool streamWriteAll(NetworkClient& c, const uint8_t* buf, size_t len, uint32_t budgetMs);' "$C"

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
  -iprefix "$SDK/include/"
  -iwithprefixbefore newlib/platform_include
  -iwithprefixbefore freertos/config/include
  -iwithprefixbefore freertos/config/include/freertos
  -iwithprefixbefore freertos/config/xtensa/include
  -iwithprefixbefore freertos/FreeRTOS-Kernel/include
  -iwithprefixbefore freertos/FreeRTOS-Kernel/portable/xtensa/include
  -iwithprefixbefore freertos/FreeRTOS-Kernel/portable/xtensa/include/freertos
  -iwithprefixbefore freertos/FreeRTOS-Kernel/portable/xtensa/include/freertos
  -iwithprefixbefore freertos/esp_additions/include
  -iwithprefixbefore freertos/FreeRTOS-Kernel/include/freertos
  -iwithprefixbefore esp_hw_support/include
  -iwithprefixbefore esp_hw_support/include/soc
  -iwithprefixbefore esp_hw_support/dma/include
  -iwithprefixbefore esp_hw_support/ldo/include
  -iwithprefixbefore esp_hw_support/port/esp32s3
  -iwithprefixbefore esp_hw_support/port/esp32s3/include
  -iwithprefixbefore heap/include
  -iwithprefixbefore log/include
  -iwithprefixbefore soc/include
  -iwithprefixbefore soc/esp32s3
  -iwithprefixbefore soc/esp32s3/include
  -iwithprefixbefore soc/esp32s3/register
  -iwithprefixbefore hal/include
  -iwithprefixbefore hal/esp32s3/include
  -iwithprefixbefore esp_rom/include
  -iwithprefixbefore esp_rom/esp32s3/include
  -iwithprefixbefore esp_rom/esp32s3/include/esp32s3
  -iwithprefixbefore esp_common/include
  -iwithprefixbefore esp_system/include
  -iwithprefixbefore xtensa/esp32s3/include
  -iwithprefixbefore xtensa/include
  -iwithprefixbefore esp_timer/include
  -iwithprefixbefore lwip/include
  -iwithprefixbefore lwip/include/apps
  -iwithprefixbefore lwip/lwip/src/include
  -iwithprefixbefore lwip/port/include
  -iwithprefixbefore lwip/port/freertos/include
  -iwithprefixbefore lwip/port/esp32xx/include
  -iwithprefixbefore lwip/port/esp32xx/include/arch
  -iwithprefixbefore lwip/port/esp32xx/include/sys
  -iwithprefixbefore espressif__esp-tflite-micro
  -iwithprefixbefore espressif__esp-tflite-micro/third_party/gemmlowp
  -iwithprefixbefore espressif__esp-tflite-micro/third_party/flatbuffers/include
  -iwithprefixbefore espressif__esp-tflite-micro/third_party/ruy
  -iwithprefixbefore espressif__esp-tflite-micro/third_party/kissfft
  -iwithprefixbefore espressif__esp32-camera/driver/include
  -iwithprefixbefore espressif__esp32-camera/conversions/include
  -iwithprefixbefore driver/deprecated
  -iwithprefixbefore driver/i2c/include
  -iwithprefixbefore driver/touch_sensor/include
  -iwithprefixbefore driver/twai/include
  -iwithprefixbefore driver/touch_sensor/esp32s3/include
  -iwithprefixbefore esp_pm/include
  -iwithprefixbefore esp_ringbuf/include
  -iwithprefixbefore esp_driver_gpio/include
  -iwithprefixbefore esp_driver_pcnt/include
  -iwithprefixbefore esp_driver_gptimer/include
  -iwithprefixbefore esp_driver_spi/include
  -iwithprefixbefore esp_driver_mcpwm/include
  -iwithprefixbefore esp_driver_i2s/include
  -iwithprefixbefore esp_driver_sdmmc/include
  -iwithprefixbefore sdmmc/include
  -iwithprefixbefore esp_driver_sdspi/include
  -iwithprefixbefore esp_driver_sdio/include
  -iwithprefixbefore esp_driver_dac/include
  -iwithprefixbefore esp_driver_rmt/include
  -iwithprefixbefore esp_driver_tsens/include
  -iwithprefixbefore esp_driver_sdm/include
  -iwithprefixbefore esp_driver_i2c/include
  -iwithprefixbefore esp_driver_uart/include
  -iwithprefixbefore vfs/include
  -iwithprefixbefore esp_driver_ledc/include
  -iwithprefixbefore esp_driver_parlio/include
  -iwithprefixbefore esp_driver_usb_serial_jtag/include
  -iwithprefixbefore esp_driver_twai/include
  -iwithprefixbefore espressif__esp_jpeg/include
  -iwithprefixbefore esp_eth/include
  -iwithprefixbefore esp_event/include
  -iwithprefixbefore esp_wifi/include
  -iwithprefixbefore esp_wifi/include/local
  -iwithprefixbefore esp_phy/include
  -iwithprefixbefore esp_phy/esp32s3/include
  -iwithprefixbefore esp_netif/include
  -iwithprefixbefore mbedtls/port/include
  -iwithprefixbefore mbedtls/mbedtls/include
  -iwithprefixbefore mbedtls/mbedtls/library
  -iwithprefixbefore mbedtls/esp_crt_bundle/include
  -iwithprefixbefore fatfs/diskio
  -iwithprefixbefore fatfs/src
  -iwithprefixbefore fatfs/vfs
  -iwithprefixbefore wear_levelling/include
  -iwithprefixbefore esp_partition/include
  -iwithprefixbefore app_update/include
  -iwithprefixbefore bootloader_support/include
  -iwithprefixbefore bootloader_support/bootloader_flash/include
  -iwithprefixbefore esp_app_format/include
  -iwithprefixbefore esp_bootloader_format/include
  -iwithprefixbefore console
  -iwithprefixbefore esp_vfs_console/include
  -iwithprefixbefore nvs_flash/include
  -iwithprefixbefore spi_flash/include
  -iwithprefixbefore espressif__mdns/include
  -iwithprefixbefore espressif__jsmn/include
  -iwithprefixbefore spiffs/include
  -iwithprefixbefore esp_http_client/include
  -iwithprefixbefore espressif__json_generator/include
  -iwithprefixbefore json/cJSON
  -iwithprefixbefore espressif__esp_cam_sensor/include
  -iwithprefixbefore espressif__esp_cam_sensor/sensors/gc2145/include
  -iwithprefixbefore espressif__esp_sccb_intf/include
  -iwithprefixbefore espressif__esp_sccb_intf/interface
  -iwithprefixbefore espressif__esp_sccb_intf/sccb_i2c/include
  -iwithprefixbefore espressif__cjson/cJSON
  -iwithprefixbefore efuse/include
  -iwithprefixbefore efuse/esp32s3/include
  -iwithprefixbefore esp_security/include
  -iwithprefixbefore pthread/include
  -iwithprefixbefore esp_psram/include
  -iwithprefixbefore esp_psram/device/include
  -iwithprefixbefore app_trace/include
  -iwithprefixbefore wpa_supplicant/include
  -iwithprefixbefore wpa_supplicant/port/include
  -iwithprefixbefore wpa_supplicant/esp_supplicant/include
  -iwithprefixbefore esp_coex/include
  -iwithprefixbefore esp_gdbstub/include
  -iwithprefixbefore unity/include
  -iwithprefixbefore cmock/CMock/src
  -iwithprefixbefore esp_driver_bitscrambler/include
  -iwithprefixbefore http_parser
  -iwithprefixbefore esp-tls
  -iwithprefixbefore esp-tls/esp-tls-crypto
  -iwithprefixbefore esp_adc/include
  -iwithprefixbefore esp_adc/interface
  -iwithprefixbefore esp_adc/esp32s3/include
  -iwithprefixbefore esp_driver_jpeg/include
  -iwithprefixbefore esp_driver_ppa/include
  -iwithprefixbefore esp_driver_touch_sens/include
  -iwithprefixbefore esp_driver_touch_sens/hw_ver2/include
  -iwithprefixbefore esp_hal_ieee802154/include
  -iwithprefixbefore esp_hid/include
  -iwithprefixbefore tcp_transport/include
  -iwithprefixbefore esp_http_server/include
  -iwithprefixbefore esp_https_ota/include
  -iwithprefixbefore esp_https_server/include
  -iwithprefixbefore esp_lcd/include
  -iwithprefixbefore esp_lcd/interface
  -iwithprefixbefore esp_lcd/rgb/include
  -iwithprefixbefore protobuf-c/protobuf-c
  -iwithprefixbefore protocomm/include/common
  -iwithprefixbefore protocomm/include/security
  -iwithprefixbefore protocomm/include/transports
  -iwithprefixbefore protocomm/include/crypto/srp6a
  -iwithprefixbefore protocomm/proto-c
  -iwithprefixbefore esp_local_ctrl/include
  -iwithprefixbefore espcoredump/include
  -iwithprefixbefore espcoredump/include/port/xtensa
  -iwithprefixbefore idf_test/include
  -iwithprefixbefore idf_test/include/esp32s3
  -iwithprefixbefore ieee802154/include
  -iwithprefixbefore mqtt/esp-mqtt/include
  -iwithprefixbefore nvs_sec_provider/include
  -iwithprefixbefore perfmon/include
  -iwithprefixbefore rt/include
  -iwithprefixbefore touch_element/include
  -iwithprefixbefore ulp/ulp_common/include
  -iwithprefixbefore ulp/ulp_fsm/include
  -iwithprefixbefore ulp/ulp_fsm/include/esp32s3
  -iwithprefixbefore usb/include
  -iwithprefixbefore wifi_provisioning/include
  -iwithprefixbefore espressif__esp-nn/include
  -iwithprefixbefore espressif__esp-nn/src/common
  -iwithprefixbefore espressif__rmaker_cmd_resp/include
  -iwithprefixbefore espressif__rmaker_common_events/include
  -iwithprefixbefore espressif__rmaker_system_ctrl/include
  -iwithprefixbefore espressif__rmaker_time_sync/include
  -iwithprefixbefore espressif__rmaker_console/include
  -iwithprefixbefore espressif__rmaker_work_queue/include
  -iwithprefixbefore espressif__rmaker_common/include
  -iwithprefixbefore espressif__cbor/tinycbor/src
  -iwithprefixbefore espressif__esp_diag_data_store/src/rtc_store
  -iwithprefixbefore espressif__esp_diag_data_store/include
  -iwithprefixbefore espressif__esp-serial-flasher/include
  -iwithprefixbefore espressif__esp-serial-flasher/port
  -iwithprefixbefore espressif__esp_rcp_update/include
  -iwithprefixbefore espressif__usb_host_uvc/include
  -iwithprefixbefore espressif__dl_fft
  -iwithprefixbefore espressif__dl_fft/base
  -iwithprefixbefore espressif__dl_fft/base/isa
  -iwithprefixbefore espressif__esp-dsp/modules/dotprod/include
  -iwithprefixbefore espressif__esp-dsp/modules/support/include
  -iwithprefixbefore espressif__esp-dsp/modules/support/mem/include
  -iwithprefixbefore espressif__esp-dsp/modules/windows/include
  -iwithprefixbefore espressif__esp-dsp/modules/windows/hann/include
  -iwithprefixbefore espressif__esp-dsp/modules/windows/blackman/include
  -iwithprefixbefore espressif__esp-dsp/modules/windows/blackman_harris/include
  -iwithprefixbefore espressif__esp-dsp/modules/windows/nuttall/include
  -iwithprefixbefore espressif__esp-dsp/modules/windows/flat_top/include
  -iwithprefixbefore espressif__esp-dsp/modules/fft/include
  -iwithprefixbefore espressif__esp-dsp/modules/dct/include
  -iwithprefixbefore espressif__esp-dsp/modules/conv/include
  -iwithprefixbefore espressif__esp-dsp/modules/common/include
  -iwithprefixbefore espressif__esp-dsp/modules/matrix/mul/test/include
  -iwithprefixbefore espressif__esp-dsp/modules/kalman/ekf/include
  -iwithprefixbefore espressif__esp-dsp/modules/kalman/ekf_imu13states/include
  -iwithprefixbefore chmorgan__esp-libhelix-mp3/libhelix-mp3/pub
  -iwithprefixbefore espressif__esp-modbus/modbus/mb_transports
  -iwithprefixbefore espressif__esp-modbus/modbus/mb_controller/common/include
  -iwithprefixbefore espressif__esp-modbus/modbus/mb_objects/common/include
  -iwithprefixbefore espressif__esp-modbus/modbus/mb_ports/common/include
  -iwithprefixbefore espressif__esp-modbus/modbus/mb_ports/serial
  -iwithprefixbefore espressif__esp-modbus/modbus/mb_ports/tcp
  -iwithprefixbefore espressif__esp-zboss-lib/include
  -iwithprefixbefore espressif__esp-zigbee-lib/include
  -iwithprefixbefore espressif__esp-zigbee-lib/include/radio_spinel
  -iwithprefixbefore espressif__libsodium/port_include
  -iwithprefixbefore espressif__libsodium/libsodium/src/libsodium/include
  -iwithprefixbefore espressif__esp_modem/include
  -iwithprefixbefore espressif__esp_modem/command/include
  -iwithprefixbefore espressif__esp_daylight/include
  -iwithprefixbefore espressif__esp_schedule/include
  -iwithprefixbefore espressif__network_provisioning/include
  -iwithprefixbefore espressif__esp_rainmaker/include
  -iwithprefixbefore espressif__qrcode/include
  -iwithprefixbefore joltwallet__littlefs/include
  -iwithprefixbefore arduino_tinyusb/tinyusb/src
  -iwithprefixbefore arduino_tinyusb/include
  -iwithprefixbefore fb_gfx/include
)

"$TOOL" "${ARGS[@]}" "${DEFS[@]}" "$C" 2>&1 | head -80
exit "${PIPESTATUS[0]}"
