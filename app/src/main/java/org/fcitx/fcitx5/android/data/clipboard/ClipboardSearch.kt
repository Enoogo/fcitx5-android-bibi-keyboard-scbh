/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber

/**
 * Matches clipboard entries against a search query.
 *
 * Besides a plain (case-insensitive) substring match, pinyin is supported:
 * both full pinyin ("weixin") and initials ("wx") match "微信".
 * Hanzi readings are loaded from a trimmed table of the GB2312 charset.
 */
object ClipboardSearch {

    private const val PINYIN_ASSET = "clipboard_pinyin.txt"

    /**
     * Only the first [MAX_SCAN_CHARS] characters of an entry are converted to pinyin,
     * to keep searching responsive for very long entries.
     */
    private const val MAX_SCAN_CHARS = 400

    @Volatile
    private var pinyinTable: Map<Char, Array<String>>? = null

    private fun table(): Map<Char, Array<String>> {
        pinyinTable?.let { return it }
        val map = HashMap<Char, Array<String>>(8192)
        try {
            appContext.assets.open(PINYIN_ASSET).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val tab = line.indexOf('\t')
                    if (tab != 1) return@forEach
                    map[line[0]] = line.substring(tab + 1).split(',').toTypedArray()
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to load pinyin table")
        }
        pinyinTable = map
        return map
    }

    fun matches(text: String, query: String): Boolean {
        if (query.isEmpty()) return true
        if (text.contains(query, ignoreCase = true)) return true
        // pinyin only makes sense for an ascii query
        val q = query.lowercase()
        if (q.any { it !in 'a'..'z' && it !in '0'..'9' }) return false
        return matchesPinyin(text, q)
    }

    private fun readingsOf(ch: Char, table: Map<Char, Array<String>>): Array<String>? = when {
        ch in 'a'..'z' || ch in '0'..'9' -> arrayOf(ch.toString())
        ch in 'A'..'Z' -> arrayOf(ch.lowercaseChar().toString())
        else -> table[ch]
    }

    /**
     * Check whether [q] can be read out of [text] as a sequence of pinyin syllables.
     * Each character may contribute a whole syllable ("weixin"), only its initial
     * letter ("wx"), and the last character may contribute a prefix of a syllable,
     * so that results show up while the user is still typing.
     */
    private fun matchesPinyin(text: String, q: String): Boolean {
        val table = table()
        if (table.isEmpty()) return false
        val length = minOf(text.length, MAX_SCAN_CHARS)
        var current = HashSet<Int>()
        var next = HashSet<Int>()
        for (i in 0 until length) {
            current.add(0)
            val readings = readingsOf(text[i], table)
            if (readings != null) {
                for (offset in current) {
                    if (offset == q.length) return true
                    for (syllable in readings) {
                        when {
                            q.startsWith(syllable, offset) ->
                                next.add(offset + syllable.length)

                            offset < q.length && q[offset] == syllable[0] ->
                                next.add(offset + 1)

                            offset < q.length && q.length - offset < syllable.length &&
                                    q.regionMatches(offset, syllable, 0, q.length - offset) ->
                                return true
                        }
                    }
                }
            }
            // swap buffers, the next character starts a fresh attempt at offset 0
            val tmp = current
            current = next
            next = tmp
            next.clear()
        }
        return current.contains(q.length)
    }
}
