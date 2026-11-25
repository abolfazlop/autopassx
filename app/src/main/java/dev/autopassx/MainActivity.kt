package dev.autopassx

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 28, 28, 28)
        }
        root.addView(TextView(this).apply {
            text = "AutoPass X\n\nYour encrypted password vault and autofill assistant."
            textSize = 21f
            gravity = Gravity.CENTER
        })
        root.addView(Button(this).apply {
            text = "Enable AutoFill"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                    data = android.net.Uri.parse("package:$packageName")
                })
            }
        })
        root.addView(TextView(this).apply {
            text = "\nDevelopment build 0.1.0\nUse a test account first. Vault UI and account picker are in progress."
            gravity = Gravity.CENTER
        })
        setContentView(root)
    }
}
