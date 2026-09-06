package com.khwlab.hub.store

import android.content.Context
import com.khwlab.hub.core.store.DeviceStore

/** DeviceStore 的 SharedPreferences 实现。 */
class PreferencesDeviceStore(context: Context) : DeviceStore {

    private val prefs = context.getSharedPreferences("khwlab_devices", Context.MODE_PRIVATE)
    private val deletedKey = "deleted_ids"

    override fun displayName(id: String): String? =
        prefs.getString("name_$id", null)

    override fun isDeleted(id: String): Boolean =
        prefs.getStringSet(deletedKey, emptySet()).orEmpty().contains(id)

    override fun clearDeleted() {
        prefs.edit().remove(deletedKey).apply()
    }

    override fun rename(id: String, name: String) {
        prefs.edit().putString("name_$id", name).apply()
    }

    override fun delete(id: String) {
        val set = (prefs.getStringSet(deletedKey, emptySet()).orEmpty() + id).toSet()
        prefs.edit()
            .remove("name_$id")
            .putStringSet(deletedKey, set)
            .apply()
    }
}
