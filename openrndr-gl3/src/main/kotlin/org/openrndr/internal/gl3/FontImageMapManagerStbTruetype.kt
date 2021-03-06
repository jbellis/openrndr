package org.openrndr.internal.gl3

import mu.KotlinLogging
import org.lwjgl.BufferUtils
import org.lwjgl.stb.STBTTFontinfo
import org.lwjgl.stb.STBTruetype.*
import org.lwjgl.system.MemoryStack.stackPush
import org.openrndr.binpack.IntPacker
import org.openrndr.binpack.PackNode
import org.openrndr.draw.*
import org.openrndr.internal.FontMapManager
import org.openrndr.math.IntVector2
import org.openrndr.shape.IntRectangle
import java.net.URL
import java.nio.Buffer
import java.nio.ByteOrder

private val logger = KotlinLogging.logger {}

class FontImageMapManagerStbTruetype : FontMapManager() {
    override fun fontMapFromUrl(url: String, size: Double, characterSet: Set<Char>, contentScale: Double): FontImageMap {
        logger.debug { "content scale $contentScale" }
        var packSize = 256

        val byteArray = URL(url).readBytes()
        val fileSize = byteArray.size

        if (fileSize == 0) {
            throw RuntimeException("0 bytes read")
        }

        logger.debug { "bytes read: $fileSize" }

        val bb = BufferUtils.createByteBuffer(fileSize)
        bb.order(ByteOrder.nativeOrder())
        (bb as Buffer).rewind()
        bb.put(byteArray, 0, fileSize)
        (bb as Buffer).rewind()
        val info = STBTTFontinfo.create()

        val status = stbtt_InitFont(info, bb)

        if (!status) {
            throw RuntimeException("font error")
        }

        val scale = (stbtt_ScaleForPixelHeight(info, (size * contentScale).toFloat()))

        @Suppress("VARIABLE_WITH_REDUNDANT_INITIALIZER") var ascent = 0.0
        @Suppress("VARIABLE_WITH_REDUNDANT_INITIALIZER") var descent = 0.0
        @Suppress("VARIABLE_WITH_REDUNDANT_INITIALIZER") var lineGap = 0.0
        stackPush().let {
            val pAscent = it.mallocInt(1)
            val pDescent = it.mallocInt(1)
            val pLineGap = it.mallocInt(1)

            stbtt_GetFontVMetrics(info, pAscent, pDescent, pLineGap)

            ascent = pAscent.get(0) * scale * 1.0
            descent = pDescent.get(0) * scale * 1.0
            lineGap = pLineGap.get(0) * scale * 1.0
        }

        val glyphIndices = characterSet.associate { Pair(it, stbtt_FindGlyphIndex(info, it.toInt())) }
        val glyphDimensions = characterSet.associate { c ->
            stackPush().use {
                val px0 = it.mallocInt(1)
                val py0 = it.mallocInt(1)
                val px1 = it.mallocInt(1)
                val py1 = it.mallocInt(1)
                stbtt_GetGlyphBitmapBoxSubpixel(info, glyphIndices.getValue(c), scale, scale, 0.0f, 0.0f, px0, py0, px1, py1)
                Pair(c, IntVector2(px1.get() - px0.get(), py1.get() - py0.get()))
            }
        }
        val sanding = 3

        while (true) {
            val root = PackNode(IntRectangle(0, 0, packSize, packSize))
            val packer = IntPacker()
            if (attemptPack(root, packer, glyphDimensions, sanding)) {
                break
            } else {
                packSize *= 2
            }
        }
        logger.debug { "final map size ${packSize}x${packSize}" }

        val image = colorBuffer(packSize, packSize, 1.0, ColorFormat.R, session = Session.root)
        val map = mutableMapOf<Char, IntRectangle>()

        val root = PackNode(IntRectangle(0, 0, packSize, packSize))
        val packer = IntPacker()

        val glyphMetrics = mutableMapOf<Char, GlyphMetrics>()

        val bitmap = BufferUtils.createByteBuffer(packSize * packSize)

        logger.debug { "creating font bitmap" }
        glyphDimensions.entries.sortedByDescending { it.value.squaredLength }.forEach {
            val target = packer.insert(root, IntRectangle(0, 0, it.value.x + 2 * sanding, it.value.y + 2 * sanding))

            target?.let { t ->
                map[it.key] = IntRectangle(t.area.x + sanding, t.area.y + sanding, t.area.width - 2 * sanding , t.area.height - 2 * sanding)

                val glyphIndex = glyphIndices[it.key]!!
                var advanceWidth = 0
                var leftBearing = 0
                stackPush().use { stack ->
                    val pAdvanceWidth = stack.mallocInt(1)
                    val pLeftBearing = stack.mallocInt(1)
                    stbtt_GetGlyphHMetrics(info, glyphIndex, pAdvanceWidth, pLeftBearing)
                    advanceWidth = pAdvanceWidth.get(0)
                    leftBearing = pLeftBearing.get(0)
                }

                var x0 = 0
                var y0 = 0
                var x1 = 0
                var y1 = 0
                stackPush().use { stack ->
                    val px0 = stack.mallocInt(1)
                    val py0 = stack.mallocInt(1)
                    val px1 = stack.mallocInt(1)
                    val py1 = stack.mallocInt(1)
                    stbtt_GetGlyphBitmapBoxSubpixel(info, glyphIndex, scale, scale, 0.0f, 0.0f, px0, py0, px1, py1)
                    x0 = px0.get(0); y0 = py0.get(0); x1 = px1.get(0); y1 = py1.get(0)
                }
                val ascale = scale / contentScale
                glyphMetrics[it.key] = GlyphMetrics(advanceWidth * ascale, leftBearing * ascale, x0.toDouble(), y0.toDouble())

                (bitmap as Buffer).rewind()
                (bitmap as Buffer).position((sanding + t.area.y) * packSize + sanding + t.area.x)
                stbtt_MakeGlyphBitmapSubpixel(info, bitmap, x1 - x0, y1 - y0, packSize, scale, scale, 0.0f, 0.0f, glyphIndex)
            }
        }
        logger.debug { "uploading bitmap to colorbuffer" }
        (bitmap as Buffer).rewind()
        image.write(bitmap)

        val leading = ascent - descent + lineGap
        return FontImageMap(image, map, glyphMetrics, size, contentScale, ascent / contentScale, descent / contentScale, (ascent + descent) / contentScale, leading / contentScale, url).apply {
            for (outer in characterSet) {
                for (inner in characterSet) {
                    val outerGlyph = glyphIndices[outer]
                    val innerGlyph = glyphIndices[inner]
                    if (outerGlyph != null && innerGlyph != null) {
                        val kernInfo = stbtt_GetGlyphKernAdvance(info, outerGlyph, innerGlyph)
                        kerningTable[CharacterPair(outer, inner)] = kernInfo * (scale/contentScale)
                    }
                }
            }
            // -- make sure the byte buffer containing font info is not garbage collected
            (bb as Buffer).rewind()
        }
    }
}

internal fun attemptPack(root: PackNode, packer: IntPacker, map: Map<Char, IntVector2>, sanding: Int = 2): Boolean {
    for (entry in map.values.sortedByDescending { it.squaredLength }) {
        val rectangle = IntRectangle(0, 0, entry.x + 2 * sanding, entry.y + 2 * sanding)
        if (rectangle.width <= 0 || rectangle.height <= 0) {
            throw RuntimeException("area < 0")
        }
        packer.insert(root, rectangle) ?: return false
    }
    return true
}
