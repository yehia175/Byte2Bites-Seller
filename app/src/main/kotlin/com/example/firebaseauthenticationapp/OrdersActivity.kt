package com.example.firebaseauthenticationapp

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class OrdersActivity : AppCompatActivity() {

    private lateinit var ordersRecyclerView: RecyclerView
    private lateinit var orderAdapter: OrderAdapter
    private val ordersList = mutableListOf<OrderDisplay>()

    private val db = FirebaseDatabase.getInstance().reference
    private val auth = FirebaseAuth.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_orders)

        ordersRecyclerView = findViewById(R.id.ordersRecyclerView)
        ordersRecyclerView.layoutManager = LinearLayoutManager(this)
        orderAdapter = OrderAdapter(ordersList)
        ordersRecyclerView.adapter = orderAdapter

        fetchOrders()
    }

    private fun fetchOrders() {
        val sellerUid = auth.currentUser?.uid ?: return
        val ordersRef = db.child("Sellers").child(sellerUid).child("orders")

        ordersRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                ordersList.clear()

                for (orderSnap in snapshot.children) {
                    val orderId = orderSnap.key ?: continue
                    val buyerUid = orderSnap.child("buyerUid").value?.toString() ?: continue
                    val itemsSnap = orderSnap.child("items")
                    if (!itemsSnap.exists()) continue

                    // Fetch buyer name first
                    fetchBuyerName(buyerUid) { buyerName ->

                        val itemList = mutableListOf<OrderItem>()
                        for (itemSnap in itemsSnap.children) {
                            val name = itemSnap.child("name").value?.toString() ?: ""
                            val quantity = itemSnap.child("quantity").value?.toString()?.toIntOrNull() ?: 0
                            itemList.add(OrderItem(name, quantity))
                        }

                        // Create a single OrderDisplay per order with orderId and sellerUid
                        val orderDisplay = OrderDisplay(
                            orderId = orderId,
                            sellerUid = sellerUid,
                            buyerName = buyerName,
                            items = itemList
                        )

                        ordersList.add(orderDisplay)
                        orderAdapter.updateList(ordersList)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun fetchBuyerName(uid: String, callback: (String) -> Unit) {
        db.child("Buyers").child(uid)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val buyerName = snapshot.child("fullName").getValue(String::class.java) ?: "Unknown Buyer"
                    callback(buyerName)
                }

                override fun onCancelled(error: DatabaseError) {
                    callback("Unknown Buyer")
                }
            })
    }
}
