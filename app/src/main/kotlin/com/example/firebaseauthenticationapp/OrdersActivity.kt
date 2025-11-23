package com.example.firebaseauthenticationapp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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

        val backButton = findViewById<ImageButton>(R.id.backButton)
        backButton.setOnClickListener { finish() }

        ordersRecyclerView = findViewById(R.id.ordersRecyclerView)
        ordersRecyclerView.layoutManager = LinearLayoutManager(this)
        orderAdapter = OrderAdapter(ordersList)
        ordersRecyclerView.adapter = orderAdapter

        createNotificationChannel()
        requestNotificationPermission()
        fetchOrders()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "order_channel",
                "Order Notifications",
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "Notifications for new or updated orders"
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }

    fun showOrderNotification(title: String, message: String, notificationId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val builder = NotificationCompat.Builder(this, "order_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.notify(notificationId, builder.build())
    }

    private fun fetchOrders() {
        val sellerUid = auth.currentUser?.uid ?: return
        val ordersRef = db.child("Sellers").child(sellerUid).child("orders")

        ordersRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                ordersList.clear()
                for (orderSnap in snapshot.children) {
                    val status = orderSnap.child("status").getValue(String::class.java) ?: ""
                    if (status.uppercase() == "REJECTED") continue

                    val orderId = orderSnap.key ?: continue
                    val buyerUid = orderSnap.child("buyerUid").getValue(String::class.java) ?: continue
                    val itemsSnap = orderSnap.child("items")
                    if (!itemsSnap.exists()) continue

                    // Fetch deliveryFeeCents like deliveryType
                    val deliveryFeeCents = orderSnap.child("deliveryFeeCents").getValue(Int::class.java) ?: 0
                    val deliveryType = orderSnap.child("deliveryType").getValue(String::class.java) ?: "PICKUP"

                    fetchBuyerName(buyerUid) { buyerName ->
                        val itemList = mutableListOf<OrderItem>()
                        for (itemSnap in itemsSnap.children) {
                            val name = itemSnap.child("name").getValue(String::class.java) ?: ""
                            val quantity = itemSnap.child("quantity").getValue(Int::class.java) ?: 0
                            val productId = itemSnap.child("productID").getValue(String::class.java) ?: ""
                            val price = itemSnap.child("price").getValue(String::class.java) ?: "0"

                            itemList.add(OrderItem(productId, name, quantity, price))
                        }

                        val orderDisplay = OrderDisplay(
                            orderId = orderId,
                            sellerUid = sellerUid,
                            buyerName = buyerName,
                            items = itemList,
                            buyerUid = buyerUid,
                            deliveryType = deliveryType,
                            deliveryFeeCents = deliveryFeeCents
                        )

                        ordersList.add(0, orderDisplay)
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
