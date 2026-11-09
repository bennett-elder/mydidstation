package com.willingtocode.voicepathfinder

import android.app.Application

/**
 * The process-wide holder.
 *
 * A foreground service is what the product needs and what Phase 0 explicitly does not
 * build. Until then this is the reason a rotation does not drop a live call: the
 * controller outlives the activity, and the activity only renders it.
 */
class PathfinderApp : Application() {
    val pathfinder: Pathfinder by lazy { Pathfinder(this) }
}
