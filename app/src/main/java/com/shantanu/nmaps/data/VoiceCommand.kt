package com.shantanu.nmaps.data

/** What a spoken phrase asks for. */
sealed interface VoiceCommand {
    data class Navigate(val target: String) : VoiceCommand
    data class NavigateShortcut(val shortcut: Shortcut) : VoiceCommand
    data class Nearby(val category: Category) : VoiceCommand
    data object WhereAmI : VoiceCommand
    data class Search(val query: String) : VoiceCommand

    companion object {
        private val navigate = Regex("""^(?:navigate|directions|drive|walk|take me|go|get me|route|how do i get)(?:\s+(?:me))?(?:\s+(?:to|towards))?\s+(.+)$""", RegexOption.IGNORE_CASE)
        private val nearby = Regex("""^(?:find|show|search|where(?:'s| is)(?: the)?(?: nearest)?)?\s*(?:me\s+)?(?:a|an|the|some)?\s*(?:nearest|closest|nearby)?\s*(.+?)\s*(?:nearby|near me|around here|close by)?$""", RegexOption.IGNORE_CASE)

        private val categoryWords = mapOf(
            Category.Fuel to listOf("fuel", "petrol", "gas", "gas station", "petrol pump", "diesel", "cng"),
            Category.Charging to listOf("charging", "ev charging", "charger", "charging station"),
            Category.Atm to listOf("atm", "cash", "bank"),
            Category.Pharmacy to listOf("pharmacy", "chemist", "medical store", "medicine"),
            Category.Hospital to listOf("hospital", "clinic", "doctor"),
            Category.Parking to listOf("parking", "car park"),
            Category.Toilets to listOf("toilet", "toilets", "washroom", "restroom", "bathroom"),
            Category.Food to listOf("food", "restaurant", "restaurants", "cafe", "coffee", "something to eat", "lunch", "dinner", "breakfast"),
            Category.Grocery to listOf("grocery", "groceries", "supermarket", "kirana"),
        )

        fun parse(spoken: String): VoiceCommand {
            val text = spoken.trim().trimEnd('.', '?', '!').replace(Regex("\\s+"), " ")
            val lower = text.lowercase()
            if (lower in setOf("where am i", "show my location", "my location", "where am i now")) return WhereAmI

            navigate.find(text)?.let { m ->
                val target = m.groupValues[1].trim().removePrefix("the ").trim()
                return when (target.lowercase().removeSuffix(" please")) {
                    "home", "my home", "my house", "house" -> NavigateShortcut(Shortcut.Home)
                    "work", "office", "my office", "my work" -> NavigateShortcut(Shortcut.Work)
                    else -> Navigate(target.removeSuffix(" please"))
                }
            }
            // "Find petrol nearby", "nearest ATM", "where is the closest pharmacy"
            val stripped = nearby.find(text)?.groupValues?.get(1)?.lowercase()?.trim().orEmpty()
            categoryWords.entries.firstOrNull { (_, words) -> stripped in words || lower in words }?.let { return Nearby(it.key) }
            return Search(text)
        }
    }
}
