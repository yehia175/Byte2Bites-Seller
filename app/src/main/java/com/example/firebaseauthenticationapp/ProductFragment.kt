package com.example.firebaseauthenticationapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
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
    private lateinit var adapter: ProductAdapter

    private val database = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val TAG = "ProductFragment"

    companion object {
        private const val REQUEST_EDIT_PRODUCT = 101
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_product, container, false)

        recyclerView = view.findViewById(R.id.recyclerViewProducts)
        emptyTextView = view.findViewById(R.id.textViewEmpty)

        recyclerView.layoutManager = GridLayoutManager(context, 2)
        productList = mutableListOf()

        adapter = ProductAdapter(requireContext(), productList) { product ->
            // Open EditProductActivity with startActivityForResult
            val intent = Intent(requireContext(), EditProductActivity::class.java)
            intent.putExtra(EditProductActivity.EXTRA_PRODUCT, product)
            startActivityForResult(intent, REQUEST_EDIT_PRODUCT)
        }

        recyclerView.adapter = adapter
        loadProducts()
        return view
    }

    private fun loadProducts() {
        val sellerUID = auth.currentUser?.uid
        if (sellerUID == null) {
            emptyTextView.text = "Error: Not logged in."
            emptyTextView.visibility = View.VISIBLE
            return
        }

        val sellerProductsRef = database.getReference("Sellers/$sellerUID/products")
        sellerProductsRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                productList.clear()

                if (!snapshot.exists()) {
                    emptyTextView.text = "No products added yet"
                    emptyTextView.visibility = View.VISIBLE
                    adapter.notifyDataSetChanged()
                    return
                }

                emptyTextView.visibility = View.GONE
                for (productSnapshot in snapshot.children) {
                    val product = productSnapshot.getValue(Product::class.java)
                    product?.let { productList.add(it) }
                }
                adapter.notifyDataSetChanged()
            }

            override fun onCancelled(error: DatabaseError) {
                emptyTextView.text = "Failed to load products"
                emptyTextView.visibility = View.VISIBLE
            }
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_EDIT_PRODUCT && resultCode == Activity.RESULT_OK) {
            // Reload product list to reflect edits
            loadProducts()
        }
    }
}
