package com.magpie.android.ui

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.AnimationDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.magpie.android.BuildConfig
import com.magpie.android.R

internal object AboutDialog {
    fun show(context: Context, preference: ThemePreference, onConfigure: () -> Unit, onThemeChanged: (ThemePreference) -> Unit) {
        val dialog = Dialog(context)
        var animation: AnimationDrawable? = null
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 16))
        }
        val pica = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(dp(context, 96), dp(context, 96))
            val drawable = PicaAnimation.drawable(context)
            setImageDrawable(drawable)
            animation = drawable
            drawable.start()
        }
        root.addView(pica)
        root.addView(text(context, context.getString(R.string.app_name), 26, bold = true))
        root.addView(text(context, context.getString(R.string.about_subtitle), 14))
        root.addView(text(context, context.getString(R.string.about_version, BuildConfig.VERSION_NAME), 12, color = "#667085"))

        root.addView(text(context, context.getString(R.string.about_theme), 13, topMargin = 12, bold = true))
        val themeGroup = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        listOf(
            ThemePreference.FollowSystem to R.string.about_theme_system,
            ThemePreference.Light to R.string.about_theme_light,
            ThemePreference.Dark to R.string.about_theme_dark,
        ).forEach { (value, label) ->
            themeGroup.addView(RadioButton(context).apply {
                id = View.generateViewId()
                textSize = 11f
                setSingleLine(true)
                minWidth = 0
                minHeight = 0
                setPadding(0, 0, 0, 0)
                gravity = Gravity.CENTER
                layoutParams = RadioGroup.LayoutParams(
                    if (value == ThemePreference.FollowSystem) dp(context, 96) else dp(context, 62),
                    dp(context, 44),
                )
                setText(label)
                isChecked = value == preference
                setOnClickListener {
                    onThemeChanged(value)
                    val dark = isDarkPreference(context, value)
                    applyDialogPalette(root, dark)
                    dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(dialogBackground(dark)))
                    root.postDelayed({ dialog.dismiss() }, 250)
                }
            })
        }
        root.addView(themeGroup)

        val author = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 12), 0, dp(context, 4))
            isClickable = true
            isFocusable = true
            setOnClickListener { openUri(context, "https://github.com/KouzakiUmi") }
        }
        author.addView(ImageView(context).apply {
            setImageResource(R.drawable.about_author)
            layoutParams = LinearLayout.LayoutParams(dp(context, 56), dp(context, 56))
        })
        author.addView(
            text(context, context.getString(R.string.about_author), 13, leftMargin = 12),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(author)
        root.addView(text(context, context.getString(R.string.about_contributors), 13, topMargin = 8))

        root.addView(Button(context).apply {
            text = context.getString(R.string.about_update)
            textSize = 13f
            minHeight = 0
            setPadding(0, dp(context, 6), 0, dp(context, 6))
            setOnClickListener { openUri(context, "https://github.com/KouzakiUmi/magpie-go/releases") }
        }, matchWidth(context))

        root.addView(text(context, context.getString(R.string.about_links), 12, topMargin = 8, color = "#667085"))
        val firstLinks = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        firstLinks.addView(aboutButton(context, R.string.about_configure) {
            dialog.dismiss()
            onConfigure()
        }, linkParams(context))
        firstLinks.addView(aboutButton(context, R.string.about_official) {
            openUri(context, "https://github.com/yetone/magpie")
        }, linkParams(context))
        root.addView(firstLinks)
        val secondLinks = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        secondLinks.addView(aboutButton(context, R.string.about_community) {
            openUri(context, "https://github.com/yetone/magpie/discussions")
        }, linkParams(context))
        secondLinks.addView(aboutButton(context, R.string.about_licenses) {
            AboutLicenseDialog.show(context, preference)
        }, linkParams(context))
        root.addView(secondLinks)

        val dark = isDarkPreference(context, preference)
        applyDialogPalette(root, dark)
        dialog.setContentView(ScrollView(context).apply {
            addView(root)
            isFillViewport = true
        })
        dialog.setOnDismissListener { animation?.stop() }
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(dialogBackground(dark)))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    private fun openUri(context: Context, value: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_external_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun aboutButton(context: Context, label: Int, onClick: () -> Unit): Button = Button(context).apply {
        text = context.getString(label)
        textSize = 12f
        minHeight = 0
        minWidth = 0
        setPadding(0, dp(context, 6), 0, dp(context, 6))
        setOnClickListener { onClick() }
    }

    private fun linkParams(context: Context) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun text(
        context: Context,
        value: String,
        size: Int,
        bold: Boolean = false,
        color: String? = null,
        topMargin: Int = 0,
        leftMargin: Int = 0,
    ): TextView = TextView(context).apply {
        text = value
        textSize = size.toFloat()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        color?.let { setTextColor(Color.parseColor(it)) }
        setPadding(dp(context, leftMargin), dp(context, topMargin), 0, 0)
    }

    private fun matchWidth(context: Context) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(context, 6) }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
}

internal object AboutLicenseDialog {
    fun show(context: Context, preference: ThemePreference) {
        val content = buildString {
            append(context.assets.open("open_source_notices.txt").bufferedReader().use { it.readText() })
            context.assets.list("licenses")?.sorted()?.forEach { name ->
                append("\n\n========================================\n")
                append(name)
                append("\n========================================\n\n")
                append(context.assets.open("licenses/$name").bufferedReader().use { it.readText() })
            }
        }
        val dark = isDarkPreference(context, preference)
        val view = ScrollView(context).apply {
            addView(TextView(context).apply {
                text = content
                textSize = 12f
                setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 16))
            })
        }
        applyDialogPalette(view, dark)
        Dialog(context).apply {
            setTitle(R.string.about_licenses)
            setContentView(view)
            show()
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(dialogBackground(dark)))
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
}

private fun isDarkPreference(context: Context, preference: ThemePreference): Boolean = when (preference) {
    ThemePreference.Dark -> true
    ThemePreference.Light -> false
    ThemePreference.FollowSystem ->
        context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
}

private fun dialogBackground(dark: Boolean): Int = Color.parseColor(if (dark) "#101624" else "#F7F1E6")

private fun applyDialogPalette(view: View, dark: Boolean) {
    val foreground = Color.parseColor(if (dark) "#F5ECDD" else "#17233D")
    when (view) {
        is RadioButton -> view.setTextColor(foreground)
        is Button -> view.setTextColor(Color.parseColor("#17233D"))
        is TextView -> view.setTextColor(foreground)
    }
    if (view is ViewGroup) {
        view.setBackgroundColor(dialogBackground(dark))
        repeat(view.childCount) { index -> applyDialogPalette(view.getChildAt(index), dark) }
    }
}
