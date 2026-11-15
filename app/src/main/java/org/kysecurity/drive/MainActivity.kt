package org.kysecurity.drive

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.navigation.NavigationBarView

class MainActivity : SecureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        val nav = findViewById<NavigationBarView>(R.id.nav)
        nav.setOnItemSelectedListener { show(it.itemId); true }
        show(nav.selectedItemId)
    }

    override fun onResume() {
        super.onResume()
        // Only the wide layout has a shell; the side may have changed in Settings.
        val shell = findViewById<LinearLayout>(R.id.shell) ?: return
        val rail = findViewById<View>(R.id.nav)
        val index = if (Settings(this).railSide == RailSide.LEFT) 0 else shell.childCount - 1
        if (shell.indexOfChild(rail) != index) {
            shell.removeView(rail)
            shell.addView(rail, index)
        }
    }

    private fun show(item: Int) {
        val files = item == R.id.nav_files
        findViewById<TextView>(R.id.title).setText(if (files) R.string.nav_files else R.string.nav_shared)
        findViewById<TextView>(R.id.empty).setText(if (files) R.string.empty_files else R.string.empty_shared)
    }
}
