package io.github.fredleonam.droidproof.smokeapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.action).setOnClickListener {
            val name = findViewById<EditText>(R.id.name).text.toString()
            findViewById<TextView>(R.id.status).text =
                if (name.isEmpty()) getString(R.string.action_completed) else getString(R.string.greeting, name)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        findViewById<EditText>(R.id.name).text.clear()
        findViewById<TextView>(R.id.status).setText(R.string.ready)
    }
}
