package com.ghissue.app.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class CreateIssueRequest(
    @Json(name = "title") val title: String,
    @Json(name = "body") val body: String? = null,
    @Json(name = "labels") val labels: List<String>? = null
)

@JsonClass(generateAdapter = false)
data class IssueResponse(
    @Json(name = "id") val id: Long,
    @Json(name = "number") val number: Int,
    @Json(name = "html_url") val htmlUrl: String,
    @Json(name = "title") val title: String
)

@JsonClass(generateAdapter = false)
data class DeviceCodeRequest(
    @Json(name = "client_id") val clientId: String,
    @Json(name = "scope") val scope: String
)

@JsonClass(generateAdapter = false)
data class DeviceCodeResponse(
    @Json(name = "device_code") val deviceCode: String,
    @Json(name = "user_code") val userCode: String,
    @Json(name = "verification_uri") val verificationUri: String,
    @Json(name = "expires_in") val expiresIn: Int,
    @Json(name = "interval") val interval: Int
)

@JsonClass(generateAdapter = false)
data class DeviceTokenRequest(
    @Json(name = "client_id") val clientId: String,
    @Json(name = "device_code") val deviceCode: String,
    @Json(name = "grant_type") val grantType: String = "urn:ietf:params:oauth:grant-type:device_code"
)

@JsonClass(generateAdapter = false)
data class OAuthTokenResponse(
    @Json(name = "access_token") val accessToken: String?,
    @Json(name = "token_type") val tokenType: String?,
    @Json(name = "scope") val scope: String?,
    @Json(name = "error") val error: String?,
    @Json(name = "error_description") val errorDescription: String?
)

@JsonClass(generateAdapter = false)
data class RepoResponse(
    @Json(name = "full_name") val fullName: String,
    @Json(name = "name") val name: String,
    @Json(name = "owner") val owner: RepoOwner
)

@JsonClass(generateAdapter = false)
data class RepoOwner(
    @Json(name = "login") val login: String
)

@JsonClass(generateAdapter = false)
data class LabelResponse(
    @Json(name = "name") val name: String,
    @Json(name = "color") val color: String
)
