/*
 * VIDENTRA - Watch Freely. Live Fully.
 * Licensed Under GPL-3.0+
 */
package com.arslandaim.playtube.ui.components

import androidx.compose.runtime.Composable

/**
 * Backward-compatible wrapper for the Developer Birthday Celebration Dialog.
 */
@Composable
fun DeveloperBirthdayDialog(
    onDismiss: () -> Unit
) {
    CelebrationDialog(
        celebration = CelebrationType.DEVELOPER_BIRTHDAY,
        onDismiss = onDismiss
    )
}
