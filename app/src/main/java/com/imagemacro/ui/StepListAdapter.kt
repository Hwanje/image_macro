package com.imagemacro.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.imagemacro.databinding.ItemStepBinding
import com.imagemacro.model.Step

class StepListAdapter(
    private val steps: MutableList<Step>,
    private val onTap: (Int) -> Unit,
    private val onUp: (Int) -> Unit,
    private val onDown: (Int) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<StepListAdapter.VH>() {

    inner class VH(val v: ItemStepBinding) : RecyclerView.ViewHolder(v.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = ItemStepBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(v)
    }

    override fun getItemCount() = steps.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = steps[position]
        holder.v.txtIndex.text = "${position + 1}"
        holder.v.txtType.text = s.type.display
        holder.v.txtSummary.text = s.summary()
        holder.v.root.setOnClickListener { onTap(holder.bindingAdapterPosition) }
        holder.v.btnUp.setOnClickListener { onUp(holder.bindingAdapterPosition) }
        holder.v.btnDown.setOnClickListener { onDown(holder.bindingAdapterPosition) }
        holder.v.btnDelete.setOnClickListener { onDelete(holder.bindingAdapterPosition) }
    }
}
