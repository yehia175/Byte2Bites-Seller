package com.example.firebaseauthenticationapp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
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

        // Home button functionality
        val homeButton = findViewById<ImageButton>(R.id.backButton) // your button ID
        homeButton.setOnClickListener {
            val intent = Intent(this, HomeActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish() // optional: closes OrdersActivity
        }

        // RecyclerView setup
        ordersRecyclerView = findViewById(R.id.ordersRecyclerView)
        ordersRecyclerView.layoutManager = LinearLayoutManager(this)//“Display all items one below the other like a normal scrolling list.”
        orderAdapter = OrderAdapter(ordersList)
        ordersRecyclerView.adapter = orderAdapter

        createNotificationChannel()
        requestNotificationPermission()
        fetchOrders()
    }

    private fun createNotificationChannel() {//Think of a notification channel as a category or group for your notifications.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "order_channel",//(used by your app to send notifications into this channel, like identifier for channel)
                "Order Notifications",
                NotificationManager.IMPORTANCE_HIGH//(makes notification appear immediately with sound & heads-up popup)
            )
            channel.description = "Notifications for new or updated orders"//Users will see this when they check app notification settings.
            val manager = getSystemService(NotificationManager::class.java)//You need this system service to register the channel.
            manager?.createNotificationChannel(channel)//"Hey, create this notification channel so we can use it."
        }
    }

    private fun requestNotificationPermission() {//Starting from Android 13 (TIRAMISU / API 33), apps must ask the user for permission to show notifications.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(//“Does the user already allow my app to send notifications?”
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED//IF NOT GRANTED DO THE FOLLOWING. IF YES, DO NOTHING
            ) {
                //shows permission dialog
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),//“I want to ask the user for the POST_NOTIFICATIONS permission.”
                    101
                )
            }
        }
    }

    //⚠ VERY IMPORTANT
    //This function does NOT show the permission popup. Permission shown above
    fun showOrderNotification(title: String, message: String, notificationId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return //the user did not give permission for notifications → STOP the function (return)

        val builder = NotificationCompat.Builder(this, "order_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)//Makes the notification appear immediately and pop up.
            .setAutoCancel(true)//Notification disappears when user taps it.

        //This is the line that actually shows the notification.
        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.notify(notificationId, builder.build())//notificationId → unique number so notifications don’t overwrite each other
    }

    private fun fetchOrders() {
        val sellerUid = auth.currentUser?.uid ?: return
        val ordersRef = db.child("Sellers").child(sellerUid).child("orders")

        ordersRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                ordersList.clear()
                for (orderSnap in snapshot.children) {//Loop through each order
                    val status = orderSnap.child("status").getValue(String::class.java) ?: ""//else, empty string
                    if (status.uppercase() == "REJECTED") continue//skip rejected orders, they should be permenantly deleted from app

                    val orderId = orderSnap.key ?: continue
                    val buyerUid = orderSnap.child("buyerUid").getValue(String::class.java) ?: continue
                    val itemsSnap = orderSnap.child("items")
                    if (!itemsSnap.exists()) continue//Skip the order if it has no items.

                    val deliveryFeeCents = orderSnap.child("deliveryFeeCents").getValue(Int::class.java) ?: 0
                    val deliveryType = orderSnap.child("deliveryType").getValue(String::class.java) ?: "PICKUP"

                    fetchBuyerName(buyerUid) { buyerName ->
                        val itemList = mutableListOf<OrderItem>()
                        for (itemSnap in itemsSnap.children) {
                            val name = itemSnap.child("name").getValue(String::class.java) ?: ""
                            val quantity = itemSnap.child("quantity").getValue(Int::class.java) ?: 0
                            val productId = itemSnap.child("productID").getValue(String::class.java) ?: ""
                            val price = itemSnap.child("price").getValue(String::class.java) ?: "0"

                            //Each child under items becomes an OrderItem object.
                            itemList.add(OrderItem(productId, name, quantity, price))
                        }

                        val orderDisplay = OrderDisplay(//This contains everything needed to show one order card.
                            orderId = orderId,
                            sellerUid = sellerUid,
                            buyerName = buyerName,
                            items = itemList,
                            buyerUid = buyerUid,
                            deliveryType = deliveryType,
                            deliveryFeeCents = deliveryFeeCents
                        )

                        ordersList.add(0, orderDisplay)//Adds at index 0 → newest order appears first.
                        orderAdapter.updateList(ordersList)//Refreshes the list so items show on screen.
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
                    callback(buyerName)//gets it back here
                }

                override fun onCancelled(error: DatabaseError) {
                    callback("Unknown Buyer")
                }
            })
    }
}
