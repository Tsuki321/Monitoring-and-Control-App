package com.watermonitor.app.data.model

data class SavedAccount(
    val uid: String,
    val email: String,
    val displayName: String?,
    val photoUrl: String?,
    val provider: AuthProvider
)

enum class AuthProvider {
    EMAIL,
    GOOGLE,
    FACEBOOK
}
