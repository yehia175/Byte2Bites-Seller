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
        holder.buyerName.text = "Order (Name: ${order.buyerName}):"

        // Nested RecyclerView for order items
        holder.itemsRecyclerView.layoutManager = LinearLayoutManager(holder.itemView.context)
        holder.itemsRecyclerView.adapter = OrderItemAdapter(order.items)
        holder.itemsRecyclerView.isNestedScrollingEnabled = false

        // ==========================
        //        VOIP CALL
        // ==========================
        holder.btnCallVoip.setOnClickListener {
            val context = holder.itemView.context
            val intent = Intent(context, VoipCallActivity::class.java).apply {
                // Auto-fill buyer UID in VoipCallActivity
                putExtra(VoipCallActivity.EXTRA_CALLEE_UID, order.buyerUid)

                // If in the future you store buyer IP/port in the order (e.g. order.buyerIp, buyerPort)
                // you can also pass them like this:
                // putExtra(VoipCallActivity.EXTRA_REMOTE_IP, order.buyerIp)
                // putExtra(VoipCallActivity.EXTRA_REMOTE_PORT, order.buyerPort)
            }
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
                val currentQty =
                    snap.child("quantity").getValue(String::class.java)?.toIntOrNull() ?: 0
                if (currentQty - item.quantity < 0) {
                    insufficientItems.add("${item.name} (Stock: $currentQty)")
                }

                checksDone++
                if (checksDone == order.items.size) {
                    if (insufficientItems.isNotEmpty()) {
                        showInsufficientDialog(holder.itemView.context, insufficientItems)
                    } else {
                        updateStockAndAcceptOrder(order, holder.itemView.context, position)
                    }
                }
            }
        }
    }

    private fun showInsufficientDialog(context: Context, insufficientItems: List<String>) {
        AlertDialog.Builder(context)
            .setTitle("Insufficient Stock")
            .setMessage(
                "The following items have insufficient stock:\n\n" +
                        insufficientItems.joinToString("\n")
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun updateStockAndAcceptOrder(
        order: OrderDisplay,
        context: Context,
        position: Int
    ) {
        val orderId = order.orderId
        val sellerUid = order.sellerUid

        for (item in order.items) {
            val productRef = db.child("Sellers")
                .child(sellerUid)
                .child("products")
                .child(item.productID)

            productRef.get().addOnSuccessListener { snap ->
                val currentQty =
                    snap.child("quantity").getValue(String::class.java)?.toIntOrNull() ?: 0
                val newQty = (currentQty - item.quantity).coerceAtLeast(0)

                productRef.child("quantity").setValue(newQty.toString())
            }
        }

        // Update the order status in the database
        db.child("Sellers").child(sellerUid).child("orders").child(orderId)
            .child("status").setValue("ACCEPTED")
            .addOnSuccessListener {
                notifyItemChanged(position)
            }
    }

    // ==========================
    //      REJECT ORDER LOGIC
    // ==========================
    private fun rejectOrder(order: OrderDisplay, holder: OrderViewHolder, position: Int) {
        val context = holder.itemView.context
        AlertDialog.Builder(context)
            .setTitle("Reject Order")
            .setMessage("Are you sure you want to reject this order?")
            .setPositiveButton("Reject") { _, _ ->
                val orderId = order.orderId
                val sellerUid = order.sellerUid
                if (orderId.isEmpty() || sellerUid.isEmpty()) return@setPositiveButton

                db.child("Sellers").child(sellerUid).child("orders").child(orderId)
                    .child("status").setValue("REJECTED")
                    .addOnSuccessListener {
                        ordersList.removeAt(position)
                        notifyItemRemoved(position)
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ==========================
    //   FINISHED PREPARING LOGIC
    // ==========================
    private fun finishPreparing(order: OrderDisplay, holder: OrderViewHolder) {
        val context = holder.itemView.context
        val orderId = order.orderId
        val sellerUid = order.sellerUid
        if (orderId.isEmpty() || sellerUid.isEmpty()) return

        db.child("Sellers").child(sellerUid).child("orders").child(orderId)
            .child("status").setValue("READY")
            .addOnSuccessListener {
                // Optionally show a toast or update UI
            }
    }
}
