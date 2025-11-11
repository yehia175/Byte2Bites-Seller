package com.example.firebaseauthenticationapp

import android.os.Bundle
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_product, container, false)

        recyclerView = view.findViewById(R.id.recyclerViewProducts)
        emptyTextView = view.findViewById(R.id.textViewEmpty)

        recyclerView.layoutManager = GridLayoutManager(context, 2)
        productList = mutableListOf()
        adapter = ProductAdapter(productList)
        recyclerView.adapter = adapter

        loadProducts()

        return view
    }

    private fun loadProducts() {
        val sellerUID = auth.currentUser?.uid ?: return
        val sellerProductsRef = database.getReference("sellers/$sellerUID/products")

        sellerProductsRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                productList.clear()
                if (!snapshot.exists()) {
                    emptyTextView.visibility = View.VISIBLE
                    adapter.notifyDataSetChanged()
                    return
                }

                // Loop through all product IDs of this seller
                for (productIdSnap in snapshot.children) {
                    val productID = productIdSnap.key ?: continue
                    val productRef = database.getReference("products/$productID")

                    productRef.addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(productSnapshot: DataSnapshot) {
                            val product = productSnapshot.getValue(Product::class.java)
                            if (product != null) {
                                productList.add(product)
                                adapter.notifyDataSetChanged()
                                emptyTextView.visibility = View.GONE
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {
                            // Handle errors if needed
                        }
                    })
                }
            }

            override fun onCancelled(error: DatabaseError) {
                // Handle errors if needed
            }
        })
    }
}
