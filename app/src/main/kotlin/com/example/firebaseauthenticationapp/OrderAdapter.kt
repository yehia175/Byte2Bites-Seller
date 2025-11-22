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

        holder.buyerName.text = "Order (Name: ${order.buyerName}):"
        holder.deliveryTypeText.text = "Type: ${order.deliveryType.uppercase()}"

        holder.itemsRecyclerView.layoutManager = LinearLayoutManager(context)
        holder.itemsRecyclerView.adapter = OrderItemAdapter(order.items)
        holder.itemsRecyclerView.isNestedScrollingEnabled = false

        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        // ==========================
        //     LOAD ACCEPT STATE
        // ==========================
        val isAccepted = prefs.getBoolean("accepted_${order.orderId}", false)
        if (isAccepted) {
            holder.btnAcceptOrder.isEnabled = false
            holder.btnAcceptOrder.text = "Accepted"
            holder.btnAcceptOrder.setBackgroundColor(context.getColor(android.R.color.darker_gray))
            holder.btnAcceptOrder.setTextColor(context.getColor(android.R.color.white))

            holder.btnRejectOrder.isEnabled = false
            holder.btnRejectOrder.setBackgroundColor(context.getColor(android.R.color.darker_gray))
            holder.btnRejectOrder.setTextColor(context.getColor(android.R.color.white))
        } else {
            holder.btnAcceptOrder.isEnabled = true
            holder.btnAcceptOrder.text = "Accept Order"
            holder.btnAcceptOrder.setBackgroundColor(context.getColor(android.R.color.holo_green_dark))
            holder.btnAcceptOrder.setTextColor(context.getColor(android.R.color.white))

            holder.btnRejectOrder.isEnabled = true
            holder.btnRejectOrder.setBackgroundColor(context.getColor(android.R.color.holo_red_dark))
            holder.btnRejectOrder.setTextColor(context.getColor(android.R.color.white))
        }

        // ==========================
        //   LOAD COMPLETED STATE
        // ==========================
        val isCompleted = prefs.getBoolean("completed_${order.orderId}", false)
        if (isCompleted) {
            holder.btnFinishedPreparing.isEnabled = false
            holder.btnFinishedPreparing.text = "Done"
            holder.btnFinishedPreparing.setBackgroundColor(context.getColor(android.R.color.darker_gray))
            holder.btnFinishedPreparing.setTextColor(context.getColor(android.R.color.white))
        } else {
            holder.btnFinishedPreparing.isEnabled = true
            holder.btnFinishedPreparing.text = "Order Ready"
            holder.btnFinishedPreparing.setBackgroundColor(context.getColor(android.R.color.holo_blue_dark))
            holder.btnFinishedPreparing.setTextColor(context.getColor(android.R.color.white))
        }

        // ==========================
        //       ACCEPT ORDER
        // ==========================
        holder.btnAcceptOrder.setOnClickListener {
            checkAndAcceptOrder(order, holder, prefs)
        }

        // ==========================
        //     FINISHED PREPARING
        // ==========================
        holder.btnFinishedPreparing.setOnClickListener {
            val isCurrentlyAccepted = prefs.getBoolean("accepted_${order.orderId}", false)
            if (!isCurrentlyAccepted) {
                AlertDialog.Builder(context)
                    .setTitle("Action not allowed")
                    .setMessage("You can't finish preparing an order that isn't accepted yet")
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }

            finishPreparing(order, holder)
            prefs.edit().putBoolean("completed_${order.orderId}", true).apply()

            holder.btnFinishedPreparing.isEnabled = false
            holder.btnFinishedPreparing.text = "Done"
            holder.btnFinishedPreparing.setBackgroundColor(context.getColor(android.R.color.darker_gray))
            holder.btnFinishedPreparing.setTextColor(context.getColor(android.R.color.white))
        }

        // ==========================
        //      REJECT ORDER
        // ==========================
        holder.btnRejectOrder.setOnClickListener {
            ordersList.removeAt(position)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, ordersList.size)

            val orderId = order.orderId
            val sellerUid = order.sellerUid
            if (orderId.isNotEmpty() && sellerUid.isNotEmpty()) {
                db.child("Sellers").child(sellerUid).child("orders")
                    .child(orderId).child("status").setValue("REJECTED")
            }

            sendNotification(
                context,
                "Order Rejected",
                "Order from ${order.buyerName} was rejected",
                order.orderId.hashCode()
            )
        }

        // ==========================
        //         VOIP CALL
        // ==========================
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

    // ==========================
    // CHECK STOCK AND ACCEPT
    // ==========================
    private fun checkAndAcceptOrder(order: OrderDisplay, holder: OrderViewHolder, prefs: android.content.SharedPreferences) {
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
                val currentQty =
                    snap.child("quantity").getValue(String::class.java)?.toIntOrNull() ?: 0

                if (currentQty - item.quantity < 0) {
                    insufficientItems.add("${item.name} (Stock: $currentQty)")
                }

                checksDone++
                if (checksDone == order.items.size) {
                    if (insufficientItems.isNotEmpty()) {
                        // ALERT only, do NOT change buttons or SharedPreferences
                        AlertDialog.Builder(holder.itemView.context)
                            .setTitle("Cannot Accept Order")
                            .setMessage(
                                "Insufficient stock for:\n" +
                                        insufficientItems.joinToString("\n")
                            )
                            .setPositiveButton("OK", null)
                            .show()
                        return@addOnSuccessListener
                    }

                    // Stock sufficient → proceed as normal
                    db.child("Sellers").child(sellerUid).child("orders")
                        .child(orderId).child("status").setValue("PREPARING")

                    updateProductQuantities(order.items, sellerUid)

                    prefs.edit().putBoolean("accepted_${order.orderId}", true).apply()

                    holder.btnAcceptOrder.isEnabled = false
                    holder.btnAcceptOrder.text = "Accepted"
                    holder.btnAcceptOrder.setBackgroundColor(holder.itemView.context.getColor(android.R.color.darker_gray))
                    holder.btnAcceptOrder.setTextColor(holder.itemView.context.getColor(android.R.color.white))

                    holder.btnRejectOrder.isEnabled = false
                    holder.btnRejectOrder.setBackgroundColor(holder.itemView.context.getColor(android.R.color.darker_gray))
                    holder.btnRejectOrder.setTextColor(holder.itemView.context.getColor(android.R.color.white))

                    sendNotification(
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
    //    FINISHED PREPARING
    // ==========================
    private fun finishPreparing(order: OrderDisplay, holder: OrderViewHolder) {
        val orderId = order.orderId
        val sellerUid = order.sellerUid
        if (orderId.isEmpty()) return

        val newStatus = if (order.deliveryType.uppercase() == "DELIVERY")
            "READY FOR DELIVERING"
        else
            "READY FOR PICKUP"

        db.child("Sellers").child(sellerUid).child("orders")
            .child(orderId).child("status").setValue(newStatus)

        sendNotification(
            holder.itemView.context,
            "Order Ready",
            "Order from ${order.buyerName} is $newStatus",
            orderId.hashCode()
        )
    }

    // ==========================
    //   UPDATE PRODUCT STOCK
    // ==========================
    private fun updateProductQuantities(orderItems: List<OrderItem>, sellerUid: String) {
        for (item in orderItems) {
            val qtyRef = db.child("Sellers").child(sellerUid)
                .child("products")
                .child(item.productID)
                .child("quantity")

            qtyRef.get().addOnSuccessListener { snap ->
                val currentQty = snap.getValue(String::class.java)?.toIntOrNull() ?: 0
                val newQty = (currentQty - item.quantity).coerceAtLeast(0)
                qtyRef.setValue(newQty.toString())
            }
        }
    }

    // ==========================
    //    SEND NOTIFICATION
    // ==========================
    private fun sendNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int
    ) {
        if (context is OrdersActivity) {
            context.showOrderNotification(title, message, notificationId)
        }
    }
}
