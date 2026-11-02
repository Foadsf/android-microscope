package io.github.foadsf.microscope.gl

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView

/** The live view: an OpenGL ES 3 surface that renders only when a camera frame or setting changes. */
@SuppressLint("ViewConstructor")
class PreviewView(context: Context, onTexture: (SurfaceTexture) -> Unit) : GLSurfaceView(context) {
    val renderer = PreviewRenderer(onTexture) { requestRender() }

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }
}
