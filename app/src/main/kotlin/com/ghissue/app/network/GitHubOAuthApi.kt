package com.ghissue.app.network

import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

interface GitHubOAuthApi {

    @Headers("Accept: application/json")
    @POST("login/device/code")
    suspend fun requestDeviceCode(@Body request: DeviceCodeRequest): DeviceCodeResponse

    @Headers("Accept: application/json")
    @POST("login/oauth/access_token")
    suspend fun pollForToken(@Body request: DeviceTokenRequest): OAuthTokenResponse
}
