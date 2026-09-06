package com.khwlab.hub.core.plugin

/**
 * 插件注册表:type -> CardPlugin。宿主发现到 Device 后 `registry[device.type]` 取卡。
 */
class CardRegistry {
    private val plugins = LinkedHashMap<String, CardPlugin>()

    fun register(plugin: CardPlugin): CardRegistry {
        plugins[plugin.type] = plugin
        return this
    }

    operator fun get(type: String): CardPlugin? = plugins[type]

    fun all(): List<CardPlugin> = plugins.values.toList()
}
