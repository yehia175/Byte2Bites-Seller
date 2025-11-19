package com.example.firebaseauthenticationapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.FirebaseDatabase
import android.content.Intent

class OrderAdapter(
    private var ordersList: MutableList<OrderDisplay>
) : RecyclerView.Adapter<OrderAdapter.OrderViewHolder>() {

    private val db = FirebaseDatabase.getInstance().reference

    class OrderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val buyerName: TextView = itemView.findViewById(R.id.textBuyerName)
        val itemsRecyclerView: RecyclerView = itemView.findViewById(R.id.recyclerItems)
        val btnAcceptOrder: Button = itemView.findViewById(R.id.btnAcceptOrder)
        val btnRejectOrder: Button = itemView.findViewById(R.id.btnRejectOrder)
        val btnFinishedPreparing: Button = itemView.findViewById(R.id.btnFinishedPreparing)
        val btnCallVoip: Button = itemView.findViewById(R.id.btnCallVoip)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_order, parent, false)
        return OrderViewHolder(view)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        val order = ordersList[position]
        holder.buyerName.text = "Order(Name: ${order.buyerName}):"

        // Nested RecyclerView for order items
        holder.itemsRecyclerView.layoutManager = LinearLayoutManager(holder.itemView.context)
        holder.itemsRecyclerView.adapter = OrderItemAdapter(order.items)
        holder.itemsRecyclerView.isNestedScrollingEnabled = false

        // ==========================
        //        VOIP CALL
        // ==========================
        holder.btnCallVoip.setOnClickListener {
            val context = holder.itemView.context
            val intent = Intent(context, VoipCallActivity::class.java)
            intent.putExtra("buyerName", order.buyerName)
            intent.putExtra("buyerUid", order.buyerUid)
            intent.putExtra("orderId", order.orderId)
            context.startActivity(intent)
        }

        // ==========================
        //        ACCEPT ORDER
        // ==========================
        holder.btnAcceptOrder.setOnClickListener {
            acceptOrder(order, holder, position)
        }

        // ==========================
        //       REJECT ORDER
        // ==========================
        holder.btnRejectOrder.setOnClickListener {
            rejectOrder(order, holder, position)
        }

        // ==========================
        //      FINISHED PREPARING
        // ==========================
        holder.btnFinishedPreparing.setOnClickListener {
            finishPreparing(order, holder)
        }
    }

    override fun getItemCount(): Int = ordersList.size

    fun updateList(newList: MutableList<OrderDisplay>) {
        ordersList = newList
        notifyDataSetChanged()
    }

    // ==========================
    //      ACCEPT ORDER LOGIC
    // ==========================
    private fun acceptOrder(order: OrderDisplay, holder: OrderViewHolder, position: Int) {
        val orderId = order.orderId
        val sellerUid = order.sellerUid
        if (orderId.isEmpty() || sellerUid.isEmpty()) return

        var checksDone = 0
        val insufficientItems = mutableListOf<String>()

        for (item in order.items) {
            val productRef = db.child("Sellers")
                .child(sellerUid)
                .child("products")
                .child(item.productID)

            productRef.get().addOnSuccessListener { snap ->
                val currentQty = snap.child("quantity").getValue(String::class.java)?.toIntOrNull() ?: 0
                if (currentQty - item.quantity < 0) {
                    insufficientItems.add("${item.name} (Stock: $currentQty)")
                }

                checksDone++
                if (checksDone == order.items.size) {
                    if (insufficientItems.isNotEmpty()) {
                        AlertDialog.Builder(holder.itemView.context)
                            .setTitle("Cannot Accept Order")
                            .setMessage(
                                "Insufficient stock for:\n" +
                                        insufficientItems.joinToString("\n") +
                                        "\n\nOrder rejected."
                            )
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setPositiveButton("OK", null)
                            .show()
                        return@addOnSuccessListener
                    }

                    // Update order status to PREPARING
                    db.child("Sellers").child(sellerUid).child("orders")
                        .child(orderId).child("status").setValue("PREPARING")

                    // Update product quantities
                    updateProductQuantities(order.items, sellerUid)

                    // Notification
                    showNotification(
                        holder.itemView.context,
                        "Order Accepted",
                        "Order from ${order.buyerName} is now PREPARING",
                        orderId.hashCode()
                    )
                }
            }
        }
    }

    // ==========================
    //      REJECT ORDER LOGIC
    // ==========================
    private fun rejectOrder(order: OrderDisplay, holder: OrderViewHolder, position: Int) {
        val orderId = order.orderId
        val sellerUid = order.sellerUid
        if (orderId.isEmpty() || sellerUid.isEmpty()) return

        db.child("Sellers").child(sellerUid).child("orders")
            .child(orderId).removeValue()

        ordersList.removeAt(position)
        notifyItemRemoved(position)
        notifyItemRangeChanged(position, ordersList.size)

        showNotification(
            holder.itemView.context,
            "Order Rejected",
            "Order from ${order.buyerName} was rejected",
            orderId.hashCode()
        )
    }

    // ==========================
    //      FINISHED PREPARING LOGIC
    // ==========================
    private fun finishPreparing(order: OrderDisplay, holder: OrderViewHolder) {
        val orderId = order.orderId
        val sellerUid = order.sellerUid
        if (orderId.isEmpty() || sellerUid.isEmpty()) return

        // Update status in Firebase
        db.child("Sellers").child(sellerUid).child("orders")
            .child(orderId).child("status")
            .setValue("READY")

        // Notification message based on deliveryType
        val deliveryMessage = if (order.deliveryType.uppercase() == "DELIVERY") {
            "READY FOR DELIVERING"
        } else {
            "READY FOR PICKUP"
        }

        showNotification(
            holder.itemView.context,
            "Order Ready",
            "Order from ${order.buyerName} is $deliveryMessage",
            orderId.hashCode()
        )
    }

    // ==========================
    //    UPDATE PRODUCT QUANTITY
    // ==========================
    private fun updateProductQuantities(orderItems: List<OrderItem>, sellerUid: String) {
        for (item in orderItems) {
            val qtyRef = db.child("Sellers").child(sellerUid)
                .child("products").child(item.productID).child("quantity")

            qtyRef.get().addOnSuccessListener { snap ->
                val currentQty = snap.getValue(String::class.java)?.toIntOrNull() ?: 0
                val newQty = (currentQty - item.quantity).coerceAtLeast(0)
                qtyRef.setValue(newQty.toString())
            }
        }
    }

    // ==========================
    //    SHOW NOTIFICATION
    // ==========================
    private fun showNotification(context: Context, title: String, message: String, notificationId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val builder = NotificationCompat.Builder(context, "order_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.notify(notificationId, builder.build())
    }
}
