package com.ratatoskr.shared.api

import kotlinx.serialization.Serializable

/**
 * These data classes are a deliberate field-for-field mirror of the
 * server's app/schemas.py. Kept as a flat 1:1 match rather than a
 * "nicer" client-side shape, so a schema change on the server is a
 * one-file diff to find here too, not a guessing game. Role is modeled
 * as a plain String (matching the server's `Role = str` type alias)
 * rather than a Kotlin enum, so an unrecognized future role value never
 * fails to deserialize. See RoleLabels.kt for display-only handling.
 */

@Serializable
data class SetupRequest(val username: String, val master_password: String)

@Serializable
data class UnlockRequest(val username: String, val master_password: String)

@Serializable
data class AcceptInviteRequest(val username: String, val invite_token: String, val master_password: String)

@Serializable
data class AuthResponse(val token: String, val expires_in: Int, val username: String, val role: String)

@Serializable
data class StatusResponse(val initialized: Boolean)

@Serializable
data class MeResponse(val username: String, val role: String)

@Serializable
data class ChangePasswordRequest(val current_password: String, val new_password: String)

@Serializable
data class FactoryResetRequest(val confirm_wipe: Boolean = false)

@Serializable
data class InviteRequest(val username: String, val role: String = "user")

@Serializable
data class InviteResponse(val username: String, val role: String, val token: String, val expires_in: Int)

@Serializable
data class UserOut(val id: String, val username: String, val role: String, val created_at: Double)

@Serializable
data class RoleUpdateRequest(val role: String)

@Serializable
data class EntryIn(
    val site: String,
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val notes: String = "",
    val totp_secret: String = "",
)

@Serializable
data class EntryOut(
    val id: String,
    val site: String,
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val notes: String = "",
    val totp_secret: String = "",
    val owner_username: String,
    val can_write: Boolean,
    val shared: Boolean,
    val created_at: Double,
    val updated_at: Double,
)

@Serializable
data class TotpCodeOut(val code: String, val period: Int, val seconds_remaining: Int)

@Serializable
data class ShareRequest(val username: String, val can_write: Boolean = false)

@Serializable
data class ShareEntryOut(val username: String, val role: String, val can_write: Boolean)

@Serializable
data class ImportResult(val imported: Int, val skipped: Int, val errors: List<String>)

@Serializable
data class GenerateRequest(
    val length: Int = 20,
    val use_upper: Boolean = true,
    val use_lower: Boolean = true,
    val use_digits: Boolean = true,
    val use_symbols: Boolean = true,
    val avoid_ambiguous: Boolean = true,
)

@Serializable
data class GenerateResponse(val password: String)

/** The three roles the server currently defines, for display/UI purposes
 * only, never used for (de)serialization, so a role the client doesn't
 * know about yet still round-trips fine as a plain string. */
object Roles {
    const val ADMIN = "admin"
    const val USER = "user"
    const val VISITOR = "visitor"
    val ALL = listOf(ADMIN, USER, VISITOR)
}
