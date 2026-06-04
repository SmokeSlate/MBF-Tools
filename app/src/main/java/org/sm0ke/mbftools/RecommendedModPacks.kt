package org.sm0ke.mbftools

data class RecommendedMod(val id: String, val displayName: String)

data class RecommendedModPack(
        val title: String,
        val versionTag: String,
        val mods: List<RecommendedMod>,
        val bundledQmodFileName: String? = null,
        val bundledQmodBase64: String? = null
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

    private const val pack1408_7379QmodFileName = "mods.qmod"

    private const val pack1408_7379QmodBase64 =
            "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"

    private val packsByVersion =
            mapOf(
                    "1.40.8_7379" to
                            RecommendedModPack(
                                    title = "Recommended Mods for 1.40.8_7379",
                                    versionTag = "1.40.8_7379",
                                    mods = pack1408_7379,
                                    bundledQmodFileName = pack1408_7379QmodFileName,
                                    bundledQmodBase64 = pack1408_7379QmodBase64
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
        val trimmed = versionTag.trim()
        packsByVersion[trimmed]?.let { return it }

        val normalized = normalizeVersionTag(trimmed)
        packsByVersion[normalized]?.let { return it }

        val versionName = versionNameOf(normalized)
        val byVersionName =
                packsByVersion.entries.filter { versionNameOf(it.key) == versionName }.map { it.value }
        if (byVersionName.size == 1) {
            return byVersionName.first()
        }

        return null
    }

    fun supportedVersionTags(): Set<String> = packsByVersion.keys

    private fun normalizeVersionTag(versionTag: String): String {
        return versionTag.trim().replace('+', '_').replace(' ', '_')
    }

    private fun versionNameOf(versionTag: String): String {
        return normalizeVersionTag(versionTag).substringBefore('_')
    }
}
