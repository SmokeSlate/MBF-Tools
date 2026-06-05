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
            "UEsDBBQAAAAIAFSExFyn90bFaQQAAHUTAAAIAAAAbW9kLmpzb26lmF1z2jgUhq+TX5HJTO+KhAkU6M1OEza7mQ0dEtjc7HQZYStYg7AUSSQlnf73Hsk2H45ExvQiX9Lx+z46PhJH+XF6cj69Gz1QpZnIzj+fnTdRhKLzjzCekSW1I/c0FkuaJTQ5G4pEnz0KdRahdhP1pt2Lbt/FssRGDi+vpxMhuJ7CMxA7rYaRlUmFsqHjpVjQMSeGuomE6lgxaQqIgXjJuCBgZlJ6prYAyxDA83YFEWq6IUniBZnTG4cGCmhGiZnDorT7TZMZVbuBD7sS++pga3Fojh4Lw0mWtMqpa8aphpn/vtkBzmaKqPX+4CP8dSUk2xmKhVz/+d3QzJpuhxMq7VKzuIg9PTn5AV9limd6ya3vyd6S/2+iNup0iomkyN7N45BpzbK5DUmNkfozxnNm0tUMQT7wTD81pFBG47sV1aZxOR7eYkU5JZpqXKrg51wc22n0BOs9B5efHytg8UobsWyYtQRuH2DUQxc1AR3WKH87Q5LBd4VveOtKSjczAa8RMXEKCn5u64mvHJkN1mH8a7KwleIFR/2a3AOSTVJL3Gt1G5dQa2Nba/lykHMaBHhR/0Prcqf6sI1GN4MwOCeSvXq5W6hVN+GKsIwSaqjKCMe3VtoParXz+TDZkhoSC0U9cBH6hFo12YYgx5lxP69A1gfmdDcRYTRJJFWtqd7dyRXENki1ayJeQ97gwEroM3YOXHjr0knjKkOYNiEGqgDOBRV4z3U3fpnKESdrqgYg/4+TD73rzpvIA6mFSM60Cb75zm/hWunw2+8UqGVUGFOLbB5EjFCr7qvf3zn5eToGjzCrNcFlyGHQ8rFAAfRQryZtQtQiY/PURM1O00EMNhb+IgCPSlwYeZWxWCS+1NoTrnvk2/83V3WpDZ2e3TIqDGeP41sazGU/bx5q8G0FsW0ruPu1Yf39lOCw88whUAPVZHM+pkTFqQcXTmEURUd/6lcdgqnNfRoz3cg/mt48eaAtSJVYEi971Kz9GbBlv3K6flgrXAS816/ALOW+fiWySz6yUvOGY+ik/XsftPeiwphzRWTKYm1eKFkEGqva2/9aiVeafeH0O/6r0J84/dC+6lXiwrx/MzO25+oD0ytIxqt3m12gZu3kQiHBoS5kStULZVlKVGLwWzffCpydJza8ipvMKDFeMBmojLp1ey/WwJ5F/X4fb7Qbwf3mLLaB03wgjDskUoL5zm3C2yd0ajfgKqZ8bSh+Y+Chzg3ehjrsP+5bXvKvAm5W9CD4Mb3i9pyoGoS7xmpkON1fxQjOFhHYjFHdJB+8LRReoY0ZXVTuC0X8+/3ZOz3FxdGnX9l/vddRgIMn9n3wZX4R9FZK+7epy2umt0zaO8hFYJj3afNPBslXurFcccOka6M96J+OQLdHYCwlvtvUzMgaDbdGjTKvvuU4ywMPh1d2T2OaGb52lwLfnTmCA7duGzUhduOlDO+r+18EyFfiptG03Zz2wtCK2mV5a71z9F303on66xtUi/kt1OnJt9Ofp78AUEsBAhQAFAAAAAgAVITEXKf3RsVpBAAAdRMAAAgAAAAAAAAAAAAAAAAAAAAAAG1vZC5qc29uUEsFBgAAAAABAAEANgAAAI8EAAAAAA=="

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
