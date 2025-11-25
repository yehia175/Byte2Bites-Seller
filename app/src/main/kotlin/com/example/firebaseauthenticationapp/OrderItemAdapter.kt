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
        //Note: Only a few item views are created initially; others are reused.
    }

    //Called when RecyclerView wants to display data in a ViewHolder.
    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        val item = itemsList[position]

        holder.itemName.text = item.name
        holder.quantity.text = "Quantity: ${item.quantity}"

        //Price stored as String in OrderItem
        val priceValue = item.price.toDoubleOrNull() ?: 0.0 //Converts string to double. If fail, set to 0.0
        holder.price.text = "Price: ${"%.2f".format(priceValue)} EGP"

        val subtotalValue = priceValue * item.quantity
        holder.subtotal.text = "Subtotal: ${"%.2f".format(subtotalValue)} EGP"
    }

    //FIRST THING THAT IS CALLED IN THE WHOLE PAGE, called automatically by RecyclerView
    override fun getItemCount(): Int = itemsList.size
    //Returns the total number of items in the list.
    //RecyclerView uses this to know how many items to display.
}
//V.V.V.IMP:
//How It Works Together:
//1- RecyclerView asks: How many items? → getItemCount()
//2- RecyclerView creates enough ViewHolders to fill the screen → onCreateViewHolder()
//3- RecyclerView binds data to each ViewHolder → onBindViewHolder()
//4- When you scroll, old ViewHolders are recycled for new data (efficiency).
