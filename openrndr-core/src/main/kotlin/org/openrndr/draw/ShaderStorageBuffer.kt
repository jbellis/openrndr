package org.openrndr.draw

import org.openrndr.internal.Driver
import java.nio.ByteBuffer

interface ShaderStorageBuffer {
    val session: Session?
    val format: ShaderStorageFormat

    fun clear()
    fun bind(base: Int)

    fun write(source: ByteBuffer, writeOffset: Int = 0)
    fun read(target: ByteBuffer, readOffset: Int = 0)

    fun destroy()

}

fun shaderStorageBuffer(format: ShaderStorageFormat): ShaderStorageBuffer {
    return Driver.instance.createShaderStorageBuffer(format)
}

data class ShaderStorageElement(val attribute: String, val offset: Int, val type: VertexElementType, val arraySize: Int)


class ShaderStorageFormat {
    var items: MutableList<ShaderStorageElement> = mutableListOf()
    private var formatSize = 0

    /**
     * The size of the [ShaderStorageFormat] in bytes
     */
    val size get() = formatSize


    /**
     * Adds a custom attribute to the [VertexFormat]
     */
    fun attribute(name: String, type: VertexElementType, arraySize: Int = 1) {
        val offset = items.sumBy { it.arraySize * it.type.sizeInBytes }
        val item = ShaderStorageElement(name, offset, type, arraySize)
        items.add(item)
        formatSize += type.sizeInBytes * arraySize
    }

    override fun toString(): String {
        return "ShaderStorageFormat{" +
                "items=" + items +
                ", formatSize=" + formatSize +
                '}'
    }

    fun hasAttribute(name: String): Boolean = items.any { it.attribute == name }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ShaderStorageFormat
        if (items != other.items) return false
        return true
    }

    override fun hashCode(): Int {
        return items.hashCode()
    }
}

fun shaderStorageFormat(builder: ShaderStorageFormat.() -> Unit): ShaderStorageFormat {
    return ShaderStorageFormat().apply { builder() }
}

