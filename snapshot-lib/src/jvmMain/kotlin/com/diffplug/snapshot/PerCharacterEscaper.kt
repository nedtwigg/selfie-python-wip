/*
 * Copyright (C) 2023 DiffPlug
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.diffplug.snapshot

/**
 * If your escape policy is "'123", it means this:
 * ```
 * abc->abc
 * 123->'1'2'3
 * I won't->I won''t
 * ```
 */
class PerCharacterEscaper
/**
 * The first character in the string will be uses as the escape character, and all characters will
 * be escaped.
 */
private constructor(
    private val escapeCodePoint: Int,
    private val escapedCodePoints: IntArray,
    private val escapedByCodePoints: IntArray
) {
  fun needsEscaping(input: String): Boolean {
    return firstOffsetNeedingEscape(input) != -1
  }

  private fun firstOffsetNeedingEscape(input: String): Int {
    val length = input.length
    var firstOffsetNeedingEscape = -1
    var offset = 0
    outer@ while (offset < length) {
      val codepoint = input.codePointAt(offset)
      for (escaped in escapedCodePoints) {
        if (codepoint == escaped) {
          firstOffsetNeedingEscape = offset
          break@outer
        }
      }
      offset += Character.charCount(codepoint)
    }
    return firstOffsetNeedingEscape
  }

  fun escapeCodePoint(): Int {
    return escapeCodePoint
  }

  fun doForward(input: String): String {
    val noEscapes = firstOffsetNeedingEscape(input)
    return if (noEscapes == -1) {
      input
    } else {
      val length = input.length
      val needsEscapes = length - noEscapes
      val builder = StringBuilder(noEscapes + 4 + needsEscapes * 5 / 4)
      builder.append(input, 0, noEscapes)
      var offset = noEscapes
      while (offset < length) {
        val codepoint = input.codePointAt(offset)
        offset += Character.charCount(codepoint)
        val idx = indexOf(escapedCodePoints, codepoint)
        if (idx == -1) {
          builder.appendCodePoint(codepoint)
        } else {
          builder.appendCodePoint(escapeCodePoint)
          builder.appendCodePoint(escapedByCodePoints[idx])
        }
      }
      builder.toString()
    }
  }

  private fun firstOffsetNeedingUnescape(input: String): Int {
    val length = input.length
    var firstOffsetNeedingEscape = -1
    var offset = 0
    while (offset < length) {
      val codepoint = input.codePointAt(offset)
      if (codepoint == escapeCodePoint) {
        firstOffsetNeedingEscape = offset
        break
      }
      offset += Character.charCount(codepoint)
    }
    return firstOffsetNeedingEscape
  }

  fun doBackward(input: String): String {
    val noEscapes = firstOffsetNeedingUnescape(input)
    return if (noEscapes == -1) {
      input
    } else {
      val length = input.length
      val needsEscapes = length - noEscapes
      val builder = StringBuilder(noEscapes + 4 + needsEscapes * 5 / 4)
      builder.append(input, 0, noEscapes)
      var offset = noEscapes
      while (offset < length) {
        var codepoint = input.codePointAt(offset)
        offset += Character.charCount(codepoint)
        // if we need to escape something, escape it
        if (codepoint == escapeCodePoint) {
          if (offset < length) {
            codepoint = input.codePointAt(offset)
            val idx = indexOf(escapedByCodePoints, codepoint)
            if (idx != -1) {
              codepoint = escapedCodePoints[idx]
            }
            offset += Character.charCount(codepoint)
          } else {
            throw IllegalArgumentException(
                "Escape character '" +
                    String(intArrayOf(escapeCodePoint), 0, 1) +
                    "' can't be the last character in a string.")
          }
        }
        // we didn't escape it, append it raw
        builder.appendCodePoint(codepoint)
      }
      builder.toString()
    }
  }

  companion object {
    fun indexOf(arr: IntArray, target: Int): Int {
      for ((index, value) in arr.withIndex()) {
        if (value == target) {
          return index
        }
      }
      return -1
    }

    /**
     * If your escape policy is "'123", it means this:
     * ```
     * abc->abc
     * 123->'1'2'3
     * I won't->I won''t
     * ```
     */
    fun selfEscape(escapePolicy: String): PerCharacterEscaper {
      val escapedCodePoints = escapePolicy.codePoints().toArray()
      val escapeCodePoint = escapedCodePoints[0]
      return PerCharacterEscaper(escapeCodePoint, escapedCodePoints, escapedCodePoints)
    }

    /**
     * If your escape policy is "'a1b2c3d", it means this:
     * ```
     * abc->abc
     * 123->'b'c'd
     * I won't->I won'at
     * ```
     */
    fun specifiedEscape(escapePolicy: String): PerCharacterEscaper {
      val codePoints = escapePolicy.codePoints().toArray()
      require(codePoints.size % 2 == 0)
      val escapeCodePoint = codePoints[0]
      val escapedCodePoints = IntArray(codePoints.size / 2)
      val escapedByCodePoints = IntArray(codePoints.size / 2)
      for (i in escapedCodePoints.indices) {
        escapedCodePoints[i] = codePoints[2 * i]
        escapedByCodePoints[i] = codePoints[2 * i + 1]
      }
      return PerCharacterEscaper(escapeCodePoint, escapedCodePoints, escapedByCodePoints)
    }
  }
}