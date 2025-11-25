package com.example.firebaseauthenticationapp

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

class ProductAdapter(
    private val context: Context,//for inflating layouts or loading images
    private val productList: List<Product>,//the data you want to display in the RecyclerView.
    private val onItemClick: ((Product) -> Unit)? = null   // optional click listener for edit
) : RecyclerView.Adapter<ProductAdapter.ProductViewHolder>() {

    class ProductViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val image: ImageView = itemView.findViewById(R.id.productImage)
        val name: TextView = itemView.findViewById(R.id.productName)
    }

    //CREATE new ViewHolder objects whenever the RecyclerView needs a new item view.
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProductViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_product, parent, false)
        return ProductViewHolder(view)//Returns a ProductViewHolder, which holds references to the views of the item.
    }
    //RecyclerView calls onCreateViewHolder when it needs a new item view.
    //We inflate the item layout XML into a View object.
    //We wrap that View in a ProductViewHolder.
    //RecyclerView can now reuse this ViewHolder and pass it to onBindViewHolder to display data.

    //FILL the views inside a ViewHolder with the actual data for the item at a given position.
    override fun onBindViewHolder(holder: ProductViewHolder, position: Int) {
        val product = productList[position]//Retrieves the Product object corresponding to this position in the list.

        //Even when views are recycled, position is updated by RecyclerView to match the new data that the ViewHolder should display.

        // Display product name
        holder.name.text = product.name ?: "No Name"

        // Log image URL
        val imageUrl = product.imageUrl
        //print url in logcat (for debugging only)
        Log.d("ProductAdapter", "Loading image URL: $imageUrl for product: ${product.name}")

        // Load image with Glide
        if (!imageUrl.isNullOrEmpty()) {
            Glide.with(holder.image.context)
                .load(imageUrl)//tells Glide what image to load, in this case the URL from Firebase/AWS.
                .placeholder(R.drawable.placeholder)//shown while the image is loading.
                .error(R.drawable.placeholder)//shown if loading fails (bad URL, network error).
                .fallback(R.drawable.placeholder)//shown if the URL is null (just in case you didn’t check earlier).
                .diskCacheStrategy(DiskCacheStrategy.ALL)//Caches both the original image and the resized image in Glide’s cache to load fast and offline.
                .centerCrop()//Crops the image to fill the ImageView, maintaining aspect ratio to avoid distortion.
                .into(holder.image)//Tells Glide which ImageView to display the loaded image in.
        } else {
            holder.image.setImageResource(R.drawable.placeholder)
        }

        // Handle click for editing
        holder.itemView.setOnClickListener {//holder.itemView → the root view of this item in the RecyclerView.
            onItemClick?.invoke(product)//click shows product(for editing)
        }
    }

    override fun getItemCount(): Int = productList.size
    //Returns the total number of items in the RecyclerView.
    //RecyclerView uses this to know how many ViewHolders to create.
    //We must know how many items it will display
}
