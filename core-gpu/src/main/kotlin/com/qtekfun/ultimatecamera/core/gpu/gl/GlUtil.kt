package com.qtekfun.ultimatecamera.core.gpu.gl

import android.opengl.GLES30

/** Thrown when a shader does not compile or link, or a GL call reports an error. */
class GlException(message: String) : RuntimeException(message)

internal object GlUtil {
    fun compileProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw GlException("program link failed: $log")
        }
        return program
    }

    fun checkError(what: String) {
        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) throw GlException("$what: GL error 0x${Integer.toHexString(error)}")
    }

    fun extensions(): Set<String> = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty().split(' ').filter { it.isNotBlank() }.toSet()

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw GlException("shader compile failed: $log")
        }
        return shader
    }
}
