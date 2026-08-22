// Chuks Preview connect screen (Android). The launcher activity for the Preview build:
// point it at a running `chuks dev` server by typing the address or opening a chuks://
// deep link (Android's camera / Lens scans the QR and opens it here), then it hands off
// to MainActivity which renders the served app over HTTP. Built programmatically — no XML.
package com.chuks.app

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class ConnectActivity : Activity() {
    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    // A white QR-viewfinder glyph (four corner brackets + a few modules), drawn to a
    // bitmap so the Scan button has an icon without shipping an asset.
    private fun qrIcon(px: Int): android.graphics.drawable.Drawable {
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = android.graphics.Paint.Style.STROKE
            strokeWidth = px * 0.09f; strokeCap = android.graphics.Paint.Cap.ROUND
        }
        val m = px * 0.14f; val len = px * 0.2f; val e = px - m
        c.drawLine(m, m + len, m, m, stroke); c.drawLine(m, m, m + len, m, stroke)           // top-left
        c.drawLine(e - len, m, e, m, stroke); c.drawLine(e, m, e, m + len, stroke)           // top-right
        c.drawLine(m, e - len, m, e, stroke); c.drawLine(m, e, m + len, e, stroke)           // bottom-left
        c.drawLine(e, e - len, e, e, stroke); c.drawLine(e - len, e, e, e, stroke)           // bottom-right
        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val b = px * 0.11f; val o = px * 0.37f
        c.drawRect(o, o, o + b, o + b, fill)
        c.drawRect(o + b * 1.6f, o, o + b * 2.6f, o + b, fill)
        c.drawRect(o, o + b * 1.6f, o + b, o + b * 2.6f, fill)
        c.drawRect(o + b * 1.7f, o + b * 1.7f, o + b * 2.5f, o + b * 2.5f, fill)
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // A chuks:// deep link connects straight through, no manual entry.
        parseDevServer(intent?.data?.toString())?.let { connect(it); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B1120"))
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }

        // logo (bundled ChuksLogo.png), 72dp
        try {
            assets.open("ChuksLogo.png").use { s ->
                root.addView(ImageView(this).apply {
                    setImageBitmap(BitmapFactory.decodeStream(s))
                    layoutParams = LinearLayout.LayoutParams(dp(76), dp(76))
                })
            }
        } catch (e: Exception) {}

        root.addView(TextView(this).apply {
            text = "Chuks Preview"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(6))
        })
        root.addView(TextView(this).apply {
            text = "Run your Chuks app on this device.\nScan the QR from chuks dev with your camera."
            setTextColor(Color.parseColor("#99A4B2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(22))
        })

        // Scan QR code (opens the in-app camera scanner). Built as a centered icon+text
        // row, not a Button — a Button's compound drawable pins the icon to the far left.
        val scanBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat(); setColor(Color.parseColor("#4F7DFF"))
            }
            setPadding(0, dp(15), 0, dp(15))
            isClickable = true; isFocusable = true
            setOnClickListener { startActivity(Intent(this@ConnectActivity, ScannerActivity::class.java)) }
            addView(ImageView(this@ConnectActivity).apply {
                setImageDrawable(qrIcon(dp(22)))
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) })
            addView(TextView(this@ConnectActivity).apply {
                text = "Scan QR code"
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
        }
        root.addView(scanBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(14) })

        val field = EditText(this).apply {
            hint = "192.168.1.5:7799"
            setHintTextColor(Color.parseColor("#73808F"))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#16202E"))
            }
            setPadding(dp(14), dp(13), dp(14), dp(13))
        }
        root.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(Button(this).apply {
            text = "Connect manually"
            setTextColor(Color.parseColor("#4F7DFF"))
            background = null
            setOnClickListener {
                val host = parseDevServer(field.text.toString())
                if (host != null) connect(host)
                else Toast.makeText(this@ConnectActivity, "Enter a host like 192.168.1.5:7799", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })

        // reconnect to the last server
        prefs().getString("host", "")?.takeIf { it.isNotEmpty() }?.let { last ->
            root.addView(TextView(this).apply {
                text = "Reconnect to $last"
                setTextColor(Color.parseColor("#7F8B9A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, 0)
                setOnClickListener { connect(last) }
            })
        }

        setContentView(root)
    }

    private fun prefs() = getSharedPreferences("chuks.preview", MODE_PRIVATE)

    private fun connect(host: String) {
        prefs().edit().putString("host", host).apply()
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }

    // "chuks://192.168.1.5:7799" | "192.168.1.5:7799" | "192.168.1.5" (adds :7799) -> "host:port"
    private fun parseDevServer(raw: String?): String? {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        for (p in listOf("chuks://", "http://", "https://")) if (s.startsWith(p, true)) { s = s.substring(p.length); break }
        s = s.substringBefore('/')
        if (s.isEmpty()) return null
        if (!s.contains(':')) s += ":7799"
        return s
    }
}
