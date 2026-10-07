package dev.quietnet

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("quietnet", Context.MODE_PRIVATE)
    }

    /** True while the user wants protection on, so it comes back after a reboot or update. */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var enabledLists: Set<String>
        get() = sp.getStringSet("lists", null)?.toSet() ?: Catalog.defaults
        set(v) = sp.edit().putStringSet("lists", v).apply()

    var userAllow: Set<String>
        get() = sp.getStringSet("allow", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("allow", v).apply()

    var userBlock: Set<String>
        get() = sp.getStringSet("block", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("block", v).apply()

    var excludedApps: Set<String>
        get() = sp.getStringSet("excluded", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("excluded", v).apply()

    var upstream: String
        get() = sp.getString("upstream", "auto") ?: "auto"
        set(v) = sp.edit().putString("upstream", v).apply()

    var lastUpdate: Long
        get() = sp.getLong("lastUpdate", 0)
        set(v) = sp.edit().putLong("lastUpdate", v).apply()

    fun listCount(id: String): Int = sp.getInt("count_$id", 0)
    fun setListCount(id: String, n: Int) = sp.edit().putInt("count_$id", n).apply()
}
