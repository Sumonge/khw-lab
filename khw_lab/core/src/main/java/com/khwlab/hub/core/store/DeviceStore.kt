package com.khwlab.hub.core.store

/**
 * 设备本地偏好存储(重命名 / 删除),宿主注入具体实现(如 SharedPreferences)。
 * 契约层只管语义,不绑 Android 存储 API,便于以后换 Room 等。
 */
interface DeviceStore {
    /** 用户改过的显示名,未改过返回 null。 */
    fun displayName(id: String): String?

    /** 是否被用户删除(删除后不再自动发现)。 */
    fun isDeleted(id: String): Boolean

    /** 清空已删除集合:配网成功 = 用户明确要找回设备,让所有被隐藏设备重新可发现。 */
    fun clearDeleted()

    fun rename(id: String, name: String)

    fun delete(id: String)
}
