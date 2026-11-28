package com.wangzan101.mika.media

import androidx.core.content.FileProvider

/** Hands a copy of a video made in the cache for playback to the system player as a read-only content:// address; the workspace itself is not exposed. */
class VideoPlaybackFilesProvider : FileProvider()