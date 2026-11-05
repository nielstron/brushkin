package io.github.nielstron.brushkin.ui

import android.content.SharedPreferences
import org.json.JSONArray

/** Persist identities, then resolve against today's catalog so actions never capture an old document. */
class RecentCommands(private val preferences: SharedPreferences) {
    private fun key(command: Cmd) = JSONArray(listOf(command.section, command.label)).toString()
    private fun keys(): List<String> = JSONArray(preferences.getString("commands", "[]")!!).let { array ->
        List(array.length()) { array.getString(it) }
    }

    fun record(command: Cmd) {
        val id = key(command)
        val recent = (listOf(id) + keys().filter { it != id }).take(6)
        preferences.edit().putString("commands", JSONArray(recent).toString()).apply()
    }

    fun resolve(catalog: List<Cmd>): List<Cmd> {
        val current = catalog.associateBy(::key)
        return keys().mapNotNull(current::get)
    }
}
