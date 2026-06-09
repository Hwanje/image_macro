package com.imagemacro.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.imagemacro.databinding.ItemMacroBinding
import com.imagemacro.model.Macro

class MacroListAdapter(
    private val onRun: (Macro) -> Unit,
    private val onEdit: (Macro) -> Unit,
    private val onDelete: (Macro) -> Unit
) : RecyclerView.Adapter<MacroListAdapter.VH>() {

    private val items = mutableListOf<Macro>()

    fun submit(list: List<Macro>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    inner class VH(val v: ItemMacroBinding) : RecyclerView.ViewHolder(v.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = ItemMacroBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.v.txtName.text = m.name
        val repeat = if (m.repeatCount == 0) "무한 반복" else "${m.repeatCount}회 반복"
        holder.v.txtInfo.text = "${m.steps.size}단계 · $repeat"
        holder.v.btnRun.setOnClickListener { onRun(m) }
        holder.v.root.setOnClickListener { onEdit(m) }
        holder.v.root.setOnLongClickListener { onDelete(m); true }
    }
}
