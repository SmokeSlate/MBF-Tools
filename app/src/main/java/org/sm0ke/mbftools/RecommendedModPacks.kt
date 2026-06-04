package org.sm0ke.mbftools

data class RecommendedMod(val id: String, val displayName: String)

data class RecommendedModPack(
        val title: String,
        val versionTag: String,
        val mods: List<RecommendedMod>
)

object RecommendedModPacks {
    private val modernPack =
            listOf(
                    RecommendedMod(id = "BetterSongSearch", displayName = "BetterSongSearch"),
                    RecommendedMod(id = "bsl", displayName = "Better Song List"),
                    RecommendedMod(id = "SongDownloader", displayName = "SongDownloader"),
                    RecommendedMod(id = "BeatLeader", displayName = "BeatLeader"),
                    RecommendedMod(id = "transitions", displayName = "Transitions")
            )

    private val packsByVersion =
            mapOf(
                    "1.40.8_7379" to
                            RecommendedModPack(
                                    title = "Recommended Essentials",
                                    versionTag = "1.40.8_7379",
                                    mods = modernPack
                            ),
                    "1.40.7_7060" to
                            RecommendedModPack(
                                    title = "Recommended Essentials",
                                    versionTag = "1.40.7_7060",
                                    mods = modernPack
                            ),
                    "1.40.6_6407" to
                            RecommendedModPack(
                                    title = "Recommended Essentials",
                                    versionTag = "1.40.6_6407",
                                    mods = modernPack
                            ),
                    "1.40.4_5283" to
                            RecommendedModPack(
                                    title = "Recommended Essentials",
                                    versionTag = "1.40.4_5283",
                                    mods = modernPack
                            )
            )

    fun forVersion(versionTag: String?): RecommendedModPack? {
        if (versionTag.isNullOrBlank()) {
            return null
        }
        return packsByVersion[versionTag]
    }
}
