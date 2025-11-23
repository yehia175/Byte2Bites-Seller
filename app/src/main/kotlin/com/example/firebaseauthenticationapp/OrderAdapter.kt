package com.example.firebaseauthenticationapp

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.FirebaseDatabase

class OrderAdapter(
    private var ordersList: MutableList<OrderDisplay>
) : RecyclerView.Adapter<OrderAdapter.OrderViewHolder>() {

    private val db = FirebaseDatabase.getInstance().reference
    private val PREF_NAME = "accepted_orders_pref"

    class OrderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val buyerName: TextView = itemView.findViewById(R.id.textBuyerName)
        val deliveryTypeText: TextView = itemView.findViewById(R.id.textDeliveryType)
        val deliveryPriceText: TextView = itemView.findViewById(R.id.textDeliveryPrice)
        val totalPriceText: TextView = itemView.findViewById(R.id.textTotalPrice)
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
        val context = holder.itemView.context

        // Display buyer info
        holder.buyerName.text = "Order (Name: ${order.buyerName}):"
        holder.deliveryTypeText.text = "Type: ${order.deliveryType.uppercase()}"

        // Display items in nested RecyclerView
        holder.itemsRecyclerView.layoutManager = LinearLayoutManager(context)
        holder.itemsRecyclerView.adapter = OrderItemAdapter(order.items)
        holder.itemsRecyclerView.isNestedScrollingEnabled = false

        // Calculate total prices
        val itemsTotal = order.items.sumOf { (it.price.toDoubleOrNull() ?: 0.0) * it.quantity }
        val deliveryFee = (order.deliveryFeeCents ?: 0) / 100.0
        val totalPrice = itemsTotal + deliveryFee

        holder.deliveryPriceText.text = "Delivery: ${"%.2f".format(deliveryFee)} EGP"
        holder.totalPriceText.text = "Total: ${"%.2f".format(totalPrice)} EGP"

        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val isAccepted = prefs.getBoolean("accepted_${order.orderId}", false)
        val isCompleted = prefs.getBoolean("completed_${order.orderId}", false)

        // Configure buttons state
        holder.btnAcceptOrder.apply {
            isEnabled = !isAccepted
            text = if (isAccepted) "Accepted" else "Accept Order"
            setBackgroundColor(context.getColor(if (isAccepted) android.R.color.darker_gray else android.R.color.holo_green_dark))
        }

        holder.btnRejectOrder.apply {
            isEnabled = !isAccepted
            setBackgroundColor(context.getColor(if (isAccepted) android.R.color.darker_gray else android.R.color.holo_red_dark))
        }

        holder.btnFinishedPreparing.apply {
            isEnabled = !isCompleted
            text = if (isCompleted) "Done" else "Order Ready"
            setBackgroundColor(context.getColor(if (isCompleted) android.R.color.darker_gray else android.R.color.holo_blue_dark))
        }

        // Accept order click
        holder.btnAcceptOrder.setOnClickListener {
            acceptOrder(order, holder, prefs)
        }

        // Finish preparing click
        holder.btnFinishedPreparing.setOnClickListener {
            if (!prefs.getBoolean("accepted_${order.orderId}", false)) {
                AlertDialog.Builder(context)
                    .setTitle("Action not allowed")
                    .setMessage("You can't finish an order that isn't accepted yet")
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }
            finishPreparing(order, holder, prefs)
        }

        // Reject order click
        holder.btnRejectOrder.setOnClickListener {
            ordersList.removeAt(position)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, ordersList.size)

            db.child("Sellers").child(order.sellerUid)
                .child("orders").child(order.orderId)
                .child("status").setValue("REJECTED")

            sendNotification(context, "Order Rejected", "Order from ${order.buyerName} was rejected", order.orderId.hashCode())
        }

        // VOIP call
        holder.btnCallVoip.setOnClickListener {
            val intent = Intent(context, VoipCallActivity::class.java).apply {
                putExtra(VoipCallActivity.EXTRA_CALLEE_UID, order.buyerUid)
                putExtra("buyerName", order.buyerName)
                putExtra("orderId", order.orderId)
            }
            context.startActivity(intent)
        }
    }

    override fun getItemCount(): Int = ordersList.size

    fun updateList(newList: MutableList<OrderDisplay>) {
        ordersList = newList
        notifyDataSetChanged()
    }

    private fun acceptOrder(order: OrderDisplay, holder: OrderViewHolder, prefs: android.content.SharedPreferences) {
        val sellerUid = order.sellerUid
        val orderId = order.orderId
        val insufficientItems = mutableListOf<String>()
        var checksDone = 0

        for (item in order.items) {
            val productRef = db.child("Sellers").child(sellerUid).child("products").child(item.productID)
            productRef.get().addOnSuccessListener { snap ->
                val currentQty = snap.child("quantity").getValue(String::class.java)?.toIntOrNull() ?: 0
                if (currentQty < item.quantity) {
                    insufficientItems.add("${item.name} (Stock: $currentQty)")
                }

                checksDone++
                if (checksDone == order.items.size) {
                    if (insufficientItems.isNotEmpty()) {
                        AlertDialog.Builder(holder.itemView.context)
                            .setTitle("Cannot Accept Order")
                            .setMessage("Insufficient stock for:\n${insufficientItems.joinToString("\n")}")
                            .setPositiveButton("OK", null)
                            .show()
                        return@addOnSuccessListener
                    }

                    db.child("Sellers").child(sellerUid).child("orders").child(orderId).child("status").setValue("PREPARING")
                    prefs.edit().putBoolean("accepted_${orderId}", true).apply()

                    holder.btnAcceptOrder.isEnabled = false
                    holder.btnAcceptOrder.text = "Accepted"
                    holder.btnRejectOrder.isEnabled = false
                    holder.btnRejectOrder.setBackgroundColor(holder.itemView.context.getColor(android.R.color.darker_gray))

                    sendNotification(holder.itemView.context, "Order Accepted", "Order from ${order.buyerName} is now PREPARING", orderId.hashCode())
                }
            }
        }
    }

    private fun finishPreparing(order: OrderDisplay, holder: OrderViewHolder, prefs: android.content.SharedPreferences) {
        val sellerUid = order.sellerUid
        val orderId = order.orderId
        val newStatus = if (order.deliveryType.uppercase() == "DELIVERY") "READY FOR DELIVERING" else "READY FOR PICKUP"

        db.child("Sellers").child(sellerUid).child("orders").child(orderId).child("status").setValue(newStatus)
        prefs.edit().putBoolean("completed_${orderId}", true).apply()

        holder.btnFinishedPreparing.isEnabled = false
        holder.btnFinishedPreparing.text = "Done"
        holder.btnFinishedPreparing.setBackgroundColor(holder.itemView.context.getColor(android.R.color.darker_gray))

        sendNotification(holder.itemView.context, "Order Ready", "Order from ${order.buyerName} is $newStatus", orderId.hashCode())
    }

    private fun sendNotification(context: Context, title: String, message: String, notificationId: Int) {
        if (context is OrdersActivity) {
            context.showOrderNotification(title, message, notificationId)
        }
    }
}
