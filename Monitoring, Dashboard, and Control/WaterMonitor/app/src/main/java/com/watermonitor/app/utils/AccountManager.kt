package com.watermonitor.app.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseUser
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.watermonitor.app.data.model.AuthProvider
import com.watermonitor.app.data.model.SavedAccount

object AccountManager {

    private const val PREFS_NAME = "hydrosense_accounts"
    private const val KEY_ACCOUNTS = "saved_accounts"
    private const val KEY_LAST_LOGGED_IN_UID = "last_logged_in_uid"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveAccount(context: Context, user: FirebaseUser, provider: AuthProvider) {
        val accounts = getSavedAccounts(context).toMutableList()

        val newAccount = SavedAccount(
            uid = user.uid,
            email = user.email ?: "",
            displayName = user.displayName,
            photoUrl = user.photoUrl?.toString(),
            provider = provider
        )

        // Remove existing account with same UID if present
        accounts.removeAll { it.uid == user.uid }

        // Add new account at the beginning
        accounts.add(0, newAccount)

        // Keep only last 3 accounts
        if (accounts.size > 3) {
            accounts.removeAt(accounts.size - 1)
        }

        val json = Gson().toJson(accounts)
        getPrefs(context).edit().putString(KEY_ACCOUNTS, json).apply()

        // Save as last logged in
        setLastLoggedInUid(context, user.uid)
    }

    fun getSavedAccounts(context: Context): List<SavedAccount> {
        val json = getPrefs(context).getString(KEY_ACCOUNTS, null) ?: return emptyList()
        val type = object : TypeToken<List<SavedAccount>>() {}.type
        return Gson().fromJson(json, type) ?: emptyList()
    }

    fun removeAccount(context: Context, uid: String) {
        val accounts = getSavedAccounts(context).toMutableList()
        accounts.removeAll { it.uid == uid }
        val json = Gson().toJson(accounts)
        getPrefs(context).edit().putString(KEY_ACCOUNTS, json).apply()

        // Clear last logged in if it was this account
        if (getLastLoggedInUid(context) == uid) {
            clearLastLoggedInUid(context)
        }
    }

    fun getLastLoggedInAccount(context: Context): SavedAccount? {
        val uid = getLastLoggedInUid(context) ?: return null
        return getSavedAccounts(context).firstOrNull { it.uid == uid }
    }

    private fun setLastLoggedInUid(context: Context, uid: String) {
        getPrefs(context).edit().putString(KEY_LAST_LOGGED_IN_UID, uid).apply()
    }

    private fun getLastLoggedInUid(context: Context): String? {
        return getPrefs(context).getString(KEY_LAST_LOGGED_IN_UID, null)
    }

    private fun clearLastLoggedInUid(context: Context) {
        getPrefs(context).edit().remove(KEY_LAST_LOGGED_IN_UID).apply()
    }

    fun clearAllAccounts(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
