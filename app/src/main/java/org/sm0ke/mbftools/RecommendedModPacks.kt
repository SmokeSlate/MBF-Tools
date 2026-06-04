package org.sm0ke.mbftools

data class RecommendedMod(val id: String, val displayName: String)

data class RecommendedModPack(
        val title: String,
        val versionTag: String,
        val mods: List<RecommendedMod>
) {
    val fingerprint: String
        get() = buildString {
            append(versionTag)
            append('|')
            append(title)
            append('|')
            append(mods.joinToString(separator = ",") { it.id })
        }
}

object RecommendedModPacks {
    private val modernPack =
            listOf(
                    RecommendedMod(id = "BetterSongSearch", displayName = "BetterSongSearch"),
                    RecommendedMod(id = "bsl", displayName = "Better Song List"),
                    RecommendedMod(id = "SongDownloader", displayName = "SongDownloader"),
                    RecommendedMod(id = "BeatLeader", displayName = "BeatLeader"),
                    RecommendedMod(id = "transitions", displayName = "Transitions")
            )

    private val pack1408_7379 =
            listOf(
                    RecommendedMod(id = "FakeId", displayName = "Fake ID"),
                    RecommendedMod(id = "datakeeper", displayName = "PlayerDataKeeper"),
                    RecommendedMod(id = "playlistcore", displayName = "PlaylistCore"),
                    RecommendedMod(id = "songcore", displayName = "SongCore"),
                    RecommendedMod(id = "songdownloader", displayName = "SongDownloader"),
                    RecommendedMod(id = "unicode", displayName = "Unicode"),
                    RecommendedMod(id = "BeatLeader", displayName = "BeatLeader"),
                    RecommendedMod(id = "BetterSongSearch", displayName = "BetterSongSearch"),
                    RecommendedMod(id = "chroma", displayName = "Chroma"),
                    RecommendedMod(id = "custommodels", displayName = "Custom Models"),
                    RecommendedMod(id = "graphicstweaks", displayName = "GraphicsTweaks"),
                    RecommendedMod(id = "HitScoreVisualizer", displayName = "HitScoreVisualizer"),
                    RecommendedMod(id = "IntroSkip", displayName = "Intro Skip"),
                    RecommendedMod(id = "MappingExtensions", displayName = "Mapping Extensions"),
                    RecommendedMod(id = "NoodleExtensions", displayName = "NoodleExtensions"),
                    RecommendedMod(id = "NoPromo", displayName = "NoPromo"),
                    RecommendedMod(id = "playlistdownloader", displayName = "PlaylistDownloader"),
                    RecommendedMod(id = "playlistmanager", displayName = "PlaylistManager"),
                    RecommendedMod(
                            id = "qbeatsaberplus-multiplayer",
                            displayName = "QBeatSaberPlus-Multiplayer"
                    ),
                    RecommendedMod(id = "RecentlyPlayed", displayName = "RecentlyPlayed"),
                    RecommendedMod(id = "replay", displayName = "Replay")
            )

    private val packsByVersion =
            mapOf(
                    "1.40.8_7379" to
                            RecommendedModPack(
                                    title = "Recommended Mods for 1.40.8_7379",
                                    versionTag = "1.40.8_7379",
                                    mods = pack1408_7379
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

    fun supportedVersionTags(): Set<String> = packsByVersion.keys
}
