package com.example.firebaseauthenticationapp

data class OrderDisplay(
    val orderId: String = "",       // Firebase order ID
    val sellerUid: String = "",
    val buyerUid: String = "", // Seller UID
    val buyerName: String = "",
    val items: List<OrderItem> = emptyList()
)
