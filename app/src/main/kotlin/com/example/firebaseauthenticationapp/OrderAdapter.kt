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

        // Display items
        holder.itemsRecyclerView.layoutManager = LinearLayoutManager(context)
        holder.itemsRecyclerView.adapter = OrderItemAdapter(order.items)
        holder.itemsRecyclerView.isNestedScrollingEnabled = false

        // Prices calculation
        val itemsTotal = order.items.sumOf { (it.price.toDoubleOrNull() ?: 0.0) * it.quantity }
        val deliveryFee = (order.deliveryFeeCents ?: 0) / 100.0
        val totalPrice = itemsTotal + deliveryFee
        holder.deliveryPriceText.text = "Delivery: ${"%.2f".format(deliveryFee)} EGP"
        holder.totalPriceText.text = "Total: ${"%.2f".format(totalPrice)} EGP"

        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val isAccepted = prefs.getBoolean("accepted_${order.orderId}", false)
        val firstReadyClick = prefs.getBoolean("first_ready_${order.orderId}", false)
        val isCompleted = prefs.getBoolean("completed_${order.orderId}", false)

        // ACCEPT BUTTON UI
        holder.btnAcceptOrder.apply {
            isEnabled = !isAccepted
            text = if (isAccepted) "Accepted" else "Accept Order"
            setBackgroundColor(
                context.getColor(
                    if (isAccepted) android.R.color.darker_gray else android.R.color.holo_green_dark
                )
            )
        }

        // REJECT BUTTON UI
        holder.btnRejectOrder.apply {
            isEnabled = !isAccepted
            setBackgroundColor(
                context.getColor(
                    if (isAccepted) android.R.color.darker_gray else android.R.color.holo_red_dark
                )
            )
        }

        // READY BUTTON UI
        holder.btnFinishedPreparing.apply {
            when {
                isCompleted -> { // Completed already
                    isEnabled = false
                    text = "Done"
                    setBackgroundColor(context.getColor(android.R.color.darker_gray))
                }
                // For PICKUP: use firstReadyClick to show middle state
                firstReadyClick && order.deliveryType.uppercase() == "PICKUP" -> {
                    isEnabled = true
                    text = "Done"
                    setBackgroundColor(context.getColor(android.R.color.holo_blue_dark))
                }
                // For DELIVERY and first click (if sellerDeliver yes) we rely on runtime read in click handler
                else -> { // initial state
                    isEnabled = true
                    text = "Order Ready"
                    setBackgroundColor(context.getColor(android.R.color.holo_blue_dark))
                }
            }
        }

        // Accept Click
        holder.btnAcceptOrder.setOnClickListener {
            acceptOrderWithAlerts(order, holder, prefs)
        }

        // READY BUTTON CLICK LOGIC
        holder.btnFinishedPreparing.setOnClickListener {
            // Prevent finishing before accept
            if (!prefs.getBoolean("accepted_${order.orderId}", false)) {
                AlertDialog.Builder(context)
                    .setTitle("Action not allowed")
                    .setMessage("You can't finish an order that isn't accepted yet.")
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }

            // If DELIVERY: dynamic behavior based on sellerDeliver stored in Firebase
            if (order.deliveryType.uppercase() == "DELIVERY") {

                // Read sellerDeliver from Firebase for this order
                db.child("Sellers").child(order.sellerUid)
                    .child("orders").child(order.orderId)
                    .child("sellerDeliver").get().addOnSuccessListener { snap ->
                        val sellerDeliver = snap.getValue(String::class.java)?.uppercase() ?: "NO"

                        if (sellerDeliver == "YES") {
                            // Seller will deliver -> two-click behavior for DELIVERY
                            val alreadyClickedOnceForDelivery = prefs.getBoolean("first_ready_${order.orderId}", false)
                            val alreadyCompleted = prefs.getBoolean("completed_${order.orderId}", false)

                            if (alreadyCompleted) {
                                // already done — ensure disabled
                                holder.btnFinishedPreparing.isEnabled = false
                                holder.btnFinishedPreparing.text = "Done"
                                holder.btnFinishedPreparing.setBackgroundColor(context.getColor(android.R.color.darker_gray))
                                return@addOnSuccessListener
                            }

                            if (!alreadyClickedOnceForDelivery) {
                                // First delivery click -> change to Delivering
                                db.child("Sellers").child(order.sellerUid)
                                    .child("orders").child(order.orderId)
                                    .child("status").setValue("DELIVERING")

                                prefs.edit().putBoolean("first_ready_${order.orderId}", true).apply()

                                holder.btnFinishedPreparing.text = "Delivering"
                                holder.btnFinishedPreparing.isEnabled = true
                                holder.btnFinishedPreparing.setBackgroundColor(context.getColor(android.R.color.holo_blue_dark))

                                sendNotification(context,
                                    "Order Delivering",
                                    "Order from ${order.buyerName} is DELIVERING",
                                    order.orderId.hashCode())
                            } else {
                                // Second delivery click -> Delivered & disable
                                db.child("Sellers").child(order.sellerUid)
                                    .child("orders").child(order.orderId)
                                    .child("status").setValue("DELIVERED")

                                prefs.edit().putBoolean("completed_${order.orderId}", true).apply()

                                holder.btnFinishedPreparing.text = "Delivered"
                                holder.btnFinishedPreparing.isEnabled = false
                                holder.btnFinishedPreparing.setBackgroundColor(context.getColor(android.R.color.darker_gray))

                                sendNotification(context,
                                    "Order Delivered",
                                    "Order from ${order.buyerName} is DELIVERED",
                                    order.orderId.hashCode())
                            }
                        } else {
                            // sellerDeliver == "NO" or missing -> single-click behavior
                            db.child("Sellers").child(order.sellerUid)
                                .child("orders").child(order.orderId)
                                .child("status").setValue("WAITING FOR DELIVERY")

                            prefs.edit().putBoolean("completed_${order.orderId}", true).apply()

                            holder.btnFinishedPreparing.text = "Done"
                            holder.btnFinishedPreparing.isEnabled = false
                            holder.btnFinishedPreparing.setBackgroundColor(context.getColor(android.R.color.darker_gray))

                            sendNotification(context,
                                "Order Waiting",
                                "Order from ${order.buyerName} is WAITING FOR DELIVERY",
                                order.orderId.hashCode())
                        }
                    }.addOnFailureListener {
                        // If read fails, fallback to single-click behavior (safe)
                        db.child("Sellers").child(order.sellerUid)
                            .child("orders").child(order.orderId)
                            .child("status").setValue("WAITING FOR DELIVERY")

                        prefs.edit().putBoolean("completed_${order.orderId}", true).apply()

                        holder.btnFinishedPreparing.text = "Done"
                        holder.btnFinishedPreparing.isEnabled = false
                        holder.btnFinishedPreparing.setBackgroundColor(context.getColor(android.R.color.darker_gray))

                        sendNotification(context,
                            "Order Waiting",
                            "Order from ${order.buyerName} is WAITING FOR DELIVERY",
                            order.orderId.hashCode())
                    }

            } else {
                // PICKUP -> keep existing two-click logic (unchanged)
                val alreadyClickedOnce = prefs.getBoolean("first_ready_${order.orderId}", false)
                if (!alreadyClickedOnce) {
                    performFirstReadyClick(order, holder, prefs)
                } else {
                    performSecondReadyClick(order, holder, prefs)
                }
            }
        }

        // Reject
        holder.btnRejectOrder.setOnClickListener {
            ordersList.removeAt(position)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, ordersList.size)

            db.child("Sellers").child(order.sellerUid)
                .child("orders").child(order.orderId)
                .child("status").setValue("REJECTED")

            sendNotification(
                context, "Order Rejected",
                "Order from ${order.buyerName} was rejected", order.orderId.hashCode()
            )
        }

        // VOIP
        holder.btnCallVoip.setOnClickListener {
            val intent = Intent(context, VoipCallActivity::class.java).apply {
                putExtra(VoipCallActivity.EXTRA_CALLEE_UID, order.buyerUid)
                putExtra("buyerName", order.buyerName)
                putExtra("orderId", order.orderId)
            }
            context.startActivity(intent)
        }
    }

    // =====================================================================
    // ACCEPT ORDER — stock logic + POST-ACCEPT sellerDeliver alert
    // =====================================================================
    private fun acceptOrderWithAlerts(order: OrderDisplay, holder: OrderViewHolder, prefs: android.content.SharedPreferences) {
        val sellerUid = order.sellerUid
        val orderId = order.orderId
        val insufficientItems = mutableListOf<String>()
        val zeroStockItems = mutableListOf<String>()
        var checksDone = 0

        // 1️⃣ Pre-accept check — old alert
        for (item in order.items) {
            val prodRef = db.child("Sellers").child(sellerUid).child("products").child(item.productID)
            prodRef.get().addOnSuccessListener { snap ->
                val availableQty = snap.child("quantity").getValue(String::class.java)?.toIntOrNull() ?: 0
                if (item.quantity > availableQty) {
                    insufficientItems.add("${item.name} (Stock: $availableQty)")
                }

                checksDone++
                if (checksDone == order.items.size) {
                    if (insufficientItems.isNotEmpty()) {
                        // OLD ALERT → do not accept
                        AlertDialog.Builder(holder.itemView.context)
                            .setTitle("Cannot Accept Order")
                            .setMessage("Insufficient stock for:\n${insufficientItems.joinToString("\n")}")
                            .setPositiveButton("OK", null)
                            .show()
                        return@addOnSuccessListener
                    }

                    // ✅ Accept order
                    db.child("Sellers").child(sellerUid).child("orders")
                        .child(orderId).child("status").setValue("PREPARING")

                    prefs.edit().putBoolean("accepted_${orderId}", true).apply()

                    // Decrease stock + collect zero-stock items
                    val totalItems = order.items.size
                    var updatedItems = 0

                    for (orderedItem in order.items) {
                        val pRef = db.child("Sellers").child(sellerUid)
                            .child("products").child(orderedItem.productID)

                        pRef.get().addOnSuccessListener { pSnap ->
                            val currentQty = pSnap.child("quantity").getValue(String::class.java)?.toIntOrNull() ?: 0
                            val newQty = currentQty - orderedItem.quantity
                            pRef.child("quantity").setValue(newQty.toString())

                            if (newQty == 0) {
                                zeroStockItems.add(orderedItem.name)
                            }

                            updatedItems++
                            // Show post-accept zero stock alert after all items processed
                            if (updatedItems == totalItems && zeroStockItems.isNotEmpty()) {
                                AlertDialog.Builder(holder.itemView.context)
                                    .setTitle("Stock Alert")
                                    .setMessage("The following items reached 0 stock:\n${zeroStockItems.joinToString("\n")}")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                    }

                    // UI updates
                    holder.btnAcceptOrder.isEnabled = false
                    holder.btnAcceptOrder.text = "Accepted"
                    holder.btnRejectOrder.isEnabled = false
                    holder.btnRejectOrder.setBackgroundColor(holder.itemView.context.getColor(android.R.color.darker_gray))

                    sendNotification(
                        holder.itemView.context,
                        "Order Accepted",
                        "Order from ${order.buyerName} is now PREPARING",
                        orderId.hashCode()
                    )

                    // ----------------------------------------------------------------
                    // NEW ALERT: "Will seller deliver this order?" (Yes / No)
                    // Write sellerDeliver = "Yes" or "No" under the order node.
                    // ----------------------------------------------------------------
                    if (order.deliveryType.uppercase() == "DELIVERY") {
                        AlertDialog.Builder(holder.itemView.context)
                            .setTitle("Will seller deliver this order?")
                            .setPositiveButton("Yes") { _, _ ->
                                db.child("Sellers").child(sellerUid)
                                    .child("orders").child(orderId)
                                    .child("sellerDeliver").setValue("Yes")
                            }
                            .setNegativeButton("No") { _, _ ->
                                db.child("Sellers").child(sellerUid)
                                    .child("orders").child(orderId)
                                    .child("sellerDeliver").setValue("No")
                            }
                            .setCancelable(false)
                            .show()
                    }
                }
            }
        }
    }

    // =====================================================================
    // PICKUP FIRST CLICK
    // =====================================================================
    private fun performFirstReadyClick(order: OrderDisplay, holder: OrderViewHolder, prefs: android.content.SharedPreferences) {
        val sellerUid = order.sellerUid
        val orderId = order.orderId

        db.child("Sellers").child(sellerUid)
            .child("orders").child(orderId)
            .child("status").setValue("READY FOR PICKUP")

        prefs.edit().putBoolean("first_ready_${orderId}", true).apply()

        holder.btnFinishedPreparing.text = "Done"
        holder.btnFinishedPreparing.isEnabled = true
        holder.btnFinishedPreparing.setBackgroundColor(holder.itemView.context.getColor(android.R.color.holo_blue_dark))

        sendNotification(holder.itemView.context, "Order Ready",
            "Order from ${order.buyerName} is READY FOR PICKUP", orderId.hashCode())
    }

    // =====================================================================
    // PICKUP SECOND CLICK
    // =====================================================================
    private fun performSecondReadyClick(order: OrderDisplay, holder: OrderViewHolder, prefs: android.content.SharedPreferences) {
        val sellerUid = order.sellerUid
        val orderId = order.orderId

        db.child("Sellers").child(sellerUid)
            .child("orders").child(orderId)
            .child("status").setValue("ORDER COMPLETE")

        prefs.edit().putBoolean("completed_${orderId}", true).apply()

        holder.btnFinishedPreparing.isEnabled = false
        holder.btnFinishedPreparing.text = "Done"
        holder.btnFinishedPreparing.setBackgroundColor(holder.itemView.context.getColor(android.R.color.darker_gray))

        sendNotification(holder.itemView.context,
            "Order Complete",
            "Order from ${order.buyerName} is completed.",
            orderId.hashCode())
    }

    private fun sendNotification(context: Context, title: String, message: String, notificationId: Int) {
        if (context is OrdersActivity) {
            context.showOrderNotification(title, message, notificationId)
        }
    }

    override fun getItemCount(): Int = ordersList.size

    fun updateList(newList: MutableList<OrderDisplay>) {
        ordersList = newList
        notifyDataSetChanged()
    }
}
