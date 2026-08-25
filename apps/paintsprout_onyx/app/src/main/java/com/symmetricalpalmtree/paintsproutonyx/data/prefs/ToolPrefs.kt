package com.symmetricalpalmtree.paintsproutonyx.data.prefs

import android.content.Context
import com.symmetricalpalmtree.paintsproutonyx.sketchbook.Lead

/**
 * Which pencil is in the hand, remembered between sittings.
 *
 * It belongs to the artist rather than to any one sketchbook, which is why it lives here and not in
 * a `.soil`. Picking a lead is picking up a pencil: put it down at the end of an evening and it is
 * still the one you reach for tomorrow, whatever you open. A per-sketchbook lead would mean every
 * new sketchbook handing you a pencil you did not choose.
 *
 * Nothing here is private — a lead name says nothing about anybody's work — but the same rule as
 * [LibraryPrefs] still applies to anything that ever joins it: ordinary SharedPreferences is
 * readable to anything holding this app's data directory, so display names and anything the artist
 * typed stay in the encrypted index. A stored name from a build that knew about a lead this one does
 * not reads back as the default rather than throwing.
 */
class ToolPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var lead: Lead
        get() = Lead.byName(prefs.getString(KEY_LEAD, null))
        set(value) = prefs.edit().putString(KEY_LEAD, value.name).apply()

    private companion object {
        const val FILE = "tool_state"
        const val KEY_LEAD = "lead"
    }
}
