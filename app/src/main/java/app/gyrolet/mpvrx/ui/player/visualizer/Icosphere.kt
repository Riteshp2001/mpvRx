/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.player.visualizer

import kotlin.math.sqrt

internal data class WireMesh(
    val positions: FloatArray,
    val lineIndices: IntArray
)

internal object Icosphere {
    fun create(subdivisions: Int): WireMesh {
        require(subdivisions in 0..6)

        val t = ((1.0 + sqrt(5.0)) / 2.0).toFloat()
        val vertices = ArrayList<FloatArray>()
        fun add(x: Float, y: Float, z: Float): Int {
            val length = sqrt(x * x + y * y + z * z)
            vertices += floatArrayOf(x / length, y / length, z / length)
            return vertices.lastIndex
        }

        add(-1f, t, 0f); add(1f, t, 0f); add(-1f, -t, 0f); add(1f, -t, 0f)
        add(0f, -1f, t); add(0f, 1f, t); add(0f, -1f, -t); add(0f, 1f, -t)
        add(t, 0f, -1f); add(t, 0f, 1f); add(-t, 0f, -1f); add(-t, 0f, 1f)

        var faces = arrayListOf(
            intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7), intArrayOf(0, 7, 10), intArrayOf(0, 10, 11),
            intArrayOf(1, 5, 9), intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6), intArrayOf(7, 1, 8),
            intArrayOf(3, 9, 4), intArrayOf(3, 4, 2), intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
            intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10), intArrayOf(8, 6, 7), intArrayOf(9, 8, 1)
        )

        repeat(subdivisions) {
            val midpointCache = HashMap<Long, Int>(faces.size * 2)
            fun midpoint(a: Int, b: Int): Int {
                val low = minOf(a, b)
                val high = maxOf(a, b)
                val key = (low.toLong() shl 32) or high.toLong()
                return midpointCache.getOrPut(key) {
                    val va = vertices[a]
                    val vb = vertices[b]
                    add(
                        (va[0] + vb[0]) * 0.5f,
                        (va[1] + vb[1]) * 0.5f,
                        (va[2] + vb[2]) * 0.5f
                    )
                }
            }

            val refined = ArrayList<IntArray>(faces.size * 4)
            for (face in faces) {
                val a = midpoint(face[0], face[1])
                val b = midpoint(face[1], face[2])
                val c = midpoint(face[2], face[0])
                refined += intArrayOf(face[0], a, c)
                refined += intArrayOf(face[1], b, a)
                refined += intArrayOf(face[2], c, b)
                refined += intArrayOf(a, b, c)
            }
            faces = refined
        }

        val positions = FloatArray(vertices.size * 3)
        vertices.forEachIndexed { index, vertex ->
            positions[index * 3] = vertex[0]
            positions[index * 3 + 1] = vertex[1]
            positions[index * 3 + 2] = vertex[2]
        }

        val edges = LinkedHashSet<Long>(faces.size * 3)
        fun addEdge(a: Int, b: Int) {
            val low = minOf(a, b)
            val high = maxOf(a, b)
            edges += (low.toLong() shl 32) or high.toLong()
        }
        faces.forEach { face ->
            addEdge(face[0], face[1])
            addEdge(face[1], face[2])
            addEdge(face[2], face[0])
        }

        val indices = IntArray(edges.size * 2)
        var cursor = 0
        edges.forEach { edge ->
            indices[cursor++] = (edge shr 32).toInt()
            indices[cursor++] = edge.toInt()
        }

        return WireMesh(positions, indices)
    }
}
