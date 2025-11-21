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

        // Buyer name
        holder.buyerName.text = "Order (Name: ${order.buyerName}):"

        // Delivery type
        holder.deliveryTypeText.text = "Type: ${order.deliveryType.uppercase()}"

        // Nested RecyclerView for order items
        holder.itemsRecyclerView.layoutManager = LinearLayoutManager(holder.itemView.context)
        holder.itemsRecyclerView.adapter = OrderItemAdapter(order.items)
        holder.itemsRecyclerView.isNestedScrollingEnabled = false

        // ==========================
        //           VOIP CALL
        // ==========================
        holder.btnCallVoip.setOnClickListener {
            val context = holder.itemView.context
            val intent = Intent(context, VoipCallActivity::class.java).apply {
                // 👇 This is the important change: use the same constant
                // your VoipCallActivity uses (EXTRA_CALLEE_UID)
                putExtra(VoipCallActivity.EXTRA_CALLEE_UID, order.buyerUid)

                // Optional extras if you want them in the call UI
                putExtra("buyerName", order.buyerName)
                putExtra("orderId", order.orderId)
            }
            context.startActivity(intent)
        }

        // ==========================
        //   ACCEPT ORDER BUTTON UI
        // ==========================
        if (orderStatusIsAccepted(order)) {
            holder.btnAcceptOrder.isEnabled = false
            holder.btnAcceptOrder.text = "Accepted"
            holder.btnAcceptOrder.setBackgroundColor(
                holder.itemView.context.getColor(android.R.color.darker_gray)
            )
            holder.btnAcceptOrder.setTextColor(
                holder.itemView.context.getColor(android.R.color.white)
            )
        } else {
            holder.btnAcceptOrder.isEnabled = true
            holder.btnAcceptOrder.text = "Accept Order"
            holder.btnAcceptOrder.setBackgroundColor(
                holder.itemView.context.getColor(android.R.color.holo_green_dark)
            )
            holder.btnAcceptOrder.setTextColor(
                holder.itemView.context.getColor(android.R.color.white)
            )
        }

        // Accept Order logic
        holder.btnAcceptOrder.setOnClickListener {
            acceptOrder(order, holder, position)

            holder.btnAcceptOrder.isEnabled = false
            holder.btnAcceptOrder.text = "Accepted"
            holder.btnAcceptOrder.setBackgroundColor(
                holder.itemView.context.getColor(android.R.color.darker_gray)
            )
            holder.btnAcceptOrder.setTextColor(
                holder.itemView.context.getColor(android.R.color.white)
            )
        }

        // Reject Order
        holder.btnRejectOrder.setOnClickListener {
            rejectOrder(order, holder, position)
        }

        // Finished Preparing
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
    //   Helper: is order accepted?
    // ==========================
    private fun orderStatusIsAccepted(order: OrderDisplay): Boolean {
        // If you store order status in Firebase, you can check here.
        // For now, assume "PREPARING" means accepted.
        return order.status == "PREPARING"
    }

    // ==========================
    //       ACCEPT ORDER
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
                val currentQty =
                    snap.child("quantity").getValue(String::class.java)?.toIntOrNull() ?: 0
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

                    // Notification (via Activity)
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
    //          REJECT ORDER
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

        sendNotification(
            holder.itemView.context,
            "Order Rejected",
            "Order from ${order.buyerName} was rejected",
            orderId.hashCode()
        )
    }

    // ==========================
    //      FINISHED PREPARING
    // ==========================
    private fun finishPreparing(order: OrderDisplay, holder: OrderViewHolder) {
        val orderId = order.orderId
        val sellerUid = order.sellerUid
        if (orderId.isEmpty() || sellerUid.isEmpty()) return

        val deliveryMessage = if (order.deliveryType.uppercase() == "DELIVERY") {
            "READY FOR DELIVERING"
        } else {
            "READY FOR PICKUP"
        }

        db.child("Sellers").child(sellerUid).child("orders")
            .child(orderId).child("status")
            .setValue(deliveryMessage)

        sendNotification(
            holder.itemView.context,
            "Order Ready",
            "Order from ${order.buyerName} is $deliveryMessage",
            orderId.hashCode()
        )
    }

    // ==========================
    //   UPDATE PRODUCT QUANTITY
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
    //   SEND NOTIFICATION VIA ACTIVITY
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
