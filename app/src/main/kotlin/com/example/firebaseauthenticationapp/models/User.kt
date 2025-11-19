package com.example.firebaseauthenticationapp.models

data class User(
    val name: String? = "",
    val email: String? = "",
    val phone: String? = "",
    val profileImageUrl: String? = "",
    var address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)
