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
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.FirebaseDatabase

class OrderAdapter(
    private var ordersList: MutableList<OrderDisplay>
) : RecyclerView.Adapter<OrderAdapter.OrderViewHolder>() {

    class OrderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val buyerName: TextView = itemView.findViewById(R.id.textBuyerName)
        val itemsRecyclerView: RecyclerView = itemView.findViewById(R.id.recyclerItems)
        val btnAcceptOrder: Button = itemView.findViewById(R.id.btnAcceptOrder)
        val btnRejectOrder: Button = itemView.findViewById(R.id.btnRejectOrder)
        val btnFinishedPreparing: Button = itemView.findViewById(R.id.btnFinishedPreparing)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_order, parent, false)
        return OrderViewHolder(view)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        val order = ordersList[position]
        holder.buyerName.text = "Order(Name: ${order.buyerName}):"

        // Nested RecyclerView for items
        holder.itemsRecyclerView.layoutManager = LinearLayoutManager(holder.itemView.context)
        holder.itemsRecyclerView.adapter = OrderItemAdapter(order.items)
        holder.itemsRecyclerView.isNestedScrollingEnabled = false

        val db = FirebaseDatabase.getInstance().reference

        // === Accept Order ===
        holder.btnAcceptOrder.setOnClickListener {
            val orderId = order.orderId ?: return@setOnClickListener
            val sellerUid = order.sellerUid ?: return@setOnClickListener

            db.child("Sellers").child(sellerUid).child("orders")
                .child(orderId).child("status").setValue("PREPARING")

            showNotification(
                holder.itemView.context,
                "Order Accepted",
                "Order from ${order.buyerName} is now PREPARING",
                orderId.hashCode()
            )
        }

        // === Reject Order ===
        holder.btnRejectOrder.setOnClickListener {
            val orderId = order.orderId ?: return@setOnClickListener
            val sellerUid = order.sellerUid ?: return@setOnClickListener

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

        // === Finished Preparing ===
        holder.btnFinishedPreparing.setOnClickListener {
            val orderId = order.orderId ?: return@setOnClickListener
            val sellerUid = order.sellerUid ?: return@setOnClickListener

            db.child("Sellers").child(sellerUid).child("orders")
                .child(orderId).child("status").setValue("READY FOR PICKUP/DELIVERING")

            showNotification(
                holder.itemView.context,
                "Order Ready",
                "Order from ${order.buyerName} is READY FOR PICKUP/DELIVERING",
                orderId.hashCode()
            )
        }
    }

    override fun getItemCount(): Int = ordersList.size

    fun updateList(newList: MutableList<OrderDisplay>) {
        ordersList = newList
        notifyDataSetChanged()
    }

    // === Notification Helper Function ===
    private fun showNotification(context: Context, title: String, message: String, notificationId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val builder = NotificationCompat.Builder(context, "order_channel")
            .setSmallIcon(R.drawable.ic_notification) // placeholder icon
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.notify(notificationId, builder.build())
    }
}
