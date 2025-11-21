package com.example.firebaseauthenticationapp

data class OrderDisplay(
    val orderId: String = "",       // Firebase order ID
    val sellerUid: String = "",
    val buyerUid: String = "",      // Buyer UID
    val buyerName: String = "",
    val items: List<OrderItem> = emptyList(),
    val deliveryType: String = "" ,  // <-- Add this
    val status: String = "PENDING" // default status
)
