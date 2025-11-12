package com.example.firebaseauthenticationapp

import java.io.Serializable

data class Product(
    var productID: String? = "",
    var name: String? = "",
    var description: String? = "",
    var price: String? = "", // ✅ price as String
    var quantity:String?="",
    var imageUrl: String? = ""
) : Serializable
{
    constructor() : this("", "", "", "", "", "")
}
