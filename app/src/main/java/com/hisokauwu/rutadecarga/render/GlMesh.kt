package com.hisokauwu.rutadecarga.render

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Malla subida a un VBO de la GPU. */
class GlMesh(builder: MeshBuilder) {
    private val vbo: Int
    val vertexCount = builder.vertexCount

    init {
        val arr = builder.toArray()
        val buf = ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(arr).position(0)
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, arr.size * 4, buf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun draw(shader: Shader) {
        if (vertexCount == 0) return
        val stride = MeshBuilder.STRIDE * 4
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(shader.aPos)
        GLES20.glVertexAttribPointer(shader.aPos, 3, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glEnableVertexAttribArray(shader.aNormal)
        GLES20.glVertexAttribPointer(shader.aNormal, 3, GLES20.GL_FLOAT, false, stride, 12)
        GLES20.glEnableVertexAttribArray(shader.aColor)
        GLES20.glVertexAttribPointer(shader.aColor, 3, GLES20.GL_FLOAT, false, stride, 24)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}

/** Shader con iluminación direccional por vértice y niebla. */
class Shader {
    val program: Int
    val aPos: Int
    val aNormal: Int
    val aColor: Int
    val uMvp: Int
    val uModel: Int
    val uLightDir: Int
    val uColorMul: Int
    val uCamPos: Int
    val uFogColor: Int
    val uFog: Int

    private val vs = """
        uniform mat4 uMvp;
        uniform mat4 uModel;
        uniform vec3 uLightDir;
        uniform vec3 uColorMul;
        uniform vec3 uCamPos;
        uniform vec2 uFog;
        attribute vec3 aPos;
        attribute vec3 aNormal;
        attribute vec3 aColor;
        varying vec3 vColor;
        varying float vFog;
        void main() {
            vec4 wp = uModel * vec4(aPos, 1.0);
            vec3 n = normalize((uModel * vec4(aNormal, 0.0)).xyz);
            float diff = max(dot(n, uLightDir), 0.0);
            float sky = 0.5 + 0.5 * n.y;
            vColor = aColor * uColorMul * (0.32 + 0.2 * sky + 0.62 * diff);
            float d = distance(wp.xyz, uCamPos);
            vFog = clamp((d - uFog.x) / (uFog.y - uFog.x), 0.0, 1.0);
            gl_Position = uMvp * vec4(aPos, 1.0);
        }
    """.trimIndent()

    private val fs = """
        precision mediump float;
        uniform vec3 uFogColor;
        varying vec3 vColor;
        varying float vFog;
        void main() {
            gl_FragColor = vec4(mix(vColor, uFogColor, vFog * vFog), 1.0);
        }
    """.trimIndent()

    init {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, v)
        GLES20.glAttachShader(program, f)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) throw RuntimeException("Error enlazando shader: " + GLES20.glGetProgramInfoLog(program))
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        aColor = GLES20.glGetAttribLocation(program, "aColor")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uLightDir = GLES20.glGetUniformLocation(program, "uLightDir")
        uColorMul = GLES20.glGetUniformLocation(program, "uColorMul")
        uCamPos = GLES20.glGetUniformLocation(program, "uCamPos")
        uFogColor = GLES20.glGetUniformLocation(program, "uFogColor")
        uFog = GLES20.glGetUniformLocation(program, "uFog")
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) throw RuntimeException("Error compilando shader: " + GLES20.glGetShaderInfoLog(s))
        return s
    }
}
