package org.kysecurity.drive

import android.annotation.SuppressLint
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.kysecurity.drive.core.FileItem
import org.kysecurity.drive.core.Folder
import org.kysecurity.drive.core.SharedItem
import org.kysecurity.drive.core.Workspace

/** One line in a listing. */
sealed interface Row {
    val name: String

    data class Space(val workspace: Workspace) : Row {
        override val name get() = workspace.name
    }

    data class Dir(val folder: Folder) : Row {
        override val name get() = folder.name
    }

    data class Doc(val file: FileItem) : Row {
        override val name get() = file.name
    }

    data class Direct(val item: SharedItem) : Row {
        override val name get() = item.name
    }

    companion object {
        /** Workspaces, then folders, then files, each by name as a person reads it. */
        fun sorted(rows: List<Row>): List<Row> = rows.sortedWith(
            compareBy<Row> {
                when (it) {
                    is Space -> 0
                    is Dir -> 1
                    is Direct -> if (it.item.kind == "folder") 1 else 2
                    is Doc -> 2
                }
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
    }
}

class RowAdapter(
    private val open: (Row) -> Unit,
    private val actions: ((Row) -> Unit)?,
) : RecyclerView.Adapter<RowAdapter.Holder>() {
    // A whole folder at a time, never one row.
    var rows: List<Row> = emptyList()
        @SuppressLint("NotifyDataSetChanged") set(value) {
            field = value
            notifyDataSetChanged()
        }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val name: TextView = view.findViewById(R.id.name)
        val detail: TextView = view.findViewById(R.id.detail)
        val actions: View = view.findViewById(R.id.actions)
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item, parent, false))

    override fun onBindViewHolder(h: Holder, position: Int) {
        val row = rows[position]
        val c = h.itemView.context
        h.name.text = row.name
        val (icon, detail) = when (row) {
            is Row.Space -> R.drawable.ic_workspace to c.getString(roleText(row.workspace.role))
            is Row.Dir -> R.drawable.ic_folder to ""
            is Row.Doc -> R.drawable.ic_file to listOf(Formatter.formatShortFileSize(c, row.file.size), row.file.modified.take(10)).filter { it.isNotEmpty() }.joinToString(" · ")
            is Row.Direct -> (if (row.item.kind == "folder") R.drawable.ic_folder else R.drawable.ic_file) to
                c.getString(R.string.shared_from, row.item.workspaceName.ifEmpty { c.getString(R.string.nav_shared) }, c.getString(roleText(row.item.role)))
        }
        h.icon.setImageResource(icon)
        h.detail.text = detail
        h.detail.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        h.itemView.setOnClickListener { open(row) }
        val act = actions
        h.actions.visibility = if (act != null && row !is Row.Space) View.VISIBLE else View.GONE
        h.actions.contentDescription = c.getString(R.string.actions_for, row.name)
        h.actions.setOnClickListener { act?.invoke(row) }
    }

    companion object {
        fun roleText(role: String) = when (role) {
            "manager" -> R.string.role_manager
            "editor" -> R.string.role_editor
            else -> R.string.role_reader
        }
    }
}
