package com.arslandaim.playtube.utils

import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** True for YouTube Shorts / very short vertical clips. These are hidden from every list. */
fun StreamInfoItem.isShortVideo(): Boolean =
    url?.contains("/shorts/") == true || duration in 1..60
