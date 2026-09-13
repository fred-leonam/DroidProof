package io.github.fredleonam.droidproof.smokeapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.action).setOnClickListener {
            findViewById<TextView>(R.id.status).setText(R.string.action_completed)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        findViewById<TextView>(R.id.status).setText(R.string.ready)
    }
}
