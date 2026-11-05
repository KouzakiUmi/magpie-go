package com.magpie.android.ui

import android.content.Context
import android.graphics.drawable.AnimationDrawable
import androidx.core.content.ContextCompat
import com.magpie.android.R

/** Generated original Pica artwork frames, played by Android without image rewriting. */
internal object PicaAnimation {
    fun drawable(context: Context): AnimationDrawable =
        requireNotNull(ContextCompat.getDrawable(context, R.drawable.pica_frame_animation)) as AnimationDrawable
}
