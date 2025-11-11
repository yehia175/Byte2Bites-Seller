package com.example.firebaseauthenticationapp

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class ProductFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyTextView: TextView
    private lateinit var productList: MutableList<Product>

    private val database = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_product, container, false)

        recyclerView = view.findViewById(R.id.recyclerViewProducts)
        emptyTextView = view.findViewById(R.id.textViewEmpty)

        recyclerView.layoutManager = GridLayoutManager(context, 2)
        productList = mutableListOf()

        // Load seller products
        loadProducts()

        return view
    }

    private fun loadProducts() {
        val sellerUID = auth.currentUser?.uid
        if (sellerUID == null) {
            Log.e("ProductFragment", "Current user is null! User is not signed in.")
            emptyTextView.visibility = View.VISIBLE
            emptyTextView.text = "User not signed in"
            return
        }

        val sellerProductsRef = database.getReference("Sellers/$sellerUID/products")

        sellerProductsRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                productList.clear()

                if (!snapshot.exists()) {
                    emptyTextView.visibility = View.VISIBLE
                    emptyTextView.text = "No products added yet"
                    return
                }

                for (productSnap in snapshot.children) {
                    try {
                        val product = productSnap.getValue(Product::class.java)
                        if (product != null) {
                            productList.add(product)
                        } else {
                            Log.w("ProductFragment", "Product is null: $productSnap")
                        }
                    } catch (e: Exception) {
                        Log.e("ProductFragment", "Failed to parse product: $productSnap", e)
                    }
                }

                emptyTextView.visibility = if (productList.isEmpty()) View.VISIBLE else View.GONE
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("ProductFragment", "Firebase error: ${error.message}")
                emptyTextView.visibility = View.VISIBLE
                emptyTextView.text = "Failed to load products"
            }
        })
    }

    // --- Inner Product class ---
    data class Product(
        var name: String? = "",
        var description: String? = "",
        var price: Double? = 0.0
    ) {
        constructor() : this("", "", 0.0) // Required for Firebase
    }
}
