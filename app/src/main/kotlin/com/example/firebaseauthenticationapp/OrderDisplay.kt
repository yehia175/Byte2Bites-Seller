package com.example.firebaseauthenticationapp

data class OrderDisplay(
    val orderId: String = "",      // Firebase order ID
    val sellerUid: String = "",    // Seller UID for Firebase reference
    val buyerName: String = "",
    val items: List<OrderItem> = emptyList()
)
