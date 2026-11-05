package com.admecca.worker

import android.content.Context
import com.google.gson.Gson

data class AppConfig(
    val supabaseUrl: String = "https://cwsdvgkjptuvbdtxcejt.supabase.co",
    val supabaseKey: String = "sb_publishable_Bwr8tWg5019kQrJpJWycig_CNDfvyc0",
    val workerId: String = "zz1",
    val leaseSeconds: Int = 300,
    val stopAfterHardBlocks: Int = 3,
    val userAgent: String = ""
)

object ConfigManager {
    private const val PREFS_NAME = "admecca_config"
    private const val KEY_CONFIG = "config_json_v2"
    private val gson = Gson()

    fun load(context: Context): AppConfig {
        val defaults = AppConfig()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CONFIG, null) ?: return defaults
        return try {
            val saved = gson.fromJson(json, AppConfig::class.java) ?: defaults
            saved.copy(supabaseUrl = defaults.supabaseUrl, supabaseKey = defaults.supabaseKey)
        } catch (e: Exception) {
            defaults
        }
    }

    fun save(context: Context, config: AppConfig) {
        val defaults = AppConfig()
        val fixed = config.copy(supabaseUrl = defaults.supabaseUrl, supabaseKey = defaults.supabaseKey)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CONFIG, gson.toJson(fixed)).apply()
    }

    fun toSupabaseConfig(config: AppConfig): SupabaseConfig {
        return SupabaseConfig(
            url = config.supabaseUrl,
            key = config.supabaseKey,
            workerId = config.workerId
        )
    }
}
