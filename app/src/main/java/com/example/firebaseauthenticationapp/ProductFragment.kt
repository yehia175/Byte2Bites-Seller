package com.example.firebaseauthenticationapp

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.TextView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class ProductFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyTextView: TextView
    private lateinit var productList: MutableList<Product>
    private lateinit var adapter: ProductAdapter

    private val database = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // Define a tag for our logs
    private val TAG = "ProductFragment"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_product, container, false)
        Log.d(TAG, "onCreateView: Fragment view created.")

        recyclerView = view.findViewById(R.id.recyclerViewProducts)
        emptyTextView = view.findViewById(R.id.textViewEmpty)

        recyclerView.layoutManager = GridLayoutManager(context, 2)
        productList = mutableListOf()
        adapter = ProductAdapter(productList) // We still need to see ProductAdapter.kt
        recyclerView.adapter = adapter

        loadProducts()

        return view
    }

    private fun loadProducts() {
        val sellerUID = auth.currentUser?.uid
        if (sellerUID == null) {
            Log.e(TAG, "loadProducts: Seller UID is null. User not authenticated.")
            emptyTextView.text = "Error: Not logged in."
            emptyTextView.visibility = View.VISIBLE
            return
        }

        Log.d(TAG, "loadProducts: Loading products for seller UID: $sellerUID")
        val sellerProductsRef = database.getReference("Sellers/$sellerUID/products")

        sellerProductsRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                productList.clear()
                if (!snapshot.exists()) {
                    Log.d(TAG, "onDataChange: No products found for this seller.")
                    emptyTextView.text = "No products added yet"
                    emptyTextView.visibility = View.VISIBLE
                    adapter.notifyDataSetChanged()
                    return
                }

                Log.d(TAG, "onDataChange: Found ${snapshot.childrenCount} product IDs. Fetching details...")
                emptyTextView.visibility = View.GONE

                // Loop through all product IDs of this seller
                for (productIdSnap in snapshot.children) {
                    val productID = productIdSnap.key
                    if (productID == null) continue

                    Log.d(TAG, "Fetching details for product ID: $productID")
                    val productRef = database.getReference("products/$productID")

                    productRef.addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(productSnapshot: DataSnapshot) {
                            if (!productSnapshot.exists()) {
                                Log.w(TAG, "Product detail not found for ID: $productID")
                                return
                            }

                            // This is the most important line to check
                            val product = productSnapshot.getValue(Product::class.java)

                            if (product != null) {
                                Log.d(TAG, "SUCCESS: Fetched product: ${product.name}")
                                productList.add(product)
                            } else {
                                Log.e(TAG, "ERROR: Failed to parse product data for ID: $productID. Check your Product.kt data class!")
                            }
                            // Notify adapter after each item is loaded
                            adapter.notifyDataSetChanged()
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.e(TAG, "Failed to read product detail for $productID: ${error.message}")
                        }
                    })
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Failed to read seller's product list: ${error.message}")
            }
        })
    }
}