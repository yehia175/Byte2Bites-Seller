package com.example.firebaseauthenticationapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class OrderItemAdapter(private val itemsList: List<OrderItem>) :
    RecyclerView.Adapter<OrderItemAdapter.ItemViewHolder>() {

    class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val itemName: TextView = itemView.findViewById(R.id.textProductName)
        val quantity: TextView = itemView.findViewById(R.id.textQuantity)
        val price: TextView = itemView.findViewById(R.id.textItemPrice)
        val subtotal: TextView = itemView.findViewById(R.id.textItemSubtotal)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_order_item, parent, false)
        return ItemViewHolder(view)
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        val item = itemsList[position]

        holder.itemName.text = item.name
        holder.quantity.text = "Quantity: ${item.quantity}"

        val priceValue = item.price.toDoubleOrNull() ?: 0.0
        holder.price.text = "Price: ${"%.2f".format(priceValue)} EGP"

        val subtotalValue = priceValue * item.quantity
        holder.subtotal.text = "Subtotal: ${"%.2f".format(subtotalValue)} EGP"
    }

    override fun getItemCount(): Int = itemsList.size
}
